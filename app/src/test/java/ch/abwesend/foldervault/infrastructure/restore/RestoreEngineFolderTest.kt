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
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreFailureReason
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.domain.restore.RestoreRunControl
import ch.abwesend.foldervault.infrastructure.crypto.Fvc1Cipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Robolectric coverage for [RestoreEngine.decryptAll] — the whole-folder restore, which had none
 * before. Focused on the three behaviours reworked on 2026-09-06: multi-file password probing (a
 * single damaged file must no longer fail the entire run as "wrong password"), the cooperative
 * stop that lets a cancelled run report what it already restored, and the directory-listing cache
 * that replaced the per-file `DocumentFile.findFile`.
 *
 * A [FolderSafProvider] backed by real temp files stands in for the document provider, so tree
 * traversal, child listing, document creation, streams and deletes all go through the real
 * `DocumentFile` plumbing — including a counter for child listings, which is what the O(N²) fix is
 * about. Encrypted fixtures are hand-assembled version-1 FVC1 blobs with a low PBKDF2 iteration
 * count to keep key derivation fast (mirrors `Fvc1CipherTest.buildBlob`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestoreEngineFolderTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cipher = Fvc1Cipher()
    private val dispatcher = UnconfinedTestDispatcher()

    private val dispatchers: IDispatchers = object : IDispatchers {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
        override val mainImmediate = dispatcher
    }

    private val engine = RestoreEngine(context, cipher, dispatchers)
    private val runControl = RestoreRunControl()

    private val sourceTree: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, SOURCE_ROOT)
    private val outputTree: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, OUTPUT_ROOT)

    @Before
    fun setUp() {
        FolderSafProvider.documents.clear()
        FolderSafProvider.childQueryCount = 0
        FolderSafProvider.fileOpenCount = 0
        FolderSafProvider.idSequence = 0
        Robolectric.setupContentProvider(FolderSafProvider::class.java, AUTHORITY)
        addDirectory(SOURCE_ROOT, parentId = null)
        addDirectory(OUTPUT_ROOT, parentId = null)
    }

    @Test
    fun `every encrypted file is decrypted and the crypt suffix is stripped`() = runTest {
        addSourceFile("report.pdf.crypt", encryptedBlob("report body"))
        addSourceFile("notes.txt.crypt", encryptedBlob("notes body"))

        val result = decryptAll()

        assertEquals(RestoreResult.Success(decrypted = 2, copied = 0, skipped = 0, failed = 0), result)
        assertContentEquals("report body".toByteArray(), outputBytes("report.pdf"))
        assertContentEquals("notes body".toByteArray(), outputBytes("notes.txt"))
    }

    @Test
    fun `plain files are copied verbatim alongside the decrypted ones`() = runTest {
        addSourceFile("report.pdf.crypt", encryptedBlob("report body"))
        addSourceFile(".foldervault-manifest.json", """{"files":1}""".toByteArray())

        val result = decryptAll()

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 1, skipped = 0, failed = 0), result)
        assertContentEquals("""{"files":1}""".toByteArray(), outputBytes(".foldervault-manifest.json"))
    }

    @Test
    fun `nested source folders are recreated in the output tree`() = runTest {
        val photos = addDirectory("photos", parentId = SOURCE_ROOT)
        addSourceFile("holiday.jpg.crypt", encryptedBlob("jpeg bytes"), parentId = photos)

        val result = decryptAll()

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        val outputDir = childOf(OUTPUT_ROOT, "photos")
        assertNotNull(outputDir, "the nested directory must be recreated")
        assertContentEquals("jpeg bytes".toByteArray(), outputBytes("holiday.jpg", parentId = outputDir.id))
    }

    @Test
    fun `restoring a folder into itself is refused before any file is touched`() = runTest {
        // Review S17: a plain file keeps its relative path, so it resolves to itself as its own
        // output — and OVERWRITE deletes the existing document before re-creating it, which leaves
        // the copy nothing to read from. The file would simply be gone.
        addSourceFile("keep-me.txt", "precious".toByteArray())
        addSourceFile("report.pdf.crypt", encryptedBlob("report body"))

        val result = engine.decryptAll(
            sourceUri = sourceTree.toString(),
            outputUri = sourceTree.toString(),
            password = PASSWORD,
            collisionPolicy = RestoreCollisionPolicy.OVERWRITE,
            runControl = runControl,
            onProgress = {},
        )

        assertEquals(RestoreResult.Failure(RestoreFailureReason.OUTPUT_FOLDER_SAME_AS_SOURCE), result)
        assertContentEquals(
            "precious".toByteArray(),
            sourceBytes("keep-me.txt"),
            "the plain file must still be there, untouched",
        )
        assertEquals(0, FolderSafProvider.childQueryCount, "nothing may even be listed")
    }

    @Test
    fun `a damaged smallest file no longer fails the whole restore as a wrong password`() = runTest {
        // The regression this rework is about: probing only the smallest .crypt file meant one
        // zero-byte leftover from an interrupted upload refused a perfectly correct password and
        // restored nothing at all.
        addSourceFile("broken.txt.crypt", ByteArray(0))
        addSourceFile("report.pdf.crypt", encryptedBlob("report body"))

        val result = decryptAll()

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 1), result)
        assertContentEquals("report body".toByteArray(), outputBytes("report.pdf"))
    }

    @Test
    fun `a truncated smallest file no longer fails the whole restore either`() = runTest {
        // Same regression with a file that *has* a parseable header but fails its GCM tag — which
        // is indistinguishable from a wrong password when only one file is probed.
        val truncated = encryptedBlob("report body").let { it.copyOf(it.size - 4) }
        addSourceFile("truncated.txt.crypt", truncated)
        addSourceFile("report.pdf.crypt", encryptedBlob("a considerably longer report body"))

        val result = decryptAll()

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 1), result)
        assertContentEquals("a considerably longer report body".toByteArray(), outputBytes("report.pdf"))
    }

    @Test
    fun `a wrong password still fails the run before anything is written`() = runTest {
        // The other half of the probe's job: the "no files were modified" promise must survive.
        addSourceFile("report.pdf.crypt", encryptedBlob("report body"))
        addSourceFile("notes.txt.crypt", encryptedBlob("notes body"))

        val result = decryptAll(password = "wrong-password")

        assertEquals(RestoreResult.InvalidPassword, result)
        assertNull(childOf(OUTPUT_ROOT, "report.pdf"), "a rejected password must write nothing")
        assertNull(childOf(OUTPUT_ROOT, "notes.txt"), "a rejected password must write nothing")
    }

    @Test
    fun `a folder of only damaged files reports failures rather than blaming the password`() = runTest {
        addSourceFile("a.txt.crypt", "not an FVC1 file".toByteArray())
        addSourceFile("b.txt.crypt", "also not an FVC1 file".toByteArray())

        val result = decryptAll()

        assertEquals(RestoreResult.Success(decrypted = 0, copied = 0, skipped = 0, failed = 2), result)
    }

    @Test
    fun `a stopped run reports what it already restored instead of losing the result`() = runTest {
        // Cooperative stop: the engine used to return Cancelled from inside a cancelled coroutine,
        // where withContext discards the value and throws — so the result never reached the user.
        addSourceFile("first.txt.crypt", encryptedBlob("first body"))
        addSourceFile("second.txt.crypt", encryptedBlob("second body"))
        addSourceFile("third.txt.crypt", encryptedBlob("third body"))

        // The stop is requested from the first file's progress callback, i.e. after that
        // iteration's stop check — so exactly one file is restored before the loop breaks.
        val result = decryptAll(onProgress = { runControl.requestStop() })

        assertEquals(RestoreResult.Cancelled(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        assertEquals(1, FolderSafProvider.documents.values.count { it.parentId == OUTPUT_ROOT })
    }

    @Test
    fun `a stop requested before the run starts is honored during password probing`() = runTest {
        // Probing decrypts whole files to reach their GCM tags, so on a folder of large files it
        // is a long, visible "Verifying password…" phase. It used to ignore the stop flag
        // completely, leaving the Cancel button dead until it was over.
        addSourceFile("first.txt.crypt", encryptedBlob("first body"))
        addSourceFile("second.txt.crypt", encryptedBlob("second body"))
        runControl.requestStop()

        val result = decryptAll()

        // Cancelled, NOT InvalidPassword: a probe set cut short must never be read as a verdict
        // on the password.
        assertEquals(RestoreResult.Cancelled(decrypted = 0, copied = 0, skipped = 0, failed = 0), result)
        assertEquals(0, FolderSafProvider.documents.values.count { it.parentId == OUTPUT_ROOT })
        // The real point: not a single stream was opened, so the stop was honored *before* the
        // expensive decrypt-to-nowhere rather than after the whole probe set had run.
        assertEquals(0, FolderSafProvider.fileOpenCount, "an already-stopped run must probe nothing")
    }

    @Test
    fun `a stop during probing is not mistaken for a wrong password`() = runTest {
        // Same guard from the other side: the password here is genuinely wrong, but the user
        // stopped the run, and "Wrong password" would be the more alarming — and less true —
        // of the two things to tell them.
        addSourceFile("first.txt.crypt", encryptedBlob("first body"))
        runControl.requestStop()

        val result = decryptAll(password = "wrong-password")

        assertEquals(RestoreResult.Cancelled(decrypted = 0, copied = 0, skipped = 0, failed = 0), result)
    }

    @Test
    fun `a cancelled run stops at the next file instead of restoring the rest`() = runTest {
        // Nothing in decryptAll's loop suspends — the body is blocking stream I/O — so without an
        // explicit liveness check cancellation could not stop it at all. The run would restore
        // every remaining file (unprotected, after its foreground host was already gone) and only
        // then have its result discarded by withContext, so a *completed* restore was reported as
        // interrupted. Cooperative stopping stays the normal path; this is the hard backstop.
        addSourceFile("first.txt.crypt", encryptedBlob("first body"))
        addSourceFile("second.txt.crypt", encryptedBlob("second body"))
        addSourceFile("third.txt.crypt", encryptedBlob("third body"))

        var cancellation: CancellationException? = null
        try {
            coroutineScope {
                // Cancels from the first file's progress callback, i.e. after that iteration's
                // liveness check — so exactly one file is restored before the loop aborts.
                decryptAll(onProgress = { this@coroutineScope.cancel() })
            }
        } catch (e: CancellationException) {
            cancellation = e
        }

        assertNotNull(cancellation)
        assertEquals(1, FolderSafProvider.documents.values.count { it.parentId == OUTPUT_ROOT })
    }

    @Test
    fun `the SKIP policy leaves an existing output file untouched`() = runTest {
        val existing = "precious pre-existing content".toByteArray()
        addOutputFile("report.pdf", existing)
        addSourceFile("report.pdf.crypt", encryptedBlob("report body"))

        val result = decryptAll(policy = RestoreCollisionPolicy.SKIP)

        assertEquals(RestoreResult.Success(decrypted = 0, copied = 0, skipped = 1, failed = 0), result)
        assertContentEquals(existing, outputBytes("report.pdf"))
    }

    @Test
    fun `the RENAME_WITH_SUFFIX policy keeps the existing file and adds a suffixed copy`() = runTest {
        val existing = "precious pre-existing content".toByteArray()
        addOutputFile("report.pdf", existing)
        addSourceFile("report.pdf.crypt", encryptedBlob("report body"))

        val result = decryptAll(policy = RestoreCollisionPolicy.RENAME_WITH_SUFFIX)

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        assertContentEquals(existing, outputBytes("report.pdf"))
        assertContentEquals("report body".toByteArray(), outputBytes("report_restored.pdf"))
    }

    @Test
    fun `the OVERWRITE policy replaces the existing file`() = runTest {
        addOutputFile("report.pdf", "stale content".toByteArray())
        addSourceFile("report.pdf.crypt", encryptedBlob("report body"))

        val result = decryptAll(policy = RestoreCollisionPolicy.OVERWRITE)

        assertEquals(RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0), result)
        assertContentEquals("report body".toByteArray(), outputBytes("report.pdf"))
    }

    @Test
    fun `the output directory is listed once instead of once per restored file`() = runTest {
        // The O(N²) fix: DocumentFile.findFile lists every child on each call, so N files used to
        // cost N full listings of the output directory — each an IPC round-trip to the provider.
        repeat(FILE_COUNT) { addSourceFile("file$it.txt.crypt", encryptedBlob("body $it")) }
        FolderSafProvider.childQueryCount = 0
        FolderSafProvider.fileOpenCount = 0

        val result = decryptAll()

        assertEquals(RestoreResult.Success(decrypted = FILE_COUNT, copied = 0, skipped = 0, failed = 0), result)
        // One listing for the source walk plus one for the output tree; the point is that it does
        // not scale with the file count, which the old findFile-per-file did.
        assertTrue(
            FolderSafProvider.childQueryCount <= 2,
            "expected at most 2 child listings, got ${FolderSafProvider.childQueryCount}",
        )
    }

    @Test
    fun `an inaccessible source folder is reported as such`() = runTest {
        FolderSafProvider.documents.remove(SOURCE_ROOT)

        val result = decryptAll()

        assertTrue(result is RestoreResult.Failure, "an unreadable source tree must fail the run")
    }

    private suspend fun decryptAll(
        password: String = PASSWORD,
        policy: RestoreCollisionPolicy = RestoreCollisionPolicy.SKIP,
        onProgress: (RestoreProgress) -> Unit = {},
    ): RestoreResult = engine.decryptAll(
        sourceUri = sourceTree.toString(),
        outputUri = outputTree.toString(),
        password = password,
        collisionPolicy = policy,
        runControl = runControl,
        onProgress = onProgress,
    )

    private fun addDirectory(id: String, parentId: String?): String {
        FolderSafProvider.documents[id] =
            FolderDocument(id, parentId = parentId, displayName = id, file = null, isDirectory = true)
        return id
    }

    private fun addSourceFile(displayName: String, content: ByteArray, parentId: String = SOURCE_ROOT) {
        addFile(displayName, content, parentId)
    }

    private fun addOutputFile(displayName: String, content: ByteArray) {
        addFile(displayName, content, OUTPUT_ROOT)
    }

    private fun addFile(displayName: String, content: ByteArray, parentId: String) {
        val id = "doc-${FolderSafProvider.idSequence++}"
        val file = File.createTempFile("restore-folder-test", null, context.cacheDir)
        file.writeBytes(content)
        FolderSafProvider.documents[id] =
            FolderDocument(id, parentId = parentId, displayName = displayName, file = file)
    }

    private fun childOf(parentId: String, name: String): FolderDocument? =
        FolderSafProvider.documents.values.firstOrNull { it.parentId == parentId && it.displayName == name }

    /** Reads a document back from the *source* tree, to prove a refused restore left it alone. */
    private fun sourceBytes(name: String): ByteArray = outputBytes(name, parentId = SOURCE_ROOT)

    private fun outputBytes(name: String, parentId: String = OUTPUT_ROOT): ByteArray {
        val document = childOf(parentId, name)
        assertNotNull(document, "expected an output document named $name")
        return document.file!!.readBytes()
    }

    /** Assembles a decryptable version-1 FVC1 blob (no AAD binding) with a cheap iteration count. */
    private fun encryptedBlob(payload: String): ByteArray {
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it + 1).toByte() }
        val key = cipher.deriveKey(PASSWORD, salt, TEST_ITERATIONS)
        val body = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }.doFinal(payload.toByteArray())
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
        const val AUTHORITY = "ch.abwesend.foldervault.test.restore.folder"
        const val SOURCE_ROOT = "source"
        const val OUTPUT_ROOT = "output"
        const val PASSWORD = "correct-horse-battery-staple"
        const val TEST_ITERATIONS = 1_000
        const val FILE_COUNT = 8
    }
}

private data class FolderDocument(
    val id: String,
    val parentId: String?,
    val displayName: String?,
    val file: File?,
    val isDirectory: Boolean = false,
)

/**
 * Minimal tree-capable SAF stand-in: metadata and child queries, `openFile` for streams (backed by
 * real temp files), and the `createDocument` / `deleteDocument` provider calls `DocumentFile`
 * issues. [childQueryCount] counts child listings, which is what the directory-listing cache is
 * meant to keep constant.
 */
private class FolderSafProvider : ContentProvider() {
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
        if (uri.pathSegments.lastOrNull() == "children") {
            childQueryCount++
            val parentId = DocumentsContract.getDocumentId(uri)
            documents.values.filter { it.parentId == parentId }.forEach { addRow(cursor, columns, it) }
        } else {
            documents[DocumentsContract.getDocumentId(uri)]?.let { addRow(cursor, columns, it) }
        }
        return cursor
    }

    private fun addRow(cursor: MatrixCursor, columns: Array<out String>, document: FolderDocument) {
        cursor.addRow(
            columns.map { column ->
                when (column) {
                    Document.COLUMN_DOCUMENT_ID -> document.id
                    Document.COLUMN_DISPLAY_NAME -> document.displayName
                    Document.COLUMN_MIME_TYPE ->
                        if (document.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
                    Document.COLUMN_SIZE -> document.file?.length() ?: 0L
                    Document.COLUMN_LAST_MODIFIED -> 0L
                    Document.COLUMN_FLAGS -> if (document.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0
                    else -> null
                }
            },
        )
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        fileOpenCount++
        val documentId = DocumentsContract.getDocumentId(uri)
        val document = documents[documentId] ?: throw FileNotFoundException("No document for $uri")
        val file = document.file ?: throw FileNotFoundException("No file for $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        when (method) {
            METHOD_CREATE_DOCUMENT -> createDocument(extras)
            METHOD_DELETE_DOCUMENT -> deleteDocument(extras)
            else -> super.call(method, arg, extras)
        }

    private fun createDocument(extras: Bundle?): Bundle {
        @Suppress("DEPRECATION")
        val parentUri = extras?.getParcelable<Uri>(EXTRA_URI) ?: error("createDocument without parent uri")
        val parentId = DocumentsContract.getDocumentId(parentUri)
        val name = extras.getString(Document.COLUMN_DISPLAY_NAME) ?: error("createDocument without display name")
        val mimeType = extras.getString(Document.COLUMN_MIME_TYPE)
        val id = "created-${idSequence++}"
        val isDirectory = mimeType == Document.MIME_TYPE_DIR
        val file = if (isDirectory) {
            null
        } else {
            File.createTempFile("restore-folder-created", null, applicationContext.cacheDir)
        }
        documents[id] = FolderDocument(id, parentId, name, file, isDirectory)
        return Bundle().apply {
            putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUriUsingTree(parentUri, id))
        }
    }

    private fun deleteDocument(extras: Bundle?): Bundle {
        @Suppress("DEPRECATION")
        val uri = extras?.getParcelable<Uri>(EXTRA_URI) ?: error("deleteDocument without uri")
        documents.remove(DocumentsContract.getDocumentId(uri))?.file?.delete()
        return Bundle.EMPTY
    }

    private val applicationContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

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
        val documents: MutableMap<String, FolderDocument> = mutableMapOf()

        /** Number of child-listing queries, the metric the directory-listing cache exists to bound. */
        var childQueryCount = 0

        /** Number of document streams opened — how a test sees whether probing ran at all. */
        var fileOpenCount = 0

        var idSequence = 0

        /** Hidden `DocumentsContract` method / extra values. */
        private const val METHOD_DELETE_DOCUMENT = "android:deleteDocument"
        private const val METHOD_CREATE_DOCUMENT = "android:createDocument"
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
