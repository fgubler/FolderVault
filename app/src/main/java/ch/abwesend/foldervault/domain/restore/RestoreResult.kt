package ch.abwesend.foldervault.domain.restore

sealed interface RestoreResult {
    data class Success(val decrypted: Int, val copied: Int, val skipped: Int, val failed: Int) : RestoreResult

    /**
     * The user stopped the run. Carries the same counters as [Success] for the files that were
     * already processed — a restore of thousands of files that is stopped half-way has really
     * restored those files, and saying only "cancelled" would hide that.
     */
    data class Cancelled(val decrypted: Int, val copied: Int, val skipped: Int, val failed: Int) : RestoreResult

    data object InvalidPassword : RestoreResult
    data class Failure(val reason: RestoreFailureReason) : RestoreResult
}
