package ch.abwesend.foldervault.infrastructure.restore

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.Fvc1Header
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.logging.FileNameRedactor
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.domain.restore.RestoreScanResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.result.rethrowCancellation
import ch.abwesend.foldervault.infrastructure.storage.ScopedStorageHelper
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import javax.crypto.SecretKey

class RestoreEngine(
    context: Context,
    private val cipher: IFvc1Cipher,
    private val dispatchers: IDispatchers,
) : IRestoreEngine {

    private val context = context.applicationContext

    private companion object {
        private const val CRYPT_SUFFIX = ".crypt"
        private const val MIME_OCTET_STREAM = "application/octet-stream"
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private data class SourceFileEntry(val relativePath: String, val documentFile: DocumentFile)

    /** Outcome of resolving an output file: a deliberate skip, an unexpected failure, or a target. */
    private sealed interface OutputResolution {
        data class Resolved(val file: DocumentFile) : OutputResolution
        object Skip : OutputResolution
        object Failed : OutputResolution
    }

    override suspend fun scanSourceFolder(sourceUri: String): RestoreScanResult =
        withContext(dispatchers.io) {
            var cryptCount = 0
            var otherCount = 0
            ScopedStorageHelper.walkTree(context, Uri.parse(sourceUri)) { relPath, _ ->
                if (relPath.endsWith(CRYPT_SUFFIX)) cryptCount++ else otherCount++
            }
            RestoreScanResult(cryptCount, otherCount)
        }

    override suspend fun decryptAll(
        sourceUri: String,
        outputUri: String,
        password: String,
        collisionPolicy: RestoreCollisionPolicy,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult = withContext(dispatchers.io) {
        val outputRoot = DocumentFile.fromTreeUri(context, Uri.parse(outputUri))
            ?: return@withContext RestoreResult.Failure("Cannot access output folder")

        val files = mutableListOf<SourceFileEntry>()
        val accessible = ScopedStorageHelper.walkTree(context, Uri.parse(sourceUri)) { relPath, doc ->
            files.add(SourceFileEntry(relPath, doc))
        }
        if (!accessible) return@withContext RestoreResult.Failure("Cannot access source folder")

        if (files.isEmpty()) return@withContext RestoreResult.Success(0, 0, 0, 0)

        // PBKDF2 (310k iterations) costs ~0.5–2 s per derivation, so derive lazily once per
        // distinct salt + iteration count and reuse across every file that shares it. A folder
        // normally has a single salt, but merged backups may mix several (BUG-6).
        val keyCache = mutableMapOf<String, SecretKey>()

        val invalidPassword = !verifyProbePassword(files, password, keyCache)
        if (invalidPassword) return@withContext RestoreResult.InvalidPassword

        var decrypted = 0
        var copied = 0
        var skipped = 0
        var failed = 0
        val total = files.size

        for ((index, entry) in files.withIndex()) {
            if (!isActive) return@withContext RestoreResult.Cancelled
            onProgress(RestoreProgress(total, index, failed, entry.documentFile.name ?: ""))

            val isCrypt = entry.relativePath.endsWith(CRYPT_SUFFIX)
            val outputRelPath = RestorePathResolver.outputRelativePath(entry.relativePath, isCrypt)

            when (val resolution = resolveOutputFile(outputRoot, outputRelPath, collisionPolicy)) {
                is OutputResolution.Skip -> skipped++
                is OutputResolution.Failed -> failed++
                is OutputResolution.Resolved -> {
                    val outputFile = resolution.file
                    val success = if (isCrypt) {
                        val key = keyFor(entry.documentFile, password, keyCache)
                        key != null && decryptEntry(entry.documentFile, outputFile, key)
                    } else {
                        copyEntry(entry.documentFile, outputFile)
                    }

                    if (success) {
                        if (isCrypt) decrypted++ else copied++
                    } else {
                        deletePartialOutput(outputFile)
                        failed++
                    }
                }
            }
        }

        onProgress(RestoreProgress(total, total, failed, ""))
        RestoreResult.Success(decrypted, copied, skipped, failed)
    }

    private fun withInputStream(source: DocumentFile, block: (InputStream) -> Boolean): Boolean =
        try {
            context.contentResolver.openInputStream(source.uri)?.use(block) ?: false
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.warning("Failed to open input stream for ${FileNameRedactor.redact(source.name.orEmpty())}", e)
            false
        }

    private fun withStreams(
        source: DocumentFile,
        output: DocumentFile,
        block: (InputStream, OutputStream) -> Boolean,
    ): Boolean =
        try {
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                context.contentResolver.openOutputStream(output.uri)?.use { out ->
                    block(input, out)
                } ?: false
            } ?: false
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.warning(
                "Failed to open streams while restoring ${FileNameRedactor.redact(source.name.orEmpty())}",
                e,
            )
            false
        }

    /**
     * Verifies the password against the *smallest* `.crypt` file, deriving its key into [cache]
     * for later reuse. Probing the smallest file matters because a full GCM decrypt must read to
     * the tag at the end — picking the first file could mean decrypting a multi-GB video just to
     * check the password (BUG-6). Returns `true` when there is nothing encrypted to verify.
     */
    private fun verifyProbePassword(
        files: List<SourceFileEntry>,
        password: String,
        cache: MutableMap<String, SecretKey>,
    ): Boolean {
        val probe = files
            .filter { it.relativePath.endsWith(CRYPT_SUFFIX) }
            .minByOrNull { it.documentFile.length() }
        return if (probe == null) {
            true
        } else {
            val key = keyFor(probe.documentFile, password, cache)
            key != null && verifyPassword(probe, key)
        }
    }

    private fun verifyPassword(entry: SourceFileEntry, key: SecretKey): Boolean =
        withInputStream(entry.documentFile) { input ->
            cipher.decryptFile(key, input, NullOutputStream) is SuccessResult
        }

    /**
     * Returns the AES key for [file], deriving it from the file's FVC1 header parameters on first
     * use and caching it by `salt + iterations` so repeated files share one expensive derivation
     * (BUG-6). Returns `null` if the header cannot be read.
     */
    private fun keyFor(
        file: DocumentFile,
        password: String,
        cache: MutableMap<String, SecretKey>,
    ): SecretKey? =
        readHeader(file)?.let { header ->
            val cacheKey = "${Base64.getEncoder().encodeToString(header.salt)}:${header.iterations}"
            cache.getOrPut(cacheKey) { cipher.deriveKey(password, header.salt, header.iterations) }
        }

    private fun readHeader(file: DocumentFile): Fvc1Header? =
        try {
            context.contentResolver.openInputStream(file.uri)?.use { Fvc1Header.readFrom(it) }
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.warning("Failed to read FVC1 header for ${FileNameRedactor.redact(file.name.orEmpty())}", e)
            null
        }

    /**
     * Resolves the concrete output [DocumentFile] for a source entry, applying [policy] on
     * collision. Distinguishes three outcomes so the caller can count them correctly (BUG-7):
     * [OutputResolution.Skip] (a deliberate SKIP), [OutputResolution.Failed] (an operation that
     * should have worked but didn't — directory or file creation failed, or an OVERWRITE delete
     * was rejected), and [OutputResolution.Resolved].
     */
    private fun resolveOutputFile(
        outputRoot: DocumentFile,
        relPath: String,
        policy: RestoreCollisionPolicy,
    ): OutputResolution {
        val parts = relPath.split("/")
        val fileName = parts.last()
        val dirParts = parts.dropLast(1)

        var dir = outputRoot
        for (part in dirParts) {
            dir = dir.findFile(part)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(part)
                ?: return OutputResolution.Failed
        }

        val existing = dir.findFile(fileName)
        return when {
            existing == null -> createOutput(dir, fileName)
            policy == RestoreCollisionPolicy.SKIP -> OutputResolution.Skip
            policy == RestoreCollisionPolicy.OVERWRITE ->
                if (existing.delete()) createOutput(dir, fileName) else OutputResolution.Failed
            else -> resolveWithSuffix(dir, fileName)
        }
    }

    private fun createOutput(dir: DocumentFile, name: String): OutputResolution =
        dir.createFile(MIME_OCTET_STREAM, name)
            ?.let { OutputResolution.Resolved(it) }
            ?: OutputResolution.Failed

    /**
     * For [RestoreCollisionPolicy.RENAME_WITH_SUFFIX], loops `_restored`, `_restored_2`, … until a
     * name that does not already exist is found, so a repeated restore does not silently collide
     * with a previously restored copy (BUG-7).
     */
    private fun resolveWithSuffix(dir: DocumentFile, fileName: String): OutputResolution {
        var index = 1
        var candidate = RestorePathResolver.indexedRestoreName(fileName, index)
        while (dir.findFile(candidate) != null) {
            index++
            candidate = RestorePathResolver.indexedRestoreName(fileName, index)
        }
        return createOutput(dir, candidate)
    }

    private fun decryptEntry(source: DocumentFile, output: DocumentFile, key: SecretKey): Boolean =
        withStreams(source, output) { input, out ->
            cipher.decryptFile(key, input, out) is SuccessResult
        }

    private fun copyEntry(source: DocumentFile, output: DocumentFile): Boolean =
        withStreams(source, output) { input, out ->
            input.copyTo(out)
            true
        }

    /**
     * Best-effort removal of a partially written output file after a failed decrypt/copy. Without
     * this the user is left with a truncated plaintext file that is indistinguishable from a good
     * one and counted only in `failed` (BUG-7).
     */
    private fun deletePartialOutput(output: DocumentFile) {
        val deleted = try {
            output.delete()
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.warning("Failed to delete partial output ${FileNameRedactor.redact(output.name.orEmpty())}", e)
            false
        }
        if (!deleted) {
            logger.warning("Could not delete partial output ${FileNameRedactor.redact(output.name.orEmpty())}")
        }
    }
}
