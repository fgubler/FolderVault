package ch.abwesend.foldervault.infrastructure.restore

import androidx.documentfile.provider.DocumentFile

/**
 * Name → document index over the restore's output tree, built lazily one directory at a time.
 *
 * `DocumentFile.findFile(name)` lists *all* children of a directory and compares display names,
 * so calling it once per restored file makes a flat backup folder of N files cost N full child
 * listings — O(N²) SAF queries, and each query is an IPC round-trip to the document provider
 * (against a cloud provider, a network call). A restore of a few thousand files was slow enough
 * to read as a hang.
 *
 * This caches each directory's listing on first touch and keeps it current as the restore creates
 * and deletes documents, so a directory is listed exactly once per run. Correct because the run
 * is the only writer of its own output tree — a foreign process changing the tree mid-restore
 * would be missed, which is no worse than the racy `findFile` it replaces.
 *
 * Not thread-safe: the restore is deliberately serial (one file at a time).
 */
internal class OutputTreeCache(private val root: DocumentFile) {

    /**
     * Children of an already-listed directory, keyed by the directory *instance*, then by display
     * name. Identity keying is sound because every directory the restore touches comes from this
     * cache — [resolveDirectory] returns either [root] or an instance the cache itself created or
     * indexed — so the same directory is always the same object within a run. It also keeps the
     * cache free of `Uri`, which makes it unit-testable without an Android runtime.
     */
    private val listings = mutableMapOf<DocumentFile, MutableMap<String, DocumentFile>>()

    /**
     * Resolves [pathSegments] under the root, creating the directories that do not exist yet.
     * Returns `null` when a segment exists as a *file*, or when creating it failed.
     */
    fun resolveDirectory(pathSegments: List<String>): DocumentFile? {
        var dir: DocumentFile? = root
        for (segment in pathSegments) {
            val parent = dir ?: break
            val existing = findChild(parent, segment)
            dir = when {
                existing != null && existing.isDirectory -> existing
                existing != null -> null // a file blocks the directory name
                else -> parent.createDirectory(segment)?.also { register(parent, it) }
            }
        }
        return dir
    }

    /** The child of [dir] displayed as [name], or `null` if there is none. */
    fun findChild(dir: DocumentFile, name: String): DocumentFile? = listingOf(dir)[name]

    /** Creates a child document of [dir] and indexes it, so a later lookup finds it without a query. */
    fun createChild(dir: DocumentFile, mimeType: String, name: String): DocumentFile? =
        dir.createFile(mimeType, name)?.also { register(dir, it) }

    /**
     * Drops the child displayed as [name] from [dir]'s index after it was deleted, so a later
     * lookup does not hand back a document that no longer exists.
     *
     * Takes the *name* rather than the `DocumentFile` on purpose. `DocumentFile.getName()` is not
     * a cached field — it re-queries the provider — so a deleted document reports `null`, and an
     * earlier version of this method read the name after its caller had already deleted the
     * document. It therefore removed nothing at all against a real provider, while the in-memory
     * test fake (which kept answering with its name) said it worked. Callers now capture the name
     * while the document still exists; use [nameOf] for that.
     */
    fun forgetChild(dir: DocumentFile, name: String?) {
        name?.let { listingOf(dir).remove(it) }
    }

    /**
     * The display name to hand to [forgetChild] later. Read it *before* deleting the document —
     * that is the whole point. It is the child's own name rather than the one it was requested
     * under, because a provider may create a document under a different name, and it is the actual
     * name that both this index and a fresh listing are keyed by.
     */
    fun nameOf(child: DocumentFile): String? = child.name

    private fun register(dir: DocumentFile, child: DocumentFile) {
        child.name?.let { listingOf(dir)[it] = child }
    }

    private fun listingOf(dir: DocumentFile): MutableMap<String, DocumentFile> =
        listings.getOrPut(dir) {
            dir.listFiles().mapNotNullTo(mutableListOf()) { child ->
                child.name?.let { it to child }
            }.toMap(mutableMapOf())
        }
}
