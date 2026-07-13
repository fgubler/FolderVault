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
        val mtimeUsable = localMtime != null && localMtime != 0L
        return if (indexed == null) {
            Decision.NEW
        } else if (indexed.isBaseline) {
            decideForBaseline(localMtime, localSize, indexed, mtimeUsable)
        } else if (mtimeUsable) {
            if (localMtime != indexed.localLastModified || localSize != indexed.localSize) {
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

    /**
     * Baseline rows describe files that existed at a "only sync changes from now on" config's
     * first run and were deliberately never uploaded, so neither [Decision.CHANGED] nor
     * [Decision.CHECK_CLOUD] applies to them: the first divergence from the baseline is the
     * file's *first upload* ([Decision.NEW] — no previous cloud file to overwrite, duplicate,
     * or ignore), and with an unusable mtime there is no cloud object whose existence could
     * settle a size match — uploading instead would re-upload the whole pre-existing tree on
     * every run, so a size match is treated as [Decision.UNCHANGED] (documented limitation:
     * a pre-existing file modified without a size change stays undetected on mtime-less
     * providers).
     */
    private fun decideForBaseline(
        localMtime: Long?,
        localSize: Long,
        indexed: UploadedFileIndexEntity,
        mtimeUsable: Boolean,
    ): Decision {
        val matchesBaseline = if (mtimeUsable) {
            localMtime == indexed.localLastModified && localSize == indexed.localSize
        } else {
            localSize == indexed.localSize
        }
        return if (matchesBaseline) Decision.UNCHANGED else Decision.NEW
    }
}
