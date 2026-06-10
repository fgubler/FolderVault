package ch.abwesend.foldervault.infrastructure.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object ScopedStorageHelper {
    /**
     * Depth-first traversal of the tree rooted at [treeUri].
     * [onFile] is called for each leaf file with (relativePath, DocumentFile).
     * relativePath uses '/' separators, no leading slash.
     */
    fun walkTree(
        context: Context,
        treeUri: Uri,
        onFile: (relativePath: String, file: DocumentFile) -> Unit,
    ) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return
        walkDir(root, "", onFile)
    }

    private fun walkDir(
        dir: DocumentFile,
        prefix: String,
        onFile: (String, DocumentFile) -> Unit,
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val childPath = if (prefix.isEmpty()) name else "$prefix/$name"
            when {
                child.isDirectory -> walkDir(child, childPath, onFile)
                child.isFile -> onFile(childPath, child)
            }
        }
    }
}
