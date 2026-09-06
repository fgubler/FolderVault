package ch.abwesend.foldervault.domain.restore

/**
 * Observable state of the one folder restore that may run at a time, owned by
 * `RestoreRunCoordinator` rather than by a ViewModel: the run outlives the screen when it is
 * hosted in the foreground service, so the screen has to *observe* the run instead of owning it.
 */
sealed interface RestoreRunState {
    /** No restore running and no result waiting to be shown. */
    data object Idle : RestoreRunState

    /**
     * A restore is in flight. [progress] is `null` until the source scan finished and the first
     * file boundary is reached. [hostedInForegroundService] drives the "please keep the app open"
     * hint: when the run is *not* service-hosted, leaving the app can have it killed mid-restore.
     */
    data class Running(
        val progress: RestoreProgress?,
        val hostedInForegroundService: Boolean,
    ) : RestoreRunState

    /** The run ended; the result waits until the screen acknowledges it. */
    data class Finished(val result: RestoreResult) : RestoreRunState
}
