package ch.abwesend.foldervault.infrastructure.storage

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Robolectric coverage for [ScopedStorageHelper.walkTree], focused on the BUG-4 distinction that
 * a backup app must never blur: an *inaccessible* root (deleted folder / revoked SAF permission)
 * has to report `false`, while an *accessible but empty* root reports `true`. Treating the former
 * as "0 files, up to date" is a silent data-protection failure.
 *
 * Must run outside the Bash sandbox (`! ./gradlew test`) — Robolectric downloads its Android jars,
 * which the sandbox blocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScopedStorageHelperTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `walkTree returns false and visits nothing when the root has no backing provider`() {
        // A well-formed tree URI whose authority resolves to no provider: fromTreeUri still yields
        // a DocumentFile, but every provider query fails, so exists()/canRead() are false. This is
        // what a revoked permission / deleted folder looks like to DocumentFile.
        val treeUri = DocumentsContract.buildTreeDocumentUri("com.example.gone", "root")
        val visited = mutableListOf<String>()

        val accessible = ScopedStorageHelper.walkTree(context, treeUri) { relPath, _ -> visited.add(relPath) }

        assertFalse(accessible, "an unresolvable root must be reported as inaccessible")
        assertTrue(visited.isEmpty())
    }

    @Test
    fun `walkTree returns false when the root document no longer exists`() {
        // Provider is present but the root document is gone (empty result for its own query) —
        // e.g. the folder was deleted while the tree URI is still persisted.
        registerProvider(emptyMap())
        val treeUri = grantedTreeUri()
        val visited = mutableListOf<String>()

        val accessible = ScopedStorageHelper.walkTree(context, treeUri) { relPath, _ -> visited.add(relPath) }

        assertFalse(accessible)
        assertTrue(visited.isEmpty())
    }

    @Test
    fun `walkTree returns true and visits nothing for an accessible but empty folder`() {
        registerProvider(mapOf(ROOT_ID to dir(ROOT_ID)))
        val treeUri = grantedTreeUri()
        val visited = mutableListOf<String>()

        val accessible = ScopedStorageHelper.walkTree(context, treeUri) { relPath, _ -> visited.add(relPath) }

        assertTrue(accessible, "an empty but readable folder must be reported as accessible, not gone")
        assertTrue(visited.isEmpty())
    }

    @Test
    fun `walkTree visits files with slash-separated relative paths for an accessible tree`() {
        registerProvider(
            mapOf(
                ROOT_ID to dir(ROOT_ID, children = listOf("top.txt", "sub")),
                "top.txt" to file("top.txt"),
                "sub" to dir("sub", children = listOf("nested.txt")),
                "nested.txt" to file("nested.txt"),
            )
        )
        val treeUri = grantedTreeUri()
        val visited = mutableListOf<String>()

        val accessible = ScopedStorageHelper.walkTree(context, treeUri) { relPath, _ -> visited.add(relPath) }

        assertTrue(accessible)
        assertEquals(setOf("top.txt", "sub/nested.txt"), visited.toSet())
    }

    private fun grantedTreeUri(): Uri {
        val treeUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)
        // canRead() requires a held read permission on the root document URI.
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, ROOT_ID)
        context.grantUriPermission(context.packageName, rootDocUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return treeUri
    }

    private fun registerProvider(nodes: Map<String, Node>) {
        FakeDocumentsProvider.nodes = nodes
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, AUTHORITY)
    }

    private fun dir(id: String, children: List<String> = emptyList()) =
        Node(id, Document.MIME_TYPE_DIR, children)

    private fun file(id: String) = Node(id, "text/plain", emptyList())

    private companion object {
        const val AUTHORITY = "ch.abwesend.foldervault.test.docs"
        const val ROOT_ID = "root"
    }
}

private data class Node(val displayName: String, val mimeType: String, val children: List<String>)

/**
 * Minimal in-memory [DocumentsProvider]-style stub answering the exact queries `DocumentFile`
 * issues while walking a tree: the document's own row (for exists / mime / name) and its
 * children listing. Contents are injected via [nodes] before [Robolectric.setupContentProvider].
 */
private class FakeDocumentsProvider : ContentProvider() {
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
        val isChildrenQuery = uri.pathSegments.lastOrNull() == "children"
        if (isChildrenQuery) {
            nodes[documentId]?.children?.forEach { childId -> addRow(cursor, columns, childId) }
        } else if (nodes.containsKey(documentId)) {
            addRow(cursor, columns, documentId)
        }
        return cursor
    }

    private fun addRow(cursor: MatrixCursor, columns: Array<out String>, documentId: String) {
        val node = nodes.getValue(documentId)
        val row = columns.map { column ->
            when (column) {
                Document.COLUMN_DOCUMENT_ID -> documentId
                Document.COLUMN_DISPLAY_NAME -> node.displayName
                Document.COLUMN_MIME_TYPE -> node.mimeType
                Document.COLUMN_SIZE -> 0L
                Document.COLUMN_LAST_MODIFIED -> 0L
                Document.COLUMN_FLAGS -> 0
                else -> null
            }
        }
        cursor.addRow(row)
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
        var nodes: Map<String, Node> = emptyMap()
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
