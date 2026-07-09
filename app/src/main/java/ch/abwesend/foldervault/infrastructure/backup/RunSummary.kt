package ch.abwesend.foldervault.infrastructure.backup

class RunSummary {
    var filesUploaded: Int = 0
    var filesSkipped: Int = 0
    var filesFailed: Int = 0
    var bytesUploaded: Long = 0L
    var oversizedUploaded: Int = 0
    var oversizedDeferred: Int = 0
    var totalFilesDiscovered: Int = 0
    var authLost: Boolean = false
    var quotaExceeded: Boolean = false
    var hitTimeBudget: Boolean = false

    /**
     * Set when the source folder could not be accessed at all (deleted, or its persisted SAF
     * permission was revoked). Distinct from "the folder is empty": an inaccessible source must
     * fail the run, not report a silent, up-to-date success with zero files (BUG-4).
     */
    var sourceFolderInaccessible: Boolean = false
    var consecutiveQuotaCount: Int = 0
}
