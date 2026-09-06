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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Robolectric coverage for [RestoreEngine.decryptSingleFile], which restores into the destination
 * *document* the system "Save as" file picker returned — the picker owns the name, the location and
 * the overwrite confirmation, so the engine only writes into what it is handed. Focused on the
 * two-stage decrypt-vs-copy decision (review finding S2) and on which outcomes may delete the
 * output document. A [FakeSafProvider] backed by real temp files stands in for the SAF document
 * provider, so display names, streams and the post-failure output delete all go through the real
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
        FakeSafProvider.documents.clear()
        FakeSafProvider.onOpen = null
        FakeSafProvider.deleteCallCount = 0
        FakeSafProvider.openModes.clear()
        FakeSafProvider.idSequence = 0
        Robolectric.setupContentProvider(FakeSafProvider::class.java, AUTHORITY)
    }

    @Test
    fun `a file with a parseable header is decrypted even when its name lacks the crypt suffix`() = runTest {
        // The S2 regression case: a renamed encrypted file must not be copied as ciphertext.
        val source = addSource("report.pdf", encryptedBlob(PASSWORD))
        val output = addOutput()

        val result = engine.decryptSingleFile(source.toString(), output.toString(), PASSWORD)

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        assertContentEquals(plaintext, outputBytes())
    }

    @Test
    fun `a file with a parseable header is decrypted even when the provider reports no name`() = runTest {
        val source = addSource(displayName = null, content = encryptedBlob(PASSWORD))
        val output = addOutput()

        val result = engine.decryptSingleFile(source.toString(), output.toString(), PASSWORD)

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        assertContentEquals(plaintext, outputBytes())
    }

    @Test
    fun `a crypt-suffixed file with a parseable header is decrypted`() = runTest {
        val source = addSource("report.pdf.crypt", encryptedBlob(PASSWORD))
        val output = addOutput()

        val result = engine.decryptSingleFile(source.toString(), output.toString(), PASSWORD)

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        assertContentEquals(plaintext, outputBytes())
    }

    @Test
    fun `a plain file without the crypt suffix is copied verbatim`() = runTest {
        val plainBytes = "just some plain text".toByteArray()
        val source = addSource("notes.txt", plainBytes)
        val output = addOutput()

        val result = engine.decryptSingleFile(source.toString(), output.toString(), PASSWORD)

        assertEquals(RestoreResult.Success(decrypted = 0, copied = 1, skipped = 0, failed = 0), result)
        assertContentEquals(plainBytes, outputBytes())
    }

    @Test
    fun `picking an existing file replaces its content instead of appending to it`() = runTest {
        // The picker hands back an existing document when the user confirms overwriting it. The
        // accepted trade-off of the file-picker flow is that its content is replaced — but it must
        // be replaced *completely*, never leaving a tail of the (longer) previous content behind.
        val source = addSource("report.pdf.crypt", encryptedBlob(PASSWORD))
        val output = addOutput("a much, much longer piece of pre-existing content".repeat(10).toByteArray())

        val result = engine.decryptSingleFile(source.toString(), output.toString(), PASSWORD)

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        assertContentEquals(plaintext, outputBytes())
    }

    @Test
    fun `a crypt-suffixed file with an unparseable header fails clearly and deletes the output`() = runTest {
        // The filename claims "encrypted", the content does not parse: copying the bytes verbatim
        // would hand the user ciphertext as a "successful" restore, so this must fail instead.
        val source = addSource("broken.txt.crypt", "definitely not an FVC1 file".toByteArray())
        val output = addOutput()

        val result = engine.decryptSingleFile(source.toString(), output.toString(), PASSWORD)

        assertIs<RestoreResult.Failure>(result)
        assertEquals(RestoreFailureReason.FILE_HEADER_NOT_READABLE, result.reason)
        assertFalse(outputExists(), "the freshly created output document must be deleted on failure")
    }

    @Test
    fun `a failure before the output is written leaves a pre-existing picked file intact`() = runTest {
        // Cleanup deletes the output only when the restore already destroyed its content (it opened
        // the truncating stream) or when the picker created it empty. A restore that fails before
        // ever opening the output must not delete a file the user still has.
        val existingContent = "precious pre-existing content".toByteArray()
        val source = addSource("broken.txt.crypt", "definitely not an FVC1 file".toByteArray())
        val output = addOutput(existingContent)

        val result = engine.decryptSingleFile(source.toString(), output.toString(), PASSWORD)

        assertIs<RestoreResult.Failure>(result)
        assertTrue(outputExists(), "an untouched pre-existing output must survive the failure")
        assertContentEquals(existingContent, outputBytes(), "its content must be unchanged")
    }

    @Test
    fun `a wrong password is reported as InvalidPassword and the output is deleted`() = runTest {
        val source = addSource("report.pdf.crypt", encryptedBlob(PASSWORD))
        val output = addOutput()

        val result = engine.decryptSingleFile(source.toString(), output.toString(), "wrong-password")

        assertEquals(RestoreResult.InvalidPassword, result)
        assertFalse(outputExists(), "the freshly created output document must be deleted on failure")
    }

    @Test
    fun `a cancelled restore cleans up the created output`() = runTest {
        // A StandardTestDispatcher makes withContext(io) a real dispatch, so the UNDISPATCHED
        // launch suspends right at the engine's withContext entry — cancelling there exercises
        // the CancellationException cleanup path (review R1).
        val queuedEngine = RestoreEngine(context, cipher, dispatchersOf(StandardTestDispatcher(testScheduler)))
        val source = addSource("report.pdf.crypt", encryptedBlob(PASSWORD))
        val output = addOutput()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            queuedEngine.decryptSingleFile(source.toString(), output.toString(), PASSWORD)
        }
        job.cancel()
        job.join()

        assertFalse(outputExists(), "the created output document must be deleted on cancellation")
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
        val source = addSource("report.pdf.crypt", encryptedBlob(PASSWORD))
        val output = addOutput()

        lateinit var job: Job
        FakeSafProvider.onOpen = { name -> if (name == OUTPUT_NAME) job.cancel() }
        job = launch {
            chunkedEngine.decryptSingleFile(source.toString(), output.toString(), PASSWORD)
        }
        job.join()

        assertTrue(job.isCancelled, "the restore coroutine must end cancelled, not complete normally")
        assertFalse(recordingCipher.decryptFileReturned, "the decrypt must be aborted mid-file, not run to the end")
        assertFalse(outputExists(), "the output must be deleted when the decrypt is aborted mid-file")
    }

    @Test
    fun `a cancel arriving after an in-block failure cleans the output up only once`() = runTest {
        // Cancelling while the block runs (from the source document's openFile) lets the block
        // finish with a failure result and clean up in-block; the CancellationException thrown at
        // the withContext exit must then NOT clean up a second time (review N1) — the redundant
        // delete of the already-removed document would only log a spurious warning.
        val source = addSource("broken.txt.crypt", "definitely not an FVC1 file".toByteArray())
        val output = addOutput()

        lateinit var job: Job
        FakeSafProvider.onOpen = { name -> if (name == "broken.txt.crypt") job.cancel() }
        job = launch {
            engine.decryptSingleFile(source.toString(), output.toString(), PASSWORD)
        }
        job.join()

        assertTrue(job.isCancelled, "the restore coroutine must end cancelled")
        assertFalse(outputExists(), "the created output document must still be deleted")
        assertEquals(1, FakeSafProvider.deleteCallCount, "cleanup must not run a second time")
    }

    @Test
    fun `a source with unknown size is still cancellable mid-file`() = runTest {
        // A provider that reports no size must not bypass the chunked cancellation: unknown is
        // not "small" (review S1 of review/develop.md). Same setup as the chunked-cancel test,
        // but the source's size column reports 0 despite the real content.
        val recordingCipher = RecordingCipher(cipher)
        val chunkedEngine = RestoreEngine(context, recordingCipher, dispatchers, TEST_CHUNKING)
        val source = addSource("report.pdf.crypt", encryptedBlob(PASSWORD), reportedSize = 0L)
        val output = addOutput()

        lateinit var job: Job
        FakeSafProvider.onOpen = { name -> if (name == OUTPUT_NAME) job.cancel() }
        job = launch {
            chunkedEngine.decryptSingleFile(source.toString(), output.toString(), PASSWORD)
        }
        job.join()

        assertTrue(job.isCancelled, "the restore coroutine must end cancelled, not complete normally")
        assertFalse(recordingCipher.decryptFileReturned, "the decrypt must abort mid-file despite the unknown size")
        assertFalse(outputExists(), "the output must be deleted when the decrypt is aborted mid-file")
    }

    @Test
    fun `the output stream is opened with an explicit truncate mode`() = runTest {
        // The default "w" mode leaves truncation provider-dependent (Google Drive does not
        // truncate) (review B1 of review/develop.md). The engine must request "wt" explicitly.
        val source = addSource("report.pdf.crypt", encryptedBlob(PASSWORD))
        val output = addOutput()

        val result = engine.decryptSingleFile(source.toString(), output.toString(), PASSWORD)

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        assertEquals("wt", FakeSafProvider.openModes[OUTPUT_NAME], "the output must be opened truncating")
        assertContentEquals(plaintext, outputBytes())
    }

    @Test
    fun `a chunked decrypt without a cancel succeeds and produces the exact plaintext`() = runTest {
        // Guards the chunk-check stream wrapper itself: crossing several chunk boundaries must not
        // corrupt or truncate what the cipher reads.
        val chunkedEngine = RestoreEngine(context, cipher, dispatchers, TEST_CHUNKING)
        val source = addSource("report.pdf.crypt", encryptedBlob(PASSWORD))
        val output = addOutput()

        val result = chunkedEngine.decryptSingleFile(source.toString(), output.toString(), PASSWORD)

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        assertContentEquals(plaintext, outputBytes())
    }

    /** Registers a picked *source* document (single-document uri), backed by a real temp file. */
    private fun addSource(displayName: String?, content: ByteArray, reportedSize: Long? = null): Uri {
        val id = "src-${FakeSafProvider.idSequence++}"
        val file = File.createTempFile("restore-single-file-test", null, context.cacheDir)
        file.writeBytes(content)
        FakeSafProvider.documents[id] =
            TestDocument(id, displayName = displayName, file = file, reportedSize = reportedSize)
        return DocumentsContract.buildDocumentUri(AUTHORITY, id)
    }

    /**
     * Registers the destination document the "Save as" picker returns, backed by a real temp file.
     * Empty by default (what the picker creates for a fresh name); pass [content] to model the
     * document of an existing file the user confirmed overwriting.
     */
    private fun addOutput(content: ByteArray = ByteArray(0)): Uri {
        val file = File.createTempFile("restore-single-file-output", null, context.cacheDir)
        file.writeBytes(content)
        FakeSafProvider.documents[OUTPUT_ID] = TestDocument(OUTPUT_ID, displayName = OUTPUT_NAME, file = file)
        return DocumentsContract.buildDocumentUri(AUTHORITY, OUTPUT_ID)
    }

    private fun outputExists(): Boolean = FakeSafProvider.documents[OUTPUT_ID]?.file?.exists() == true

    private fun outputBytes(): ByteArray {
        val document = FakeSafProvider.documents[OUTPUT_ID]
        assertNotNull(document, "expected the output document to still exist")
        return document.file!!.readBytes()
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
        const val OUTPUT_ID = "picked-output"
        const val OUTPUT_NAME = "report.pdf"
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

private data class TestDocument(
    val id: String,
    val displayName: String?,
    val file: File?,
    /** Overrides the size column when set, decoupling the reported size from the real content. */
    val reportedSize: Long? = null,
)

/**
 * Minimal SAF stand-in answering what `DocumentFile.fromSingleUri` needs: display-name/size
 * queries, `openFile` for streams (backed by real temp files) and the `deleteDocument` call
 * `DocumentFile.delete()` issues. Contents are injected via [documents] before
 * [Robolectric.setupContentProvider].
 */
private class FakeSafProvider : ContentProvider() {
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
        documents[DocumentsContract.getDocumentId(uri)]?.let { addRow(cursor, columns, it) }
        return cursor
    }

    private fun addRow(cursor: MatrixCursor, columns: Array<out String>, document: TestDocument) {
        val row = columns.map { column ->
            when (column) {
                Document.COLUMN_DOCUMENT_ID -> document.id
                Document.COLUMN_DISPLAY_NAME -> document.displayName
                Document.COLUMN_MIME_TYPE -> "application/octet-stream"
                Document.COLUMN_SIZE -> document.reportedSize ?: document.file?.length() ?: 0L
                Document.COLUMN_LAST_MODIFIED -> 0L
                Document.COLUMN_FLAGS -> 0
                else -> null
            }
        }
        cursor.addRow(row)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val documentId = DocumentsContract.getDocumentId(uri)
        val document = documents[documentId] ?: throw FileNotFoundException("No document for $uri")
        val file = document.file ?: throw FileNotFoundException("No file for $uri")
        openModes[document.displayName ?: documentId] = mode
        onOpen?.invoke(document.displayName)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        when (method) {
            METHOD_DELETE_DOCUMENT -> deleteDocument(extras)
            else -> super.call(method, arg, extras)
        }

    private fun deleteDocument(extras: Bundle?): Bundle {
        @Suppress("DEPRECATION")
        val uri = extras?.getParcelable<Uri>(EXTRA_URI) ?: error("deleteDocument without uri")
        val documentId = DocumentsContract.getDocumentId(uri)
        deleteCallCount++
        documents.remove(documentId)?.file?.delete()
        return Bundle.EMPTY
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

        /** Test hook invoked with the display name whenever a document is opened for streaming. */
        var onOpen: ((String?) -> Unit)? = null

        /** The last mode string each document was opened with, keyed by display name. */
        val openModes: MutableMap<String, String> = mutableMapOf()

        /** Number of deleteDocument provider calls, to detect redundant double cleanups. */
        var deleteCallCount = 0

        /** Monotonic counter for generated document ids. */
        var idSequence = 0

        /** Hidden `DocumentsContract` method / extra values. */
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
