package ch.abwesend.foldervault.infrastructure.restore

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.DecryptionError
import ch.abwesend.foldervault.domain.crypto.Fvc1Header
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.logging.FileNameRedactor
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreFailureReason
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.domain.restore.RestoreRunControl
import ch.abwesend.foldervault.domain.restore.RestoreScanResult
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.result.rethrowCancellation
import ch.abwesend.foldervault.infrastructure.storage.ScopedStorageHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import javax.crypto.SecretKey

/**
 * Cooperative-cancellation chunking for the single-file restore flow: a source file larger than
 * [thresholdBytes] is consumed in chunks of [chunkSizeBytes] with a cancellation check between
 * chunks, so the number of chunks grows with the file size and a cancel never has to wait for
 * more than one chunk of decrypt/copy work. Files up to the threshold run as a single piece.
 * Overridable so tests can exercise the chunked path with small fixtures.
 */
data class CancellationChunking(val thresholdBytes: Long, val chunkSizeBytes: Long) {
    companion object {
        private const val MEBI_BYTE = 1024L * 1024L
        val DEFAULT = CancellationChunking(thresholdBytes = 100 * MEBI_BYTE, chunkSizeBytes = 50 * MEBI_BYTE)
    }
}

class RestoreEngine(
    context: Context,
    private val cipher: IFvc1Cipher,
    private val dispatchers: IDispatchers,
    private val cancellationChunking: CancellationChunking = CancellationChunking.DEFAULT,
) : IRestoreEngine {

    private val context = context.applicationContext

    private companion object {
        private const val CRYPT_SUFFIX = Fvc1Header.CRYPT_FILE_SUFFIX
        private const val MIME_OCTET_STREAM = "application/octet-stream"

        /**
         * Output streams are opened with an explicit "write + truncate" mode: the default "w"
         * leaves truncation provider-dependent (Google Drive's provider notoriously does not
         * truncate), which would leave trailing bytes of the previous content behind when writing
         * into a pre-existing document — e.g. one the "Save as" picker returned for an overwrite.
         */
        private const val OUTPUT_STREAM_MODE = "wt"
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    /**
     * Pass-through [InputStream] that invokes [checkCancelled] each time another [chunkSizeBytes]
     * consumed bytes complete a chunk, making a long single-file decrypt/copy cooperatively
     * cancellable. [checkCancelled] aborts by throwing (a `CancellationException` from
     * `ensureActive`), which propagates out of the crypto/copy loop through the regular
     * cancellation-rethrow path.
     */
    private class ChunkedCancellationInputStream(
        private val delegate: InputStream,
        private val chunkSizeBytes: Long,
        private val checkCancelled: () -> Unit,
    ) : InputStream() {
        private var bytesSinceLastCheck = 0L

        override fun read(): Int {
            val byte = delegate.read()
            if (byte != -1) {
                countAndCheck(consumed = 1)
            }
            return byte
        }

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            val count = delegate.read(destination, offset, length)
            if (count > 0) {
                countAndCheck(consumed = count.toLong())
            }
            return count
        }

        override fun available(): Int = delegate.available()

        override fun close() = delegate.close()

        private fun countAndCheck(consumed: Long) {
            bytesSinceLastCheck += consumed
            if (bytesSinceLastCheck >= chunkSizeBytes) {
                bytesSinceLastCheck = 0
                checkCancelled()
            }
        }
    }

    private data class SourceFileEntry(val relativePath: String, val documentFile: DocumentFile)

    /** Outcome of reading a picked source's FVC1 header — see [probeSourceHeader]. */
    private sealed interface HeaderProbe {
        /** The file is FVC1-encrypted and its header parsed. */
        data class Parsed(val header: Fvc1Header) : HeaderProbe

        /** The file opened fine but carries no valid FVC1 header: a plain file, or a damaged one. */
        object NotEncrypted : HeaderProbe

        /** The file could not be opened at all — a revoked grant, a provider that is gone. */
        object Unreadable : HeaderProbe
    }

    /** Outcome of resolving an output file: a deliberate skip, an unexpected failure, or a target. */
    private sealed interface OutputResolution {
        /** [directory] is carried along so a failed write can un-index [file] from the tree cache. */
        data class Resolved(val directory: DocumentFile, val file: DocumentFile) : OutputResolution
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
        runControl: RestoreRunControl,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult = withContext(dispatchers.io) {
        // Restoring a folder into itself destroys data rather than restoring it: a plain
        // (non-encrypted) entry keeps its relative path, so it resolves to *itself* as its own
        // output — and under OVERWRITE the collision handling deletes the existing document before
        // re-creating it, leaving the following copy nothing to read from. The file is simply gone.
        // Checked before anything is listed or written, like the single-file flow's own guard.
        // Two *different* uris addressing the same tree are still not caught — SAF offers no way
        // to tell — but both pickers hand back the same uri for the same folder.
        if (sourceUri == outputUri) {
            logger.warning("Refusing a folder restore whose output tree is its own source")
            return@withContext RestoreResult.Failure(RestoreFailureReason.OUTPUT_FOLDER_SAME_AS_SOURCE)
        }

        val outputRoot = DocumentFile.fromTreeUri(context, Uri.parse(outputUri))
            ?: return@withContext RestoreResult.Failure(RestoreFailureReason.OUTPUT_FOLDER_NOT_ACCESSIBLE)

        val files = mutableListOf<SourceFileEntry>()
        val accessible = ScopedStorageHelper.walkTree(context, Uri.parse(sourceUri)) { relPath, doc ->
            files.add(SourceFileEntry(relPath, doc))
        }
        if (!accessible) return@withContext RestoreResult.Failure(RestoreFailureReason.SOURCE_FOLDER_NOT_ACCESSIBLE)

        if (files.isEmpty()) return@withContext RestoreResult.Success(0, 0, 0, 0)

        // PBKDF2 (310k iterations) costs ~0.5–2 s per derivation, so derive lazily once per
        // distinct salt + iteration count and reuse across every file that shares it. A folder
        // normally has a single salt, but merged backups may mix several (BUG-6).
        val keyCache = mutableMapOf<String, SecretKey>()

        val mayProceed = verifyProbePassword(files, password, keyCache, runControl) { ensureActive() }
        // Order matters: a stop that landed *during* probing leaves the outcomes incomplete, and
        // an incomplete set can read as "every usable probe failed" — reporting a wrong password
        // for a run the user simply cancelled. So the stop is answered first.
        if (runControl.shouldStop()) return@withContext RestoreResult.Cancelled(0, 0, 0, 0)
        if (!mayProceed) return@withContext RestoreResult.InvalidPassword

        // One listing per output directory instead of a full listing per restored file.
        val outputTree = OutputTreeCache(outputRoot)
        val counters = RestoreCounters()
        val total = files.size
        var stopped = false

        for ((index, entry) in files.withIndex()) {
            if (runControl.shouldStop()) {
                stopped = true
                break
            }
            // Nothing in this loop suspends — the whole body is blocking stream I/O — so without
            // an explicit liveness check cancellation could not stop it at all: a host that went
            // away (the foreground service destroyed, the OS time limit drained) would leave the
            // run writing decrypted files into the output tree for as long as the process lived,
            // and `withContext` would then discard the finished result and report the completed
            // restore as interrupted. The cooperative `shouldStop` above stays the *normal* way
            // to end a run early (it can report partial counts); this is the hard backstop.
            ensureActive()
            onProgress(RestoreProgress(total, index, counters.failed, entry.documentFile.name ?: ""))
            restoreEntry(entry, outputTree, collisionPolicy, password, keyCache, counters)
        }

        if (stopped) {
            counters.toCancelled()
        } else {
            onProgress(RestoreProgress(total, total, counters.failed, ""))
            counters.toSuccess()
        }
    }

    /** Running tally of a folder restore, so the loop body stays free of four `var` reassignments. */
    private class RestoreCounters {
        var decrypted = 0
        var copied = 0
        var skipped = 0
        var failed = 0

        fun toSuccess(): RestoreResult.Success = RestoreResult.Success(decrypted, copied, skipped, failed)
        fun toCancelled(): RestoreResult.Cancelled = RestoreResult.Cancelled(decrypted, copied, skipped, failed)
    }

    /** Restores one entry of the folder walk into the output tree, updating [counters]. */
    @Suppress("LongParameterList")
    private fun restoreEntry(
        entry: SourceFileEntry,
        outputTree: OutputTreeCache,
        collisionPolicy: RestoreCollisionPolicy,
        password: String,
        keyCache: MutableMap<String, SecretKey>,
        counters: RestoreCounters,
    ) {
        val isCrypt = entry.relativePath.endsWith(CRYPT_SUFFIX)
        val outputRelPath = RestorePathResolver.outputRelativePath(entry.relativePath, isCrypt)

        when (val resolution = resolveOutputFile(outputTree, outputRelPath, collisionPolicy)) {
            is OutputResolution.Skip -> counters.skipped++
            is OutputResolution.Failed -> counters.failed++
            is OutputResolution.Resolved -> {
                val outputFile = resolution.file
                val success = if (isCrypt) {
                    decryptEntry(entry.documentFile, outputFile, password, keyCache)
                } else {
                    copyEntry(entry.documentFile, outputFile)
                }

                if (success) {
                    if (isCrypt) counters.decrypted++ else counters.copied++
                } else {
                    // Name read before the delete — afterwards the provider reports none.
                    val outputName = outputTree.nameOf(outputFile)
                    deletePartialOutput(outputFile)
                    outputTree.forgetChild(resolution.directory, outputName)
                    counters.failed++
                }
            }
        }
    }

    /**
     * Restores one picked file into the destination document [outputFileUri] — the document the
     * system "Save as" file picker created (or handed back for an overwrite). Reuses the same
     * crypto path as [decryptAll]'s loop body for a single item. Whether to decrypt or copy is
     * decided in two stages: first the FVC1 header is read ([probeSourceHeader]) — a file whose
     * header parses is decrypted regardless of its name, since a user-picked SAF provider may
     * report a display name without the `.crypt` suffix (or none at all). Only when the file opens
     * but carries no valid header does the `.crypt` filename suffix decide: a suffixed file is
     * still treated as encrypted, so its unreadable header surfaces as a clear failure instead of
     * copying encrypted bytes verbatim; anything else is copied. A source that cannot be *opened*
     * at all is a third case, reported as [RestoreFailureReason.SOURCE_FILE_NOT_ACCESSIBLE] before
     * either stream is touched. Only a GCM tag failure is reported as
     * [RestoreResult.InvalidPassword] — on a lone file that is overwhelmingly a wrong password,
     * and we cannot tell it apart from tampering without the rest of the backup to probe against.
     * Unreadable headers, corrupt files and stream failures surface as [RestoreResult.Failure]
     * instead.
     *
     * On every non-success outcome — failures *and* cancellation — the output document is deleted
     * again via [cleanUpSingleFileOutput]. Cancellation is honored cooperatively: a source above
     * [CancellationChunking.thresholdBytes] — or of unknown size — is consumed through
     * [ChunkedCancellationInputStream], which re-checks the coroutine's liveness after every chunk,
     * so a cancel aborts within one chunk of work instead of after the whole file. Smaller files
     * still run to completion first, which is why the explicit catch stays: `withContext` then
     * discards the (possibly fully decrypted) result and throws, and without the cleanup the
     * plaintext would silently remain at the picked location even though the user asked to abort.
     */
    override suspend fun decryptSingleFile(
        sourceFileUri: String,
        outputFileUri: String,
        password: String,
    ): RestoreResult {
        // The "Save as" picker can hand back the *source* document itself — same folder, the
        // source's own name typed back in and the overwrite confirmed. Restoring into it would
        // open the source for reading and then truncate that very document with mode "wt",
        // destroying the only copy of the encrypted backup, which no password can undo. Checked
        // before anything is resolved or opened, and returned straight away so the usual output
        // cleanup cannot delete the source either. Two *different* uris addressing the same
        // document are still not caught — SAF offers no way to tell — but this is the case a user
        // can actually stumble into.
        if (sourceFileUri == outputFileUri) {
            logger.warning("Refusing a single-file restore whose output is its own source")
            return RestoreResult.Failure(RestoreFailureReason.OUTPUT_SAME_AS_SOURCE)
        }

        var outputWritten = false
        var cleanedUp = false
        val markOutputWritten = { outputWritten = true }
        // Resolved before the `withContext` so that a cancellation arriving before the body ever
        // runs can still clean the picker-created document up; `fromSingleUri` does no I/O.
        val output = DocumentFile.fromSingleUri(context, Uri.parse(outputFileUri))
        return try {
            withContext(dispatchers.io) {
                val source = DocumentFile.fromSingleUri(context, Uri.parse(sourceFileUri))
                val result = when {
                    source == null -> RestoreResult.Failure(RestoreFailureReason.SOURCE_FILE_NOT_ACCESSIBLE)
                    output == null -> RestoreResult.Failure(RestoreFailureReason.OUTPUT_FILE_NOT_ACCESSIBLE)
                    else -> {
                        val wrapInput = singleFileCancellationWrapper(source.length()) { ensureActive() }
                        when (val probe = probeSourceHeader(source)) {
                            // Reported before either stream is opened, so an unreadable source
                            // cannot masquerade as a copy/decrypt failure — and cannot cost the
                            // user a picked overwrite target either, since nothing was truncated.
                            is HeaderProbe.Unreadable ->
                                RestoreResult.Failure(RestoreFailureReason.SOURCE_FILE_NOT_ACCESSIBLE)
                            is HeaderProbe.Parsed ->
                                decryptSingle(source, output, password, probe.header, markOutputWritten, wrapInput)
                            is HeaderProbe.NotEncrypted -> if (source.name.orEmpty().endsWith(CRYPT_SUFFIX)) {
                                // Suffixed but unreadable header: surface it as a clear decryption
                                // failure rather than copying encrypted bytes out verbatim.
                                decryptSingle(source, output, password, null, markOutputWritten, wrapInput)
                            } else {
                                copySingle(source, output, markOutputWritten, wrapInput)
                            }
                        }
                    }
                }
                if (result !is RestoreResult.Success && output != null) {
                    cleanUpSingleFileOutput(output, outputWritten)
                    cleanedUp = true
                }
                result
            }
        } catch (e: CancellationException) {
            // A failure result followed by a cancel at the withContext exit would otherwise clean
            // up twice — the second delete of the already-removed document only logs a warning.
            if (!cleanedUp) {
                output?.let {
                    withContext(NonCancellable + dispatchers.io) {
                        cleanUpSingleFileOutput(it, outputWritten)
                    }
                }
            }
            throw e
        }
    }

    /**
     * Decrypts one picked encrypted file. [header] is the already-parsed FVC1 header; `null` means
     * the file was classified as encrypted by its `.crypt` suffix alone although its header could
     * not be read. Cleanup of the output on failure is the caller's job.
     */
    @Suppress("LongParameterList")
    private fun decryptSingle(
        source: DocumentFile,
        output: DocumentFile,
        password: String,
        header: Fvc1Header?,
        onOutputOpened: () -> Unit,
        wrapInput: (InputStream) -> InputStream,
    ): RestoreResult =
        if (header == null) {
            RestoreResult.Failure(RestoreFailureReason.FILE_HEADER_NOT_READABLE)
        } else {
            // The header was already parsed for the decrypt-vs-copy decision, but the cipher reads
            // its own from the stream — so the provider hands the *stream's* header back here.
            val error = decryptEntryDetailed(source, output, onOutputOpened, wrapInput) { streamHeader ->
                cipher.deriveKey(password, streamHeader.salt, streamHeader.iterations)
            }.getErrorOrNull()
            when (error) {
                null -> RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0)
                DecryptionError.INVALID_PASSWORD -> RestoreResult.InvalidPassword
                DecryptionError.INVALID_FILE -> RestoreResult.Failure(RestoreFailureReason.INVALID_ENCRYPTED_FILE)
                DecryptionError.UNKNOWN -> RestoreResult.Failure(RestoreFailureReason.DECRYPTION_FAILED)
            }
        }

    /** Copies one picked plain file verbatim. Cleanup of the output on failure is the caller's job. */
    private fun copySingle(
        source: DocumentFile,
        output: DocumentFile,
        onOutputOpened: () -> Unit,
        wrapInput: (InputStream) -> InputStream,
    ): RestoreResult =
        if (copyEntry(source, output, onOutputOpened, wrapInput)) {
            RestoreResult.Success(decrypted = 0, copied = 1, skipped = 0, failed = 0)
        } else {
            RestoreResult.Failure(RestoreFailureReason.COPY_FAILED)
        }

    /**
     * Removes the "Save as" output document after a failed or cancelled single-file restore, so no
     * truncated plaintext is left behind masquerading as a restored file. Deletion is limited to
     * two cases: the restore actually opened the document's output stream (its previous content is
     * already lost to truncation, only garbage could remain), or the document is still empty (the
     * picker freshly created it). A pre-existing document the restore never touched — e.g. when the
     * source file itself turned out to be unreadable — is left intact.
     */
    private fun cleanUpSingleFileOutput(output: DocumentFile, outputWritten: Boolean) {
        if (outputWritten || output.length() == 0L) {
            deletePartialOutput(output)
        }
    }

    /**
     * Returns the input-stream wrapper implementing the chunked cooperative cancellation of the
     * single-file flow (see [CancellationChunking]): sources above the threshold are read through
     * a [ChunkedCancellationInputStream] that calls [checkCancelled] between chunks; smaller
     * sources pass through unwrapped and stay uninterruptible for their (short) duration. A
     * source whose provider reports no size (`length() == 0`) is wrapped too: unknown is not
     * "small", and the wrapper costs only a counter per read.
     */
    private fun singleFileCancellationWrapper(
        sourceSizeBytes: Long,
        checkCancelled: () -> Unit,
    ): (InputStream) -> InputStream =
        if (sourceSizeBytes <= 0L || sourceSizeBytes > cancellationChunking.thresholdBytes) {
            { stream -> ChunkedCancellationInputStream(stream, cancellationChunking.chunkSizeBytes, checkCancelled) }
        } else {
            { stream -> stream }
        }

    private fun withStreams(
        source: DocumentFile,
        output: DocumentFile,
        onOutputOpened: () -> Unit = {},
        wrapInput: (InputStream) -> InputStream = { it },
        block: (InputStream, OutputStream) -> Boolean,
    ): Boolean =
        try {
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                context.contentResolver.openOutputStream(output.uri, OUTPUT_STREAM_MODE)
                    ?.also { onOutputOpened() }
                    ?.use { out -> block(wrapInput(input), out) }
                    ?: false
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
     * Verifies [password] against a handful of the folder's encrypted files before anything is
     * written, so a wrong password fails while the "no files were modified" promise still holds.
     *
     * Probes up to [PasswordProbe.MAX_PROBES] files, smallest first — a GCM decrypt has to read to
     * the tag at the end of the file, so probing the smallest keeps this from decrypting a
     * multi-GB video just to check a password (BUG-6). Probing *several* rather than the single
     * smallest is what keeps one damaged file (a zero-byte leftover from an interrupted upload, an
     * incomplete download) from failing the whole restore as "wrong password": the run is refused
     * only when every usable probe failed its tag check. See [PasswordProbe.shouldProceed] for the
     * full decision table. Returns `true` when there is nothing encrypted to verify.
     *
     * Stopping is honored between probes, via [runControl] for a user stop and [checkCancelled]
     * for a dead host — this phase is not free: every probe decrypts a whole file to reach its GCM
     * tag, and the first one also pays a ~0.5–2 s PBKDF2 derivation, so on a folder of large files
     * it is the minute-plus the UI labels "Verifying password…". Without these checks the Cancel
     * button did nothing at all until it was over. The caller distinguishes "stopped" from
     * "wrong password" by asking [runControl] again, since a half-finished probe set must never be
     * read as a verdict.
     */
    private fun verifyProbePassword(
        files: List<SourceFileEntry>,
        password: String,
        cache: MutableMap<String, SecretKey>,
        runControl: RestoreRunControl,
        checkCancelled: () -> Unit,
    ): Boolean {
        val encrypted = files.filter { it.relativePath.endsWith(CRYPT_SUFFIX) }
        val probes = PasswordProbe.selectCandidates(encrypted) { it.documentFile.length() }
        val outcomes = mutableListOf<ProbeOutcome>()
        for (probe in probes) {
            // A proven-correct password makes the remaining probes pointless work, and a stop
            // request makes all of them pointless.
            val done = outcomes.lastOrNull() == ProbeOutcome.CORRECT_PASSWORD || runControl.shouldStop()
            if (done) break
            checkCancelled()
            outcomes.add(probeOutcome(probe, password, cache))
        }
        return PasswordProbe.shouldProceed(outcomes)
    }

    /**
     * Decrypts one probe to nowhere and classifies what that says about the password. A file whose
     * header or stream cannot be read is [ProbeOutcome.INCONCLUSIVE] rather than a verdict: it is
     * a broken file, and only the GCM tag check speaks about the key.
     */
    private fun probeOutcome(
        entry: SourceFileEntry,
        password: String,
        cache: MutableMap<String, SecretKey>,
    ): ProbeOutcome {
        val result = try {
            context.contentResolver.openInputStream(entry.documentFile.uri)?.use { input ->
                cipher.decryptFile(input, NullOutputStream) { header -> cachedKey(header, password, cache) }
            }
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.warning(
                "Failed to open probe ${FileNameRedactor.redact(entry.documentFile.name.orEmpty())}",
                e,
            )
            null
        }
        return when (result?.getErrorOrNull()) {
            null -> if (result == null) ProbeOutcome.INCONCLUSIVE else ProbeOutcome.CORRECT_PASSWORD
            DecryptionError.INVALID_PASSWORD -> ProbeOutcome.WRONG_PASSWORD
            else -> ProbeOutcome.INCONCLUSIVE
        }
    }

    /**
     * Returns the AES key for a file's FVC1 [header], deriving it on first use and caching it by
     * `salt + iterations` so every file sharing those parameters reuses one expensive derivation
     * (BUG-6).
     */
    private fun cachedKey(
        header: Fvc1Header,
        password: String,
        cache: MutableMap<String, SecretKey>,
    ): SecretKey {
        val cacheKey = "${Base64.getEncoder().encodeToString(header.salt)}:${header.iterations}"
        return cache.getOrPut(cacheKey) { cipher.deriveKey(password, header.salt, header.iterations) }
    }

    /**
     * Reads the source's FVC1 header and classifies what happened, so the single-file flow can
     * tell a file it *cannot open* apart from one that simply is not encrypted.
     *
     * The distinction matters because the two deserve different messages and different moments.
     * `DocumentFile.fromSingleUri` never returns null above API 19, so the null check that was
     * meant to catch an inaccessible source was unreachable; a genuinely unopenable source — a
     * grant the user revoked, a cloud provider that is offline — instead failed later, at
     * stream-open time inside the copy or decrypt, and was reported as "Failed to copy the file"
     * or "Failed to read or decrypt the file". Classifying here reports "Cannot access the
     * selected file" instead, and does it *before* the output document is opened and truncated.
     *
     * Only a failure to *open* counts as [HeaderProbe.Unreadable]. A stream that opens but holds
     * no valid header is [HeaderProbe.NotEncrypted] — that is an ordinary plain file (or a damaged
     * one), and the caller's `.crypt` suffix check decides which.
     */
    private fun probeSourceHeader(file: DocumentFile): HeaderProbe {
        val stream = try {
            context.contentResolver.openInputStream(file.uri)
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.warning("Cannot open ${FileNameRedactor.redact(file.name.orEmpty())} for reading", e)
            null
        }
        return stream?.use { input ->
            try {
                HeaderProbe.Parsed(Fvc1Header.readFrom(input))
            } catch (e: Exception) {
                e.rethrowCancellation()
                // The exception message is uncontrolled and may embed the content URI (file name
                // included), so it goes through redactPathsIn before reaching the breadcrumb sinks.
                logger.info(
                    "No parseable FVC1 header in ${FileNameRedactor.redact(file.name.orEmpty())}: " +
                        FileNameRedactor.redactPathsIn(e.message.orEmpty()),
                )
                HeaderProbe.NotEncrypted
            }
        } ?: HeaderProbe.Unreadable
    }

    /**
     * Resolves the concrete output [DocumentFile] for a source entry, applying [policy] on
     * collision. Distinguishes three outcomes so the caller can count them correctly (BUG-7):
     * [OutputResolution.Skip] (a deliberate SKIP), [OutputResolution.Failed] (an operation that
     * should have worked but didn't — directory or file creation failed, or an OVERWRITE delete
     * was rejected), and [OutputResolution.Resolved].
     */
    private fun resolveOutputFile(
        outputTree: OutputTreeCache,
        relPath: String,
        policy: RestoreCollisionPolicy,
    ): OutputResolution {
        val parts = relPath.split("/")
        val fileName = parts.last()

        val dir = outputTree.resolveDirectory(parts.dropLast(1)) ?: return OutputResolution.Failed

        val existing = outputTree.findChild(dir, fileName)
        return when {
            existing == null -> createOutput(outputTree, dir, fileName)
            policy == RestoreCollisionPolicy.SKIP -> OutputResolution.Skip
            policy == RestoreCollisionPolicy.OVERWRITE -> {
                // Name read before the delete — afterwards the provider reports none.
                val existingName = outputTree.nameOf(existing)
                if (existing.delete()) {
                    outputTree.forgetChild(dir, existingName)
                    createOutput(outputTree, dir, fileName)
                } else {
                    OutputResolution.Failed
                }
            }
            else -> resolveWithSuffix(outputTree, dir, fileName)
        }
    }

    private fun createOutput(outputTree: OutputTreeCache, dir: DocumentFile, name: String): OutputResolution =
        outputTree.createChild(dir, MIME_OCTET_STREAM, name)
            ?.let { OutputResolution.Resolved(dir, it) }
            ?: OutputResolution.Failed

    /**
     * For [RestoreCollisionPolicy.RENAME_WITH_SUFFIX], loops `_restored`, `_restored_2`, … until a
     * name that does not already exist is found, so a repeated restore does not silently collide
     * with a previously restored copy (BUG-7).
     */
    private fun resolveWithSuffix(
        outputTree: OutputTreeCache,
        dir: DocumentFile,
        fileName: String,
    ): OutputResolution {
        var index = 1
        var candidate = RestorePathResolver.indexedRestoreName(fileName, index)
        while (outputTree.findChild(dir, candidate) != null) {
            index++
            candidate = RestorePathResolver.indexedRestoreName(fileName, index)
        }
        return createOutput(outputTree, dir, candidate)
    }

    /**
     * Decrypts one entry of a folder restore. The key comes from the file's own header via
     * [cachedKey], resolved inside the single stream open rather than from a separate
     * header-reading pass — that pass used to double the SAF stream opens of every restore.
     */
    private fun decryptEntry(
        source: DocumentFile,
        output: DocumentFile,
        password: String,
        cache: MutableMap<String, SecretKey>,
    ): Boolean =
        decryptEntryDetailed(source, output) { header -> cachedKey(header, password, cache) } is SuccessResult

    /**
     * Decrypts [source] into [output], reporting *why* a failure happened — unlike the Boolean
     * [decryptEntry], which is enough for [decryptAll]'s per-file `failed` counter. The single-file
     * flow needs the distinction so only a GCM tag failure ([DecryptionError.INVALID_PASSWORD])
     * is presented as a wrong password. Stream-open failures map to [DecryptionError.UNKNOWN].
     * [onOutputOpened] fires as soon as the output stream is opened (i.e. its previous content is
     * gone), so the single-file flow knows whether failure cleanup may delete the document.
     */
    private fun decryptEntryDetailed(
        source: DocumentFile,
        output: DocumentFile,
        onOutputOpened: () -> Unit = {},
        wrapInput: (InputStream) -> InputStream = { it },
        keyProvider: (Fvc1Header) -> SecretKey,
    ): BinaryResult<Unit, DecryptionError> =
        try {
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                context.contentResolver.openOutputStream(output.uri, OUTPUT_STREAM_MODE)
                    ?.also { onOutputOpened() }
                    ?.use { out -> cipher.decryptFile(wrapInput(input), out, keyProvider) }
            } ?: ErrorResult(DecryptionError.UNKNOWN)
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.warning(
                "Failed to open streams while restoring ${FileNameRedactor.redact(source.name.orEmpty())}",
                e,
            )
            ErrorResult(DecryptionError.UNKNOWN)
        }

    private fun copyEntry(
        source: DocumentFile,
        output: DocumentFile,
        onOutputOpened: () -> Unit = {},
        wrapInput: (InputStream) -> InputStream = { it },
    ): Boolean =
        withStreams(source, output, onOutputOpened, wrapInput) { input, out ->
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
