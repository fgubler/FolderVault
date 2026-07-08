package ch.abwesend.foldervault.infrastructure.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object ScopedStorageHelper {
    /**
     * Depth-first traversal of the tree rooted at [treeUri].
     * [onFile] is called for each leaf file with (relativePath, DocumentFile).
     * relativePath uses '/' separators, no leading slash.
     *
     * @return `true` if the root was accessible and traversed (even when it contains no files);
     *   `false` if the root could not be accessed at all — [DocumentFile.fromTreeUri] returned
     *   null, or the root no longer exists / is no longer readable (e.g. the folder was deleted or
     *   its persisted SAF permission was revoked). Callers MUST distinguish this from an empty
     *   tree: for a backup app, silently treating an inaccessible root as "0 files, up to date"
     *   would report false success while nothing is being protected (BUG-4).
     */
    fun walkTree(
        context: Context,
        treeUri: Uri,
        onFile: (relativePath: String, file: DocumentFile) -> Unit,
    ): Boolean {
        val root = DocumentFile.fromTreeUri(context, treeUri)
        return if (root == null || !root.exists() || !root.canRead()) {
            false
        } else {
            walkDir(root, "", onFile)
            true
        }
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
