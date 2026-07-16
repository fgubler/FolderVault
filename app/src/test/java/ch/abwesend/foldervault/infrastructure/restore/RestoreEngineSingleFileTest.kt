package ch.abwesend.foldervault.infrastructure.restore

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.platform.app.InstrumentationRegistry
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.DecryptionError
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.restore.RestoreFailureReason
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.infrastructure.crypto.Fvc1Cipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Robolectric coverage for [RestoreEngine.decryptSingleFile], focused on the two-stage
 * decrypt-vs-copy decision (review finding S2): a parseable FVC1 header wins over the display
 * name, and only when the header does not parse does the `.crypt` suffix decide. A
 * [FakeSingleFileProvider] backed by real temp files stands in for the SAF document provider, so
 * display names, streams and the post-failure output delete all go through the real
 * `DocumentFile` plumbing. Encrypted fixtures are hand-assembled version-1 FVC1 blobs with a low
 * PBKDF2 iteration count to keep key derivation fast (mirrors `Fvc1CipherTest.buildBlob`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestoreEngineSingleFileTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cipher = Fvc1Cipher()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = dispatchersOf(testDispatcher)

    private fun dispatchersOf(dispatcher: CoroutineDispatcher): IDispatchers = object : IDispatchers {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
        override val mainImmediate = dispatcher
    }

    private val engine = RestoreEngine(context, cipher, dispatchers)
    private val plaintext = "FolderVault single-file restore test payload".toByteArray()

    @Before
    fun setUp() {
        FakeSingleFileProvider.documents.clear()
        FakeSingleFileProvider.onOpen = null
        FakeSingleFileProvider.deleteCallCount = 0
        Robolectric.setupContentProvider(FakeSingleFileProvider::class.java, AUTHORITY)
    }

    @Test
    fun `a file with a parseable header is decrypted even when its name lacks the crypt suffix`() = runTest {
        // The S2 regression case: a renamed encrypted file must not be copied as ciphertext.
        val source = addDocument("src", "report.pdf", encryptedBlob(PASSWORD))
        val output = addDocument("out", "report.pdf", ByteArray(0))

        val result = engine.decryptSingleFile(source.uri.toString(), output.uri.toString(), PASSWORD)

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        assertContentEquals(plaintext, output.file.readBytes())
    }

    @Test
    fun `a file with a parseable header is decrypted even when the provider reports no name`() = runTest {
        val source = addDocument("src", displayName = null, content = encryptedBlob(PASSWORD))
        val output = addDocument("out", "report.pdf", ByteArray(0))

        val result = engine.decryptSingleFile(source.uri.toString(), output.uri.toString(), PASSWORD)

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        assertContentEquals(plaintext, output.file.readBytes())
    }

    @Test
    fun `a crypt-suffixed file with a parseable header is decrypted`() = runTest {
        val source = addDocument("src", "report.pdf.crypt", encryptedBlob(PASSWORD))
        val output = addDocument("out", "report.pdf", ByteArray(0))

        val result = engine.decryptSingleFile(source.uri.toString(), output.uri.toString(), PASSWORD)

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        assertContentEquals(plaintext, output.file.readBytes())
    }

    @Test
    fun `a plain file without the crypt suffix is copied verbatim`() = runTest {
        val plainBytes = "just some plain text".toByteArray()
        val source = addDocument("src", "notes.txt", plainBytes)
        val output = addDocument("out", "notes.txt", ByteArray(0))

        val result = engine.decryptSingleFile(source.uri.toString(), output.uri.toString(), PASSWORD)

        assertEquals(RestoreResult.Success(decrypted = 0, copied = 1, skipped = 0, failed = 0), result)
        assertContentEquals(plainBytes, output.file.readBytes())
    }

    @Test
    fun `a crypt-suffixed file with an unparseable header fails clearly and deletes the output`() = runTest {
        // The filename claims "encrypted", the content does not parse: copying the bytes verbatim
        // would hand the user ciphertext as a "successful" restore, so this must fail instead.
        val source = addDocument("src", "broken.txt.crypt", "definitely not an FVC1 file".toByteArray())
        val output = addDocument("out", "broken.txt", ByteArray(0))

        val result = engine.decryptSingleFile(source.uri.toString(), output.uri.toString(), PASSWORD)

        assertIs<RestoreResult.Failure>(result)
        assertEquals(RestoreFailureReason.FILE_HEADER_NOT_READABLE, result.reason)
        assertFalse(output.file.exists(), "the pre-created output document must be deleted on failure")
    }

    @Test
    fun `a wrong password is reported as InvalidPassword and the output is deleted`() = runTest {
        val source = addDocument("src", "report.pdf.crypt", encryptedBlob(PASSWORD))
        val output = addDocument("out", "report.pdf", ByteArray(0))

        val result = engine.decryptSingleFile(source.uri.toString(), output.uri.toString(), "wrong-password")

        assertEquals(RestoreResult.InvalidPassword, result)
        assertFalse(output.file.exists(), "the pre-created output document must be deleted on failure")
    }

    @Test
    fun `a failure before any write keeps a pre-existing non-empty output`() = runTest {
        // "Save as" may return an EXISTING document the user chose to overwrite (review R2).
        // When the restore fails without ever writing, deleting it would destroy that data.
        val existingContent = "precious pre-existing content".toByteArray()
        val source = addDocument("src", "broken.txt.crypt", "definitely not an FVC1 file".toByteArray())
        val output = addDocument("out", "broken.txt", existingContent)

        val result = engine.decryptSingleFile(source.uri.toString(), output.uri.toString(), PASSWORD)

        assertIs<RestoreResult.Failure>(result)
        assertContentEquals(existingContent, output.file.readBytes(), "the untouched document must survive")
    }

    @Test
    fun `a failed copy keeps a pre-existing non-empty output that was never written`() = runTest {
        val existingContent = "precious pre-existing content".toByteArray()
        val source = addDocument("src", "notes.txt", "plain".toByteArray())
        source.file.delete() // makes opening the source stream fail before the output is touched
        val output = addDocument("out", "notes.txt", existingContent)

        val result = engine.decryptSingleFile(source.uri.toString(), output.uri.toString(), PASSWORD)

        assertIs<RestoreResult.Failure>(result)
        assertContentEquals(existingContent, output.file.readBytes(), "the untouched document must survive")
    }

    @Test
    fun `a wrong password deletes even a pre-existing output because it was already overwritten`() = runTest {
        val source = addDocument("src", "report.pdf.crypt", encryptedBlob(PASSWORD))
        val output = addDocument("out", "report.pdf", "old content, truncated by the write".toByteArray())

        val result = engine.decryptSingleFile(source.uri.toString(), output.uri.toString(), "wrong-password")

        assertEquals(RestoreResult.InvalidPassword, result)
        assertFalse(output.file.exists(), "a written-to document holds only garbage and must be deleted")
    }

    @Test
    fun `a cancelled restore cleans up the pre-created empty output`() = runTest {
        // A StandardTestDispatcher makes withContext(io) a real dispatch, so the UNDISPATCHED
        // launch suspends right at the engine's withContext entry — cancelling there exercises
        // the CancellationException cleanup path (review R1).
        val queuedEngine = RestoreEngine(context, cipher, dispatchersOf(StandardTestDispatcher(testScheduler)))
        val source = addDocument("src", "report.pdf.crypt", encryptedBlob(PASSWORD))
        val output = addDocument("out", "report.pdf", ByteArray(0))

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            queuedEngine.decryptSingleFile(source.uri.toString(), output.uri.toString(), PASSWORD)
        }
        job.cancel()
        job.join()

        assertFalse(output.file.exists(), "the pre-created output document must be deleted on cancellation")
    }

    @Test
    fun `a cancel during a chunked decrypt aborts between chunks and deletes the output`() = runTest {
        // Chunking sized so the ~100-byte fixture crosses several chunk boundaries. The job is
        // cancelled from the provider's openFile for the OUTPUT document — i.e. after the decrypt
        // has started but before its stream loop runs — so only the between-chunk liveness check
        // (review S1) can abort the run mid-file; without it the whole decrypt would run to
        // completion despite the cancel, which is what the recording cipher asserts against.
        val recordingCipher = RecordingCipher(cipher)
        val chunkedEngine = RestoreEngine(context, recordingCipher, dispatchers, TEST_CHUNKING)
        val source = addDocument("src", "report.pdf.crypt", encryptedBlob(PASSWORD))
        val output = addDocument("out", "report.pdf", ByteArray(0))

        lateinit var job: Job
        FakeSingleFileProvider.onOpen = { documentId -> if (documentId == "out") job.cancel() }
        job = launch {
            chunkedEngine.decryptSingleFile(source.uri.toString(), output.uri.toString(), PASSWORD)
        }
        job.join()

        assertTrue(job.isCancelled, "the restore coroutine must end cancelled, not complete normally")
        assertFalse(recordingCipher.decryptFileReturned, "the decrypt must be aborted mid-file, not run to the end")
        assertFalse(output.file.exists(), "the output must be deleted when the decrypt is aborted mid-file")
    }

    @Test
    fun `a cancel arriving after an in-block failure cleans the output up only once`() = runTest {
        // Cancelling while the block runs (from the source document's openFile) lets the block
        // finish with a failure result and clean up in-block; the CancellationException thrown at
        // the withContext exit must then NOT clean up a second time (review N1) — the redundant
        // delete of the already-removed document would only log a spurious warning.
        val source = addDocument("src", "broken.txt.crypt", "definitely not an FVC1 file".toByteArray())
        val output = addDocument("out", "broken.txt", ByteArray(0))

        lateinit var job: Job
        FakeSingleFileProvider.onOpen = { documentId -> if (documentId == "src") job.cancel() }
        job = launch {
            engine.decryptSingleFile(source.uri.toString(), output.uri.toString(), PASSWORD)
        }
        job.join()

        assertTrue(job.isCancelled, "the restore coroutine must end cancelled")
        assertFalse(output.file.exists(), "the pre-created output document must still be deleted")
        assertEquals(1, FakeSingleFileProvider.deleteCallCount, "cleanup must not run a second time")
    }

    @Test
    fun `a chunked decrypt without a cancel succeeds and produces the exact plaintext`() = runTest {
        // Guards the chunk-check stream wrapper itself: crossing several chunk boundaries must not
        // corrupt or truncate what the cipher reads.
        val chunkedEngine = RestoreEngine(context, cipher, dispatchers, TEST_CHUNKING)
        val source = addDocument("src", "report.pdf.crypt", encryptedBlob(PASSWORD))
        val output = addDocument("out", "report.pdf", ByteArray(0))

        val result = chunkedEngine.decryptSingleFile(source.uri.toString(), output.uri.toString(), PASSWORD)

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        assertContentEquals(plaintext, output.file.readBytes())
    }

    /**
     * Registers a document with the fake provider, backed by a real temp file holding [content].
     * A `null` [displayName] simulates a SAF provider that reports no name for the document.
     */
    private fun addDocument(id: String, displayName: String?, content: ByteArray): TestDocument {
        val file = File.createTempFile("restore-single-file-test", null, context.cacheDir)
        file.writeBytes(content)
        val document = TestDocument(displayName, file, DocumentsContract.buildDocumentUri(AUTHORITY, id))
        FakeSingleFileProvider.documents[id] = document
        return document
    }

    /**
     * Assembles a genuinely decryptable version-1 FVC1 blob (no AAD binding, so the body can be
     * produced with a bare AES/GCM cipher) whose header records [TEST_ITERATIONS] — the engine
     * must derive with the header's parameters, and the low count keeps the test fast.
     */
    private fun encryptedBlob(password: String): ByteArray {
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it + 1).toByte() }
        val key = cipher.deriveKey(password, salt, TEST_ITERATIONS)
        val body = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }.doFinal(plaintext)
        return ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                write("FVC1".toByteArray(Charsets.US_ASCII))
                writeByte(1) // version 1
                writeByte(1) // KDF id: PBKDF2-SHA256
                writeInt(TEST_ITERATIONS)
                writeByte(salt.size)
                write(salt)
                writeByte(iv.size)
                write(iv)
                write(body)
                flush()
            }
        }.toByteArray()
    }

    private companion object {
        const val AUTHORITY = "ch.abwesend.foldervault.test.restore.docs"
        const val PASSWORD = "correct-horse-battery-staple"
        const val TEST_ITERATIONS = 1_000

        /** Chunking small enough that the ~100-byte fixtures cross several chunk boundaries. */
        val TEST_CHUNKING = CancellationChunking(thresholdBytes = 16, chunkSizeBytes = 16)
    }
}

/**
 * Delegates to the real cipher but records whether [decryptFile] ran to completion — the
 * distinguishing observable for the chunked-cancellation test, where the between-chunk check must
 * abort the decrypt by throwing out of it (all other cancel outcomes look identical from outside).
 */
private class RecordingCipher(private val delegate: IFvc1Cipher) : IFvc1Cipher by delegate {
    var decryptFileReturned = false

    override fun decryptFile(
        key: SecretKey,
        input: InputStream,
        output: OutputStream,
    ): BinaryResult<Unit, DecryptionError> =
        delegate.decryptFile(key, input, output).also { decryptFileReturned = true }
}

private data class TestDocument(val displayName: String?, val file: File, val uri: Uri)

/**
 * Minimal SAF stand-in answering exactly what `DocumentFile.fromSingleUri` needs: the display-name
 * query, `openFile` for streams (backed by a real temp file), and the `deleteDocument` provider
 * call that `DocumentFile.delete()` issues. Contents are injected via [documents] before
 * [Robolectric.setupContentProvider].
 */
private class FakeSingleFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: DEFAULT_COLUMNS
        val cursor = MatrixCursor(columns)
        val documentId = DocumentsContract.getDocumentId(uri)
        documents[documentId]?.let { document ->
            val row = columns.map { column ->
                when (column) {
                    Document.COLUMN_DOCUMENT_ID -> documentId
                    Document.COLUMN_DISPLAY_NAME -> document.displayName
                    Document.COLUMN_MIME_TYPE -> "application/octet-stream"
                    Document.COLUMN_SIZE -> document.file.length()
                    Document.COLUMN_LAST_MODIFIED -> 0L
                    Document.COLUMN_FLAGS -> 0
                    else -> null
                }
            }
            cursor.addRow(row)
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val documentId = DocumentsContract.getDocumentId(uri)
        val document = documents[documentId] ?: throw FileNotFoundException("No document for $uri")
        onOpen?.invoke(documentId)
        return ParcelFileDescriptor.open(document.file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        if (method == METHOD_DELETE_DOCUMENT) {
            @Suppress("DEPRECATION")
            val uri = extras?.getParcelable<Uri>(EXTRA_URI) ?: error("deleteDocument without uri")
            val documentId = DocumentsContract.getDocumentId(uri)
            deleteCallCount++
            documents.remove(documentId)?.file?.delete()
            Bundle.EMPTY
        } else {
            super.call(method, arg, extras)
        }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        val documents: MutableMap<String, TestDocument> = mutableMapOf()

        /** Test hook invoked with the document id whenever a document is opened for streaming. */
        var onOpen: ((String) -> Unit)? = null

        /** Number of deleteDocument provider calls, to detect redundant double cleanups. */
        var deleteCallCount = 0

        /** Hidden `DocumentsContract.METHOD_DELETE_DOCUMENT` / `EXTRA_URI` values. */
        private const val METHOD_DELETE_DOCUMENT = "android:deleteDocument"
        private const val EXTRA_URI = "uri"

        private val DEFAULT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
