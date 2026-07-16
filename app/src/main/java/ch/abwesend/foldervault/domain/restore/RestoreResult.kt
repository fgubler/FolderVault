package ch.abwesend.foldervault.domain.restore

sealed interface RestoreResult {
    data class Success(val decrypted: Int, val copied: Int, val skipped: Int, val failed: Int) : RestoreResult
    data object Cancelled : RestoreResult
    data object InvalidPassword : RestoreResult
    data class Failure(val reason: RestoreFailureReason) : RestoreResult
}
