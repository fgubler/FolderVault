package ch.abwesend.foldervault.infrastructure.restore

import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.restore.IForegroundRestoreLauncher
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.restore.IRestoreRunCoordinator
import ch.abwesend.foldervault.domain.restore.RestoreFailureReason
import ch.abwesend.foldervault.domain.restore.RestoreMode
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreRequest
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.domain.restore.RestoreRunControl
import ch.abwesend.foldervault.domain.restore.RestoreRunState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the single restore that may be in flight, independently of any ViewModel.
 *
 * A service-hosted restore outlives the screen that started it, so the run state cannot live in
 * `RestoreViewModel` — the screen observes [state] instead. The staged [RestoreRequest] also stays
 * here rather than travelling in the service's `Intent`, because it carries the backup password.
 *
 * Both flows are hosted here. A single file can be as large as a whole folder — a video, a disk
 * image — and a run tied to `viewModelScope` was lost the moment the user navigated away, taking
 * however much of a multi-gigabyte decrypt had already been done with it. The two differ only in
 * which engine call [runClaimed] dispatches to; everything around it — the claim handshake, the
 * timed takeover, the cooperative stop — is the same run.
 *
 * The handshake is deliberately two-step: [start] records the request and flips the state to
 * `Running` *synchronously*, so the UI reacts to the user's tap immediately; the host that then
 * wins [tryClaim] — the foreground service, or this coordinator's own scope when the service was
 * refused — executes it via [runClaimed].
 */
class RestoreRunCoordinator(
    private val engine: IRestoreEngine,
    private val foregroundLauncher: IForegroundRestoreLauncher,
    dispatchers: IDispatchers,
) : IRestoreRunCoordinator {

    private companion object {
        /**
         * How long to let the freshly dispatched foreground service claim the run before the
         * in-app fallback takes it over. Generous next to the sub-second service dispatch, and a
         * false takeover costs nothing but the "keep the app open" warning.
         */
        private const val FOREGROUND_HANDOVER_GRACE_MS = 5_000L
    }

    private val log get() = logger

    /**
     * Application-scoped, deliberately *not* a `viewModelScope`: an in-app restore must not die
     * because the user navigated away from the restore screen. It still dies with the process,
     * which is exactly what the "keep the app open" warning tells the user.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    private val _state = MutableStateFlow<RestoreRunState>(RestoreRunState.Idle)
    override val state: StateFlow<RestoreRunState> = _state.asStateFlow()

    private val runControl = RestoreRunControl()

    /** Guards against a second host executing the same staged request (service *and* fallback). */
    private val executing = AtomicBoolean(false)

    @Volatile
    private var stagedRequest: RestoreRequest? = null

    /**
     * Stages [request], enters `Running` synchronously, and picks a host: the foreground service
     * first, with an in-app takeover after [FOREGROUND_HANDOVER_GRACE_MS] if the service does not
     * claim the run. Returns `false` when a restore is already staged or running.
     *
     * The takeover is *unconditional* rather than a branch on whether the service start succeeded,
     * because `startForegroundService` returning normally only means the service was dispatched —
     * its own `startForeground` can still be refused afterwards. Without the timed takeover such a
     * refusal would leave the run staged with no host, wedging the coordinator at `Running` for
     * the rest of the process's life.
     */
    override fun start(request: RestoreRequest): Boolean {
        val accepted = _state.value !is RestoreRunState.Running && stagedRequest == null
        if (accepted) {
            runControl.reset()
            stagedRequest = request
            _state.value = RestoreRunState.Running(
                mode = request.mode,
                progress = null,
                hostedInForegroundService = false,
            )
            val dispatched = foregroundLauncher.start()
            scope.launch {
                if (dispatched) delay(FOREGROUND_HANDOVER_GRACE_MS)
                if (tryClaimFor(request)) runClaimed()
            }
        }
        return accepted
    }

    /**
     * Records that the run is hosted in the foreground service, which is what lets the UI drop the
     * "keep the app open" warning. Called by the service only after it both claimed the run and
     * promoted itself to the foreground.
     */
    override fun markHostedInForegroundService() {
        _state.update { current ->
            if (current is RestoreRunState.Running) current.copy(hostedInForegroundService = true) else current
        }
    }

    /**
     * Claims the staged run for the calling host. Exactly one caller can win, which is what makes
     * the "try the service, fall back to the app" handover safe. A host that wins but then cannot
     * proceed must hand the claim back with [releaseClaim]; a host that wins must otherwise call
     * [runClaimed].
     */
    override fun tryClaim(): Boolean = stagedRequest != null && executing.compareAndSet(false, true)

    /**
     * Claims only if [request] is *still* the staged one, so the timed takeover started by one
     * `start` can never execute a different run.
     *
     * Without the identity check a takeover outlives its own run: a restore that finishes inside
     * the grace window (an empty or tiny folder does) leaves its coroutine still sleeping, and a
     * restore the user starts moments later would be claimed by it the instant it wakes —
     * snatching the run from the foreground service that was just dispatched for it, and making
     * the UI warn to keep the app open for no reason.
     */
    private fun tryClaimFor(request: RestoreRequest): Boolean = stagedRequest === request && tryClaim()

    /** Returns a won claim unused, so the other host can take the run instead. */
    override fun releaseClaim() {
        executing.set(false)
    }

    /**
     * Executes the claimed request, publishing progress and the final result into [state]. Only
     * call this after [tryClaim] returned `true`.
     */
    override suspend fun runClaimed() {
        val request = stagedRequest
        if (request == null) {
            // Defensive: a host that won the claim but found nothing staged must hand the flag back,
            // or `tryClaim` can never succeed again and the coordinator wedges at `Running` for the
            // life of the process — with no way out, since the progress dialog is driven by exactly
            // that state.
            log.warning("Claimed a restore that is no longer staged — releasing the claim again")
            releaseClaim()
            return
        }
        try {
            finish(request.mode, execute(request))
        } catch (e: CancellationException) {
            // The host went away mid-run (service destroyed, process going down). Leaving the
            // state at Running would strand the UI in a progress dialog that can never complete.
            finish(request.mode, RestoreResult.Failure(RestoreFailureReason.RUN_INTERRUPTED))
            throw e
        } catch (e: Exception) {
            // The engine reports per-file problems through its counters, so reaching here means
            // something unexpected — which must still release the UI rather than hang it.
            log.error("Restore failed unexpectedly", e)
            finish(request.mode, RestoreResult.Failure(RestoreFailureReason.RUN_INTERRUPTED))
        }
    }

    /** Dispatches to the engine call the staged [request] asks for. */
    private suspend fun execute(request: RestoreRequest): RestoreResult = when (request) {
        is RestoreRequest.WholeFolder -> engine.decryptAll(
            sourceUri = request.sourceUri,
            outputUri = request.outputUri,
            password = request.password,
            collisionPolicy = request.collisionPolicy,
            runControl = runControl,
            onProgress = ::publishProgress,
        )
        is RestoreRequest.SingleFile -> engine.decryptSingleFile(
            sourceFileUri = request.sourceFileUri,
            outputFileUri = request.outputFileUri,
            password = request.password,
            runControl = runControl,
            onProgress = ::publishProgress,
        )
    }

    /**
     * Requests a cooperative stop. A folder run ends at the next file boundary with the counts of
     * what it already restored; a single-file run ends at the next chunk of its one file, with
     * nothing — half a plaintext file is worse than none, so the engine deletes it.
     */
    override fun requestStop() {
        runControl.requestStop()
    }

    /** Clears a finished run once the screen has shown its result. */
    override fun acknowledgeResult() {
        _state.update { current -> if (current is RestoreRunState.Finished) RestoreRunState.Idle else current }
    }

    private fun publishProgress(progress: RestoreProgress) {
        _state.update { current ->
            if (current is RestoreRunState.Running) current.copy(progress = progress) else current
        }
    }

    private fun finish(mode: RestoreMode, result: RestoreResult) {
        stagedRequest = null
        executing.set(false)
        _state.value = RestoreRunState.Finished(mode, result)
    }
}
