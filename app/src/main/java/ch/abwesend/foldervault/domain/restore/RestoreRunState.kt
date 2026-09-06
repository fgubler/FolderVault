package ch.abwesend.foldervault.domain.restore

/**
 * Observable state of the one restore that may run at a time, owned by `RestoreRunCoordinator`
 * rather than by a ViewModel: the run outlives the screen when it is hosted in the foreground
 * service, so the screen has to *observe* the run instead of owning it.
 *
 * Both the whole-folder and the single-file flow are hosted here, so every non-idle state carries
 * the [RestoreMode] that staged it. A screen showing one mode must ignore the other mode's run
 * completely — otherwise a folder restore still going in the service would drive the single-file
 * screen's progress, and its result would land on top of the single-file one.
 */
sealed interface RestoreRunState {

    /** No restore running and no result waiting to be shown. */
    data object Idle : RestoreRunState

    /**
     * A restore is in flight. [progress] is `null` until the run has something to report — for a
     * folder that is after the source scan and the password probing, for a single file after the
     * first chunk of bytes. [hostedInForegroundService] drives the "please keep the app open" hint:
     * when the run is *not* service-hosted, leaving the app can have it killed mid-restore.
     */
    data class Running(
        val mode: RestoreMode,
        val progress: RestoreProgress?,
        val hostedInForegroundService: Boolean,
    ) : RestoreRunState

    /** The run ended; the result waits until the screen acknowledges it. */
    data class Finished(
        val mode: RestoreMode,
        val result: RestoreResult,
    ) : RestoreRunState
}
