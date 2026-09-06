package ch.abwesend.foldervault.infrastructure.restore

import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.restore.IForegroundRestoreLauncher
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreFailureReason
import ch.abwesend.foldervault.domain.restore.RestoreMode
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreRequest
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.domain.restore.RestoreRunControl
import ch.abwesend.foldervault.domain.restore.RestoreRunState
import ch.abwesend.foldervault.domain.restore.RestoreScanResult
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for the real [RestoreRunCoordinator] — the class that owns the one restore that may be
 * in flight, and the only place where the two possible hosts (the foreground service and the
 * in-app fallback) are arbitrated.
 *
 * It had no tests of its own: `RestoreForegroundServiceTest` and `RestoreViewModelTest` both
 * substitute a fake for it, so the claim handshake, the timed takeover and the failure mapping were
 * covered by nothing — while three review findings (a service that tore itself down without
 * promoting, a stale takeover stealing the next run, and an early return leaving the claim flag
 * set) were defects in exactly this logic. Every one of those is pinned below.
 *
 * The coordinator is deliberately Android-free, so this is a plain JVM spec: a
 * [StandardTestDispatcher] makes the 5 s handover grace window an `advanceTimeBy` rather than a
 * sleep, and both collaborators are hand-written fakes (the project's preference behind a domain
 * seam). [FakeRestoreEngine] can hold a run open, publish progress from inside it, and fail in the
 * two ways that matter — a dead host (cancellation) and an unexpected throw.
 */
class RestoreRunCoordinatorTest : StringSpec({
    // The coordinator is stateful (staged request, claim flag, run control), so every test gets a
    // fresh one rather than inheriting whatever the previous test left behind.
    isolationMode = IsolationMode.InstancePerTest

    val dispatcher = StandardTestDispatcher()
    val dispatchers = object : IDispatchers {
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
        override val mainImmediate: CoroutineDispatcher = dispatcher
    }
    val engine = FakeRestoreEngine()
    val launcher = FakeForegroundRestoreLauncher()
    val coordinator = RestoreRunCoordinator(engine, launcher, dispatchers)

    val folderRequest = RestoreRequest.WholeFolder(
        sourceUri = "content://backup/tree",
        outputUri = "content://restored/tree",
        password = "correct horse",
        collisionPolicy = RestoreCollisionPolicy.OVERWRITE,
    )
    val fileRequest = RestoreRequest.SingleFile(
        sourceFileUri = "content://backup/report.pdf.crypt",
        outputFileUri = "content://restored/report.pdf",
        password = "correct horse",
    )

    "start stages the request and enters Running synchronously, before any host has it" {
        runTest(dispatcher) {
            coordinator.start(folderRequest) shouldBe true

            // Deliberately no time advance: the UI reacts to the user's tap itself, not to a
            // coroutine that may not have been dispatched yet.
            coordinator.state.value shouldBe RestoreRunState.Running(
                mode = RestoreMode.WHOLE_FOLDER,
                progress = null,
                hostedInForegroundService = false,
            )
            launcher.startCount shouldBe 1
            engine.runCount shouldBe 0
        }
    }

    "a second start is refused while a restore is in flight, and does not reach the engine" {
        runTest(dispatcher) {
            launcher.dispatched = false
            engine.holdRun()
            coordinator.start(folderRequest) shouldBe true
            advanceUntilIdle()

            coordinator.start(fileRequest) shouldBe false
            advanceUntilIdle()

            engine.folderCalls.size shouldBe 1
            engine.fileCalls.shouldBeEmpty()
            coordinator.state.value.shouldBeInstanceOf<RestoreRunState.Running>().mode shouldBe
                RestoreMode.WHOLE_FOLDER
            engine.finishRun()
            advanceUntilIdle()
        }
    }

    "a refused foreground start is taken over in-app without waiting out the grace window" {
        runTest(dispatcher) {
            // startForegroundService threw (most plausibly the shared dataSync budget), so there is
            // no service to wait for: the fallback must take the run immediately.
            launcher.dispatched = false

            coordinator.start(folderRequest) shouldBe true
            runCurrent()

            engine.folderCalls.size shouldBe 1
            currentTime shouldBe 0
        }
    }

    "a dispatched service is left the grace window before the in-app fallback takes the run" {
        runTest(dispatcher) {
            launcher.dispatched = true

            coordinator.start(folderRequest) shouldBe true
            advanceTimeBy(HANDOVER_GRACE_MS)

            engine.runCount shouldBe 0
            advanceUntilIdle()
            engine.folderCalls.size shouldBe 1
        }
    }

    "the host that claims first keeps the run — the timed takeover cannot run it a second time" {
        runTest(dispatcher) {
            launcher.dispatched = true
            engine.holdRun()
            coordinator.start(folderRequest) shouldBe true

            // What RestoreForegroundService.startRun does: claim, promote, execute.
            coordinator.tryClaim() shouldBe true
            coordinator.markHostedInForegroundService()
            val serviceRun = launch { coordinator.runClaimed() }
            advanceUntilIdle()

            engine.runCount shouldBe 1
            coordinator.state.value shouldBe RestoreRunState.Running(
                mode = RestoreMode.WHOLE_FOLDER,
                progress = null,
                hostedInForegroundService = true,
            )

            engine.finishRun()
            advanceUntilIdle()
            serviceRun.isCompleted shouldBe true
            engine.runCount shouldBe 1
            coordinator.state.value shouldBe RestoreRunState.Finished(
                mode = RestoreMode.WHOLE_FOLDER,
                result = RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0),
            )
        }
    }

    "a takeover left over from a finished run cannot claim the next one" {
        runTest(dispatcher) {
            // Review finding S12. A restore that finishes inside the grace window (an empty or tiny
            // folder does) leaves its takeover coroutine still sleeping. Without the identity check
            // in tryClaimFor it wakes up and claims whatever is staged *then* — snatching the run
            // from the foreground service just dispatched for it, and making the UI warn the user
            // to keep the app open for no reason.
            launcher.dispatched = true
            coordinator.start(folderRequest) shouldBe true
            coordinator.tryClaim() shouldBe true
            coordinator.runClaimed()
            coordinator.state.value.shouldBeInstanceOf<RestoreRunState.Finished>()

            advanceTimeBy(HANDOVER_GRACE_MS - SECOND_MS)
            coordinator.acknowledgeResult()
            coordinator.start(fileRequest) shouldBe true

            // Past the *first* run's grace window, but well before the second run's own.
            advanceTimeBy(2 * SECOND_MS)

            engine.fileCalls.shouldBeEmpty()
            coordinator.tryClaim() shouldBe true
        }
    }

    "a claim on a run that is no longer staged is handed back instead of wedging the coordinator" {
        runTest(dispatcher) {
            // Review finding N17. `executing` is cleared only by finish() or releaseClaim(), so an
            // early return that skips both leaves the flag set for the life of the process: no host
            // can ever execute a run again, and the progress dialog is driven by exactly that
            // state. Not reachable through the UI (the handshake keeps a second host out), so what
            // is pinned here is the invariant, not the race.
            coordinator.runClaimed()

            // An early return must also not invent a result for a run that never existed.
            coordinator.state.value shouldBe RestoreRunState.Idle
            coordinator.start(folderRequest) shouldBe true
            coordinator.tryClaim() shouldBe true
            engine.runCount shouldBe 0
        }
    }

    "a host that dies mid-run reports an interrupted restore and still propagates the cancellation" {
        runTest(dispatcher) {
            launcher.dispatched = true
            engine.holdRun()
            coordinator.start(folderRequest) shouldBe true
            coordinator.tryClaim() shouldBe true
            val hostJob = launch { coordinator.runClaimed() }
            advanceUntilIdle()
            engine.runCount shouldBe 1

            // The service was destroyed / the process is going down: its scope is cancelled.
            hostJob.cancelAndJoin()

            // Leaving the state at Running would strand the UI in a dialog that can never complete.
            coordinator.state.value shouldBe RestoreRunState.Finished(
                mode = RestoreMode.WHOLE_FOLDER,
                result = RestoreResult.Failure(RestoreFailureReason.RUN_INTERRUPTED),
            )
            hostJob.isCancelled shouldBe true
            coordinator.tryClaim() shouldBe false
        }
    }

    "an unexpected engine failure ends the run instead of leaving the dialog spinning" {
        runTest(dispatcher) {
            // The engine reports per-file problems through its counters, so anything thrown here is
            // a bug — which must still release the UI rather than hang it.
            launcher.dispatched = false
            engine.failWith = IllegalStateException("something nobody expected")

            coordinator.start(fileRequest) shouldBe true
            advanceUntilIdle()

            coordinator.state.value shouldBe RestoreRunState.Finished(
                mode = RestoreMode.SINGLE_FILE,
                result = RestoreResult.Failure(RestoreFailureReason.RUN_INTERRUPTED),
            )
        }
    }

    "a whole-folder request is dispatched to decryptAll, with its collision policy" {
        runTest(dispatcher) {
            launcher.dispatched = false

            coordinator.start(folderRequest) shouldBe true
            advanceUntilIdle()

            engine.fileCalls.shouldBeEmpty()
            engine.folderCalls.single() shouldBe FakeRestoreEngine.FolderCall(
                sourceUri = folderRequest.sourceUri,
                outputUri = folderRequest.outputUri,
                password = folderRequest.password,
                collisionPolicy = RestoreCollisionPolicy.OVERWRITE,
            )
        }
    }

    "a single-file request is dispatched to decryptSingleFile" {
        runTest(dispatcher) {
            launcher.dispatched = false

            coordinator.start(fileRequest) shouldBe true
            advanceUntilIdle()

            engine.folderCalls.shouldBeEmpty()
            engine.fileCalls.single() shouldBe FakeRestoreEngine.FileCall(
                sourceFileUri = fileRequest.sourceFileUri,
                outputFileUri = fileRequest.outputFileUri,
                password = fileRequest.password,
            )
            coordinator.state.value.shouldBeInstanceOf<RestoreRunState.Finished>().mode shouldBe
                RestoreMode.SINGLE_FILE
        }
    }

    "engine progress is published into the run state, keeping the mode and the host flag" {
        runTest(dispatcher) {
            launcher.dispatched = false
            engine.holdRun()
            coordinator.start(folderRequest) shouldBe true
            advanceUntilIdle()
            coordinator.markHostedInForegroundService()

            engine.publishProgress(RestoreProgress.Files(total = 10, processed = 3, failed = 1, currentFileName = "a"))

            coordinator.state.value shouldBe RestoreRunState.Running(
                mode = RestoreMode.WHOLE_FOLDER,
                progress = RestoreProgress.Files(total = 10, processed = 3, failed = 1, currentFileName = "a"),
                hostedInForegroundService = true,
            )
            engine.finishRun()
            advanceUntilIdle()
        }
    }

    "marking a foreground host has no effect when no restore is in flight" {
        runTest(dispatcher) {
            coordinator.markHostedInForegroundService()

            coordinator.state.value shouldBe RestoreRunState.Idle
        }
    }

    "a stop request reaches the running engine through the shared run control" {
        runTest(dispatcher) {
            launcher.dispatched = false
            engine.holdRun()
            coordinator.start(folderRequest) shouldBe true
            advanceUntilIdle()

            coordinator.requestStop()

            // Cooperative: the engine is asked to stop, its coroutine is not cancelled.
            engine.runControlOfLastRun().shouldStop() shouldBe true
            engine.runCount shouldBe 1
            engine.finishRun()
            advanceUntilIdle()
        }
    }

    "the stop flag of a finished run does not leak into the next one" {
        runTest(dispatcher) {
            launcher.dispatched = false
            coordinator.start(folderRequest) shouldBe true
            coordinator.requestStop()
            advanceUntilIdle()
            coordinator.acknowledgeResult()

            coordinator.start(fileRequest) shouldBe true
            advanceUntilIdle()

            // The run control is one shared instance, so start() has to reset it — otherwise the
            // next restore would stop itself at its first stop check.
            engine.runControlOfLastRun().shouldStop() shouldBe false
            engine.fileCalls.size shouldBe 1
        }
    }

    "acknowledgeResult clears a finished result but leaves a running restore alone" {
        runTest(dispatcher) {
            launcher.dispatched = false
            engine.holdRun()
            coordinator.start(folderRequest) shouldBe true
            advanceUntilIdle()

            coordinator.acknowledgeResult()
            coordinator.state.value.shouldBeInstanceOf<RestoreRunState.Running>()

            engine.finishRun()
            advanceUntilIdle()
            coordinator.state.value.shouldBeInstanceOf<RestoreRunState.Finished>()

            coordinator.acknowledgeResult()
            coordinator.state.value shouldBe RestoreRunState.Idle
        }
    }

    "a finished run drops its staged request, so the password does not outlive it" {
        runTest(dispatcher) {
            launcher.dispatched = false
            coordinator.start(fileRequest) shouldBe true
            advanceUntilIdle()

            // Nothing left to claim — the request (and with it the backup password) is gone.
            coordinator.tryClaim() shouldBe false
            // And the screen may start the next restore even before it acknowledged this result.
            coordinator.start(folderRequest) shouldBe true
        }
    }
})

/** Mirrors the coordinator's private `FOREGROUND_HANDOVER_GRACE_MS`. */
private const val HANDOVER_GRACE_MS = 5_000L
private const val SECOND_MS = 1_000L

/**
 * Hand-written stand-in for [IRestoreEngine] (the project prefers a fake over a mock behind a
 * domain seam). A run can be held open with [holdRun] so a test can act while a restore is
 * genuinely in flight, publish progress from inside it with [publishProgress], and end either
 * normally or by throwing ([failWith]).
 */
private class FakeRestoreEngine : IRestoreEngine {

    data class FolderCall(
        val sourceUri: String,
        val outputUri: String,
        val password: String,
        val collisionPolicy: RestoreCollisionPolicy,
    )

    data class FileCall(val sourceFileUri: String, val outputFileUri: String, val password: String)

    val folderCalls = mutableListOf<FolderCall>()
    val fileCalls = mutableListOf<FileCall>()

    val runCount: Int get() = folderCalls.size + fileCalls.size

    /** Result of a run that is not [failWith]-ing. */
    var result: RestoreResult = RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0)

    /** Thrown out of the run instead of returning — models an unexpected engine failure. */
    var failWith: Throwable? = null

    private var gate: CompletableDeferred<Unit>? = null
    private var runControl: RestoreRunControl? = null
    private var onProgress: ((RestoreProgress) -> Unit)? = null

    /** Makes the next run suspend until [finishRun], simulating a long restore. */
    fun holdRun() {
        gate = CompletableDeferred()
    }

    fun finishRun() {
        gate?.complete(Unit)
    }

    /** The stop signal the coordinator handed to the run — one shared instance across runs. */
    fun runControlOfLastRun(): RestoreRunControl = requireNotNull(runControl) { "no run was started" }

    /** Publishes progress the way a real run does: from inside the engine call. */
    fun publishProgress(progress: RestoreProgress) {
        requireNotNull(onProgress) { "no run is in flight" }.invoke(progress)
    }

    override suspend fun scanSourceFolder(sourceUri: String): RestoreScanResult = RestoreScanResult(0, 0)

    override suspend fun decryptAll(
        sourceUri: String,
        outputUri: String,
        password: String,
        collisionPolicy: RestoreCollisionPolicy,
        runControl: RestoreRunControl,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        folderCalls.add(FolderCall(sourceUri, outputUri, password, collisionPolicy))
        return execute(runControl, onProgress)
    }

    override suspend fun decryptSingleFile(
        sourceFileUri: String,
        outputFileUri: String,
        password: String,
        runControl: RestoreRunControl,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        fileCalls.add(FileCall(sourceFileUri, outputFileUri, password))
        return execute(runControl, onProgress)
    }

    private suspend fun execute(
        runControl: RestoreRunControl,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        this.runControl = runControl
        this.onProgress = onProgress
        gate?.await()
        failWith?.let { throw it }
        return result
    }
}

/** Stand-in for the foreground-service launcher: records the starts and reports what a test wants. */
private class FakeForegroundRestoreLauncher : IForegroundRestoreLauncher {

    /** What `startForegroundService` reported — `false` models an outright refusal. */
    var dispatched: Boolean = true

    var startCount: Int = 0
        private set

    override fun start(): Boolean {
        startCount++
        return dispatched
    }
}
