package ch.abwesend.foldervault.infrastructure.restore

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.domain.restore.RestoreScanResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.infrastructure.storage.ScopedStorageHelper
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

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
        ScopedStorageHelper.walkTree(context, Uri.parse(sourceUri)) { relPath, doc ->
            files.add(SourceFileEntry(relPath, doc))
        }

        if (files.isEmpty()) return@withContext RestoreResult.Success(0, 0, 0, 0)

        val firstCrypt = files.firstOrNull { it.relativePath.endsWith(CRYPT_SUFFIX) }
        if (firstCrypt != null && !verifyPassword(firstCrypt, password)) {
            return@withContext RestoreResult.InvalidPassword
        }

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
            val outputFile = resolveOutputFile(outputRoot, outputRelPath, collisionPolicy)

            if (outputFile == null) {
                skipped++
                continue
            }

            val success = if (isCrypt) {
                decryptEntry(entry.documentFile, outputFile, password)
            } else {
                copyEntry(entry.documentFile, outputFile)
            }

            if (success) {
                if (isCrypt) decrypted++ else copied++
            } else {
                failed++
            }
        }

        onProgress(RestoreProgress(total, total, failed, ""))
        RestoreResult.Success(decrypted, copied, skipped, failed)
    }

    private fun withInputStream(source: DocumentFile, block: (InputStream) -> Boolean): Boolean =
        try {
            context.contentResolver.openInputStream(source.uri)?.use(block) ?: false
        } catch (e: Exception) {
            logger.warning("Failed to open input stream for ${source.name}", e)
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
            logger.warning("Failed to open streams while restoring ${source.name}", e)
            false
        }

    private fun verifyPassword(entry: SourceFileEntry, password: String): Boolean =
        withInputStream(entry.documentFile) { input ->
            cipher.decryptFileWithPassword(password, input, NullOutputStream) is SuccessResult
        }

    private fun resolveOutputFile(
        outputRoot: DocumentFile,
        relPath: String,
        policy: RestoreCollisionPolicy,
    ): DocumentFile? {
        val parts = relPath.split("/")
        val fileName = parts.last()
        val dirParts = parts.dropLast(1)

        var dir = outputRoot
        for (part in dirParts) {
            dir = dir.findFile(part)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(part)
                ?: return null
        }

        val existing = dir.findFile(fileName)
        return when {
            existing == null -> dir.createFile(MIME_OCTET_STREAM, fileName)
            policy == RestoreCollisionPolicy.SKIP -> null
            policy == RestoreCollisionPolicy.OVERWRITE -> {
                existing.delete()
                dir.createFile(MIME_OCTET_STREAM, fileName)
            }
            else -> {
                val newName = RestorePathResolver.resolvedName(fileName, policy) ?: return null
                dir.createFile(MIME_OCTET_STREAM, newName)
            }
        }
    }

    private fun decryptEntry(source: DocumentFile, output: DocumentFile, password: String): Boolean =
        withStreams(source, output) { input, out ->
            cipher.decryptFileWithPassword(password, input, out) is SuccessResult
        }

    private fun copyEntry(source: DocumentFile, output: DocumentFile): Boolean =
        withStreams(source, output) { input, out ->
            input.copyTo(out)
            true
        }
}
