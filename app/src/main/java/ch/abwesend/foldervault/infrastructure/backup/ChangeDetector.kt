package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.infrastructure.room.entity.UploadedFileIndexEntity

/**
 * Determines whether a local file should be uploaded, given the current index entry.
 */
internal object ChangeDetector {

    enum class Decision {
        /** No index row — upload unconditionally. */
        NEW,

        /** mtime or size differs — upload. */
        CHANGED,

        /** Same mtime+size — skip. */
        UNCHANGED,

        /** Same size but mtime unavailable — need cloud-existence check to decide. */
        CHECK_CLOUD,
    }

    /**
     * @param localMtime  null or 0 means "unavailable" (treat as unreliable)
     * @param localSize   current file size in bytes
     * @param indexed     the current-version row from UploadedFileIndex, or null if absent
     */
    fun decide(localMtime: Long?, localSize: Long, indexed: UploadedFileIndexEntity?): Decision {
        if (indexed == null) return Decision.NEW

        val mtimeUsable = localMtime != null && localMtime != 0L
        return if (mtimeUsable) {
            val mtime = localMtime!!
            if (mtime != indexed.localLastModified || localSize != indexed.localSize) {
                Decision.CHANGED
            } else {
                Decision.UNCHANGED
            }
        } else {
            if (localSize != indexed.localSize) {
                Decision.CHANGED
            } else {
                Decision.CHECK_CLOUD
            }
        }
    }
}
