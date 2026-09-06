package ch.abwesend.foldervault.infrastructure.restore

import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.restore.IForegroundRestoreLauncher
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.restore.IRestoreRunCoordinator
import ch.abwesend.foldervault.domain.restore.RestoreFailureReason
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
 * Owns the single folder restore that may be in flight, independently of any ViewModel.
 *
 * A service-hosted restore outlives the screen that started it, so the run state cannot live in
 * `RestoreViewModel` — the screen observes [state] instead. The staged [RestoreRequest] also stays
 * here rather than travelling in the service's `Intent`, because it carries the backup password.
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
            _state.value = RestoreRunState.Running(progress = null, hostedInForegroundService = false)
            val dispatched = foregroundLauncher.start()
            scope.launch {
                if (dispatched) delay(FOREGROUND_HANDOVER_GRACE_MS)
                if (tryClaim()) runClaimed()
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

    /** Returns a won claim unused, so the other host can take the run instead. */
    override fun releaseClaim() {
        executing.set(false)
    }

    /**
     * Executes the claimed request, publishing progress and the final result into [state]. Only
     * call this after [tryClaim] returned `true`.
     */
    override suspend fun runClaimed() {
        val request = stagedRequest ?: return
        try {
            val result = engine.decryptAll(
                sourceUri = request.sourceUri,
                outputUri = request.outputUri,
                password = request.password,
                collisionPolicy = request.collisionPolicy,
                runControl = runControl,
                onProgress = ::publishProgress,
            )
            finish(result)
        } catch (e: CancellationException) {
            // The host went away mid-run (service destroyed, process going down). Leaving the
            // state at Running would strand the UI in a progress dialog that can never complete.
            finish(RestoreResult.Failure(RestoreFailureReason.RUN_INTERRUPTED))
            throw e
        } catch (e: Exception) {
            // The engine reports per-file problems through its counters, so reaching here means
            // something unexpected — which must still release the UI rather than hang it.
            log.error("Folder restore failed unexpectedly", e)
            finish(RestoreResult.Failure(RestoreFailureReason.RUN_INTERRUPTED))
        }
    }

    /** Requests a cooperative stop; the run ends at the next file boundary with partial counts. */
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

    private fun finish(result: RestoreResult) {
        stagedRequest = null
        executing.set(false)
        _state.value = RestoreRunState.Finished(result)
    }
}
