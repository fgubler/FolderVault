package ch.abwesend.foldervault.domain.restore

import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the single restore that may be in flight — whole-folder or single-file — so the run is not
 * tied to the lifetime of the screen that started it: a service-hosted restore keeps going while
 * the user is elsewhere, and re-entering the restore screen has to pick its progress back up.
 * Both flows qualify: a single picked file can be a multi-gigabyte video, and losing that decrypt
 * because the user navigated away is the same failure as losing a folder run.
 *
 * The handshake is two-step on purpose. [start] records the request and enters `Running`
 * synchronously, so the UI reacts to the user's tap immediately; the host that then wins
 * [tryClaim] — the foreground service, or an app-scoped fallback — executes it via [runClaimed].
 * Exactly one host can win, which is what makes the handover safe. Host selection lives here
 * rather than in the calling ViewModel so that a restore is never lost, or left staged with no
 * host at all, just because the screen that started it went away.
 */
interface IRestoreRunCoordinator {

    /** Progress and outcome of the run, observable independently of any ViewModel. */
    val state: StateFlow<RestoreRunState>

    /**
     * Stages [request], enters `Running`, and arranges for a host to execute it. Returns `false`
     * when a restore is already staged or running.
     */
    fun start(request: RestoreRequest): Boolean

    /**
     * Claims the staged run for the calling host. A host that wins must either execute it with
     * [runClaimed] or hand the claim back with [releaseClaim].
     */
    fun tryClaim(): Boolean

    /** Returns a won claim unused, so the other host can take the run instead. */
    fun releaseClaim()

    /** Executes the claimed request. Only valid after [tryClaim] returned `true`. */
    suspend fun runClaimed()

    /**
     * Records that the run is hosted in the foreground service, which lets the UI drop the "keep
     * the app open" warning. Called only after the service both claimed and promoted itself.
     */
    fun markHostedInForegroundService()

    /**
     * Requests a cooperative stop — never a coroutine cancellation, which would discard the result.
     * A folder run ends at the next file boundary and reports what it already restored; a
     * single-file run ends at the next chunk of its one file and reports nothing, because a
     * partly written plaintext file is deleted rather than left to pass for a whole one.
     */
    fun requestStop()

    /** Clears a finished run once the screen has shown its result. */
    fun acknowledgeResult()
}
