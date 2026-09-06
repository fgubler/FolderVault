package androidx.documentfile.provider

import android.net.Uri

/**
 * Lives in `androidx.documentfile.provider` because `DocumentFile`'s only constructor is
 * package-private — the standard way to hand-write a fake for it, and the project prefers a fake
 * behind a platform seam over mocking.
 *
 * An in-memory stand-in for a SAF document that counts how often its children are listed — the
 * point of `OutputTreeCache` is that a restore lists each directory once instead of once per
 * restored file. Only the members that cache uses are implemented; the rest fail loudly.
 */
class FakeDocumentFile(
    private val displayName: String,
    private val directory: Boolean = false,
) : DocumentFile(null) {

    val children = mutableListOf<FakeDocumentFile>()
    var listCallCount = 0
    var deleted = false

    /** The parent this document was created under, so [delete] can detach it like a real one. */
    private var parent: FakeDocumentFile? = null

    override fun createFile(mimeType: String, displayName: String): DocumentFile? =
        FakeDocumentFile(displayName).also { adopt(it) }

    override fun createDirectory(displayName: String): DocumentFile? =
        FakeDocumentFile(displayName, directory = true).also { adopt(it) }

    private fun adopt(child: FakeDocumentFile) {
        child.parent = this
        children.add(child)
    }

    override fun listFiles(): Array<DocumentFile> {
        listCallCount++
        return children.toTypedArray()
    }

    /**
     * `null` once deleted, like the real `TreeDocumentFile`: `getName()` is not a cached field but
     * a fresh query against the provider, which has nothing to answer for a document that is gone.
     * The fake used to keep returning the name, which hid a bug where `OutputTreeCache.forgetChild`
     * read the name *after* its caller had deleted the document and so un-indexed nothing.
     */
    override fun getName(): String? = if (deleted) null else displayName

    override fun isDirectory(): Boolean = directory
    override fun isFile(): Boolean = !directory

    /** Detaches from the parent as a real delete does, so a later listing no longer shows it. */
    override fun delete(): Boolean {
        deleted = true
        parent?.children?.remove(this)
        return true
    }

    override fun getUri(): Uri = error("the cache must not need a Uri")
    override fun getType(): String? = null
    override fun isVirtual(): Boolean = false
    override fun lastModified(): Long = 0
    override fun length(): Long = 0
    override fun canRead(): Boolean = true
    override fun canWrite(): Boolean = true
    override fun exists(): Boolean = !deleted
    override fun findFile(displayName: String): DocumentFile? =
        error("the cache exists to replace findFile")
    override fun renameTo(displayName: String): Boolean = false
}
