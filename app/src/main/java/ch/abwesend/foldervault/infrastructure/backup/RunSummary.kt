package ch.abwesend.foldervault.infrastructure.backup

class RunSummary {
    var filesUploaded: Int = 0
    var filesSkipped: Int = 0
    var filesFailed: Int = 0
    var bytesUploaded: Long = 0L
    var oversizedCount: Int = 0
    var totalFilesDiscovered: Int = 0
    var authLost: Boolean = false
    var quotaExceeded: Boolean = false
    var hitTimeBudget: Boolean = false
    var consecutiveQuotaCount: Int = 0
}
