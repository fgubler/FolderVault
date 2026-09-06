package ch.abwesend.foldervault.view

import androidx.lifecycle.SavedStateHandle
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.logging.ILogger
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.domain.restore.IForegroundRestoreLauncher
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreMode
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.domain.restore.RestoreRunControl
import ch.abwesend.foldervault.domain.restore.RestoreScanResult
import ch.abwesend.foldervault.infrastructure.restore.RestoreRunCoordinator
import ch.abwesend.foldervault.view.viewmodel.RestoreState
import ch.abwesend.foldervault.view.viewmodel.RestoreViewModel
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Records the arguments the ViewModel passes and replays a pre-set [singleFileResult]. Hand-written
 * fake (per the project convention of faking behind a domain seam rather than mocking). Setting
 * [gate] makes both restore calls suspend until the deferred completes, so a test can observe the
 * ViewModel while a restore is still in flight.
 */
private class FakeRestoreEngine(
    private val singleFileResult: RestoreResult = RestoreResult.Success(1, 0, 0, 0),
    private val gate: CompletableDeferred<Unit>? = null,
) : IRestoreEngine {
    var singleFileSourceUri: String? = null
    var singleFileOutputFileUri: String? = null
    var singleFilePassword: String? = null
    var singleFileCallCount = 0
    var decryptAllCallCount = 0
    var decryptAllPassword: String? = null

    override suspend fun scanSourceFolder(sourceUri: String): RestoreScanResult =
        RestoreScanResult(cryptFileCount = 0, otherFileCount = 0)

    @Suppress("LongParameterList")
    override suspend fun decryptAll(
        sourceUri: String,
        outputUri: String,
        password: String,
        collisionPolicy: RestoreCollisionPolicy,
        runControl: RestoreRunControl,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        decryptAllCallCount++
        decryptAllPassword = password
        gate?.await()
        // Honors the cooperative stop the way the real engine does, so a `cancel()` in a test
        // produces the same Cancelled result instead of a silently discarded run.
        return if (runControl.shouldStop()) {
            RestoreResult.Cancelled(0, 0, 0, 0)
        } else {
            RestoreResult.Success(0, 0, 0, 0)
        }
    }

    override suspend fun decryptSingleFile(
        sourceFileUri: String,
        outputFileUri: String,
        password: String,
    ): RestoreResult {
        singleFileCallCount++
        singleFileSourceUri = sourceFileUri
        singleFileOutputFileUri = outputFileUri
        singleFilePassword = password
        gate?.await()
        return singleFileResult
    }
}

/**
 * Records whether the foreground restore service was asked for, and replays [dispatched] — `false`
 * models the OS refusing the start, which must leave the run to the ViewModel itself.
 */
private class FakeForegroundRestoreLauncher(private val dispatched: Boolean = false) : IForegroundRestoreLauncher {
    var startCount = 0

    override fun start(): Boolean {
        startCount++
        return dispatched
    }
}

/** Builds the coordinator the way production does, with the seams a test can steer. */
private fun testCoordinator(
    engine: IRestoreEngine,
    launcher: IForegroundRestoreLauncher = FakeForegroundRestoreLauncher(),
    dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
): RestoreRunCoordinator = RestoreRunCoordinator(engine, launcher, testDispatchers(dispatcher))

private fun testDispatchers(dispatcher: CoroutineDispatcher): IDispatchers = object : IDispatchers {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
    override val mainImmediate = dispatcher
}

/**
 * Builds a ViewModel over a real [RestoreRunCoordinator] — it is pure logic over the engine and
 * launcher seams, so faking it would only re-implement the host handover this suite exercises.
 * The launcher defaults to "the service could not be dispatched", which makes the coordinator take
 * the run immediately instead of waiting out the service-handover grace period.
 */
private fun restoreViewModel(
    engine: IRestoreEngine,
    launcher: IForegroundRestoreLauncher = FakeForegroundRestoreLauncher(),
    dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
    coordinator: RestoreRunCoordinator = testCoordinator(engine, launcher, dispatcher),
    handle: SavedStateHandle = SavedStateHandle(),
): RestoreViewModel = RestoreViewModel(
    engine = engine,
    coordinator = coordinator,
    savedStateHandle = handle,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreViewModelTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val testDispatcher = UnconfinedTestDispatcher()

    beforeTest {
        Dispatchers.setMain(testDispatcher)
        LoggerProvider.configure { mockk<ILogger>(relaxed = true) }
    }
    afterTest {
        Dispatchers.resetMain()
    }

    "setMode switches mode and clears any prior selection" {
        val viewModel = restoreViewModel(FakeRestoreEngine())
        viewModel.setSourceFile("content://src", "report.pdf.crypt")

        viewModel.setMode(RestoreMode.SINGLE_FILE)

        val state = viewModel.uiState.value
        state.mode shouldBe RestoreMode.SINGLE_FILE
        state.state shouldBe RestoreState.Idle
        state.sourceFileUri shouldBe null
        state.sourceFileName shouldBe null
    }

    "setSourceFile records the file, suggests a decrypted name, and marks it ready" {
        val viewModel = restoreViewModel(FakeRestoreEngine())

        viewModel.setSourceFile("content://src", "sub/report.pdf.crypt")

        val state = viewModel.uiState.value
        state.sourceFileUri shouldBe "content://src"
        state.sourceFileName shouldBe "sub/report.pdf.crypt"
        state.suggestedOutputName shouldBe "report.pdf"
        state.state shouldBe RestoreState.SourceReady
    }

    "setSourceFile keeps a plain (non-crypt) name as the suggestion" {
        val viewModel = restoreViewModel(FakeRestoreEngine())

        viewModel.setSourceFile("content://src", "notes.txt")

        viewModel.uiState.value.suggestedOutputName shouldBe "notes.txt"
    }

    "startSingleFileRestore delegates the picked source, output and password to the engine" {
        val engine = FakeRestoreEngine()
        val viewModel = restoreViewModel(engine)
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("secret")

        viewModel.startSingleFileRestore("content://out")

        engine.singleFileSourceUri shouldBe "content://src"
        engine.singleFileOutputFileUri shouldBe "content://out"
        engine.singleFilePassword shouldBe "secret"
    }

    "startSingleFileRestore lands on Done(Success) on a successful decrypt" {
        val viewModel = restoreViewModel(FakeRestoreEngine(RestoreResult.Success(1, 0, 0, 0)))
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("secret")

        viewModel.startSingleFileRestore("content://out")

        val done = viewModel.uiState.value.state
        done.shouldBeInstanceOf<RestoreState.Done>()
        done.result shouldBe RestoreResult.Success(1, 0, 0, 0)
    }

    "startSingleFileRestore surfaces InvalidPassword as the result" {
        val viewModel = restoreViewModel(FakeRestoreEngine(RestoreResult.InvalidPassword))
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("wrong")

        viewModel.startSingleFileRestore("content://out")

        val done = viewModel.uiState.value.state
        done.shouldBeInstanceOf<RestoreState.Done>()
        done.result shouldBe RestoreResult.InvalidPassword
    }

    "startSingleFileRestore ignores a second start while a restore is running (review S2)" {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeRestoreEngine(gate = gate)
        val viewModel = restoreViewModel(engine)
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("secret")

        viewModel.startSingleFileRestore("content://out")
        viewModel.startSingleFileRestore("content://out2")
        gate.complete(Unit)

        engine.singleFileCallCount shouldBe 1
        engine.singleFileOutputFileUri shouldBe "content://out"
    }

    "startRestore ignores a second start while a restore is running (review S2)" {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeRestoreEngine(gate = gate)
        val viewModel = restoreViewModel(engine)
        viewModel.setSourceFolder("content://src")
        viewModel.setOutputFolder("content://out")

        viewModel.startRestore("secret")
        viewModel.startRestore("secret")
        gate.complete(Unit)

        engine.decryptAllCallCount shouldBe 1
    }

    "startRestore prefers the foreground service so leaving the app cannot kill the run" {
        val launcher = FakeForegroundRestoreLauncher(dispatched = true)
        val engine = FakeRestoreEngine()
        val viewModel = restoreViewModel(engine, launcher = launcher)
        viewModel.setSourceFolder("content://src")
        viewModel.setOutputFolder("content://out")

        viewModel.startRestore("secret")

        launcher.startCount shouldBe 1
    }

    "startRestore runs the folder restore itself when the service could not be started" {
        // The fallback path: the run still happens, just inside the app — which is what the
        // progress dialog's "keep the app open" warning is for.
        val engine = FakeRestoreEngine()
        val viewModel = restoreViewModel(engine, launcher = FakeForegroundRestoreLauncher(dispatched = false))
        viewModel.setSourceFolder("content://src")
        viewModel.setOutputFolder("content://out")

        viewModel.startRestore("secret")

        engine.decryptAllCallCount shouldBe 1
        engine.decryptAllPassword shouldBe "secret"
        viewModel.uiState.value.restoreHostedInForegroundService shouldBe false
    }

    "a run the foreground service took over is not also run by the in-app fallback" {
        // Only one host may execute a staged run; the coordinator's claim is what enforces it.
        // The launcher reports a dispatched service, so the fallback waits out its grace period
        // (which this scheduler never advances) instead of racing the service.
        val engine = FakeRestoreEngine()
        val coordinator = testCoordinator(engine, FakeForegroundRestoreLauncher(dispatched = true))
        val viewModel = restoreViewModel(engine, coordinator = coordinator)
        viewModel.setSourceFolder("content://src")
        viewModel.setOutputFolder("content://out")

        viewModel.startRestore("secret")
        // Stand in for the service: claim the staged run before the handover grace period ends.
        coordinator.tryClaim() shouldBe true
        coordinator.markHostedInForegroundService()

        engine.decryptAllCallCount shouldBe 0
        viewModel.uiState.value.restoreHostedInForegroundService shouldBe true
    }

    "cancelling a folder restore reports what was already restored instead of vanishing" {
        // The regression: the engine's Cancelled result used to be discarded by the cancelled
        // coroutine, so stopping a restore left the user with no result at all.
        val gate = CompletableDeferred<Unit>()
        val engine = FakeRestoreEngine(gate = gate)
        val viewModel = restoreViewModel(engine)
        viewModel.setSourceFolder("content://src")
        viewModel.setOutputFolder("content://out")

        viewModel.startRestore("secret")
        viewModel.cancel()
        gate.complete(Unit)

        val state = viewModel.uiState.value.state
        state.shouldBeInstanceOf<RestoreState.Done>()
        state.result shouldBe RestoreResult.Cancelled(0, 0, 0, 0)
    }

    "a folder restore result survives the screen being left and re-entered" {
        // The point of moving the run out of the ViewModel: a service-hosted restore outlives the
        // screen, so a ViewModel built later must show its state rather than starting from Idle.
        val engine = FakeRestoreEngine()
        val coordinator = testCoordinator(engine)
        val before = restoreViewModel(engine, coordinator = coordinator)
        before.setSourceFolder("content://src")
        before.setOutputFolder("content://out")
        before.startRestore("secret")

        val after = restoreViewModel(engine, coordinator = coordinator)

        val state = after.uiState.value.state
        state.shouldBeInstanceOf<RestoreState.Done>()
        state.result shouldBe RestoreResult.Success(0, 0, 0, 0)
    }

    "switching away from the folder mode and back re-attaches to the run in flight (review N13)" {
        // A StateFlow re-emits only on change, and a folder run publishes no progress at all while
        // it walks the source tree and probes the password — minutes, on a large backup. Without
        // pulling the coordinator's state on the way back, the screen showed an empty, fully
        // enabled form while the restore was running.
        val gate = CompletableDeferred<Unit>()
        val engine = FakeRestoreEngine(gate = gate)
        val viewModel = restoreViewModel(engine)
        viewModel.setSourceFolder("content://src")
        viewModel.setOutputFolder("content://out")
        viewModel.startRestore("secret")

        viewModel.setMode(RestoreMode.SINGLE_FILE)
        viewModel.setMode(RestoreMode.WHOLE_FOLDER)

        viewModel.uiState.value.state shouldBe RestoreState.Running
        gate.complete(Unit)
    }

    "a second start while one is running starts no second run and keeps showing the first (review N13)" {
        // `coordinator.start` refuses while a run is staged or running, and `startRestore` now
        // re-syncs the screen to that run instead of discarding the `false`. Since the `setMode`
        // fix above closed the only UI path that could reach the refusal with an out-of-sync
        // screen, what is left to pin here is the invariant itself: a refused start never starts a
        // second engine run, and never leaves the screen off the run that is actually in flight.
        val gate = CompletableDeferred<Unit>()
        val engine = FakeRestoreEngine(gate = gate)
        val viewModel = restoreViewModel(engine)
        viewModel.setSourceFolder("content://src")
        viewModel.setOutputFolder("content://out")
        viewModel.startRestore("secret")

        viewModel.startRestore("secret")

        viewModel.uiState.value.state shouldBe RestoreState.Running
        engine.decryptAllCallCount shouldBe 1
        gate.complete(Unit)
    }

    "a successful single-file restore clears the password from the state (review S3)" {
        val viewModel = restoreViewModel(FakeRestoreEngine(RestoreResult.Success(1, 0, 0, 0)))
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("secret")

        viewModel.startSingleFileRestore("content://out")

        viewModel.uiState.value.singleFilePassword shouldBe ""
    }

    "a failed single-file restore keeps the password so the user can correct it" {
        val viewModel = restoreViewModel(FakeRestoreEngine(RestoreResult.InvalidPassword))
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("almost-right")

        viewModel.startSingleFileRestore("content://out")

        viewModel.uiState.value.singleFilePassword shouldBe "almost-right"
    }

    "setSingleFilePassword keeps the password in the ui state" {
        val viewModel = restoreViewModel(FakeRestoreEngine())

        viewModel.setSingleFilePassword("secret")

        viewModel.uiState.value.singleFilePassword shouldBe "secret"
    }

    "setMode clears the single-file password" {
        val viewModel = restoreViewModel(FakeRestoreEngine())
        viewModel.setMode(RestoreMode.SINGLE_FILE)
        viewModel.setSingleFilePassword("secret")

        viewModel.setMode(RestoreMode.WHOLE_FOLDER)

        viewModel.uiState.value.singleFilePassword shouldBe ""
    }

    "setMode with the already-selected mode keeps the selection and password (stray tap, review B1)" {
        val viewModel = restoreViewModel(FakeRestoreEngine())
        viewModel.setMode(RestoreMode.SINGLE_FILE)
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("secret")

        viewModel.setMode(RestoreMode.SINGLE_FILE)

        val state = viewModel.uiState.value
        state.sourceFileUri shouldBe "content://src"
        state.sourceFileName shouldBe "report.pdf.crypt"
        state.singleFilePassword shouldBe "secret"
        state.state shouldBe RestoreState.SourceReady
    }

    "reset clears the single-file password" {
        val viewModel = restoreViewModel(FakeRestoreEngine())
        viewModel.setMode(RestoreMode.SINGLE_FILE)
        viewModel.setSingleFilePassword("secret")

        viewModel.reset()

        viewModel.uiState.value.singleFilePassword shouldBe ""
    }

    "reset keeps the selected mode so Start over does not flip flows" {
        val viewModel = restoreViewModel(FakeRestoreEngine())
        viewModel.setMode(RestoreMode.SINGLE_FILE)
        viewModel.setSourceFile("content://src", "report.pdf.crypt")

        viewModel.reset()

        val state = viewModel.uiState.value
        state.mode shouldBe RestoreMode.SINGLE_FILE
        state.state shouldBe RestoreState.Idle
        state.sourceFileUri shouldBe null
    }

    "startSingleFileRestore without a picked source does nothing" {
        val engine = FakeRestoreEngine()
        val viewModel = restoreViewModel(engine)

        viewModel.startSingleFileRestore("content://out")

        engine.singleFileSourceUri shouldBe null
        viewModel.uiState.value.state shouldBe RestoreState.Idle
    }

    "startSingleFileRestore declines to run with an empty password instead of destroying the target" {
        // After process death the picker result arrives at a recreated ViewModel whose (unsaved)
        // password is gone. Running anyway would truncate the picked output document and only then
        // fail the GCM tag check — wiping a pre-existing file the user chose to save over, with no
        // user action at all. The selection must survive so the user can re-enter the password.
        val handle = SavedStateHandle()
        val before = restoreViewModel(FakeRestoreEngine(), handle = handle)
        before.setMode(RestoreMode.SINGLE_FILE)
        before.setSourceFile("content://src", "report.pdf.crypt")
        val engine = FakeRestoreEngine()
        val afterProcessDeath = restoreViewModel(engine, handle = handle)

        afterProcessDeath.startSingleFileRestore("content://out")

        engine.singleFileCallCount shouldBe 0
        afterProcessDeath.uiState.value.state shouldBe RestoreState.SourceReady
    }

    "the mode and single-file selection survive process death via SavedStateHandle, the password does not" {
        val handle = SavedStateHandle()
        val before = restoreViewModel(FakeRestoreEngine(), handle = handle)
        before.setMode(RestoreMode.SINGLE_FILE)
        before.setSourceFile("content://src", "report.pdf.crypt")
        before.setSingleFilePassword("secret")

        // A new ViewModel over the same handle simulates recreation after process death.
        val after = restoreViewModel(FakeRestoreEngine(), handle = handle)

        val state = after.uiState.value
        state.mode shouldBe RestoreMode.SINGLE_FILE
        state.sourceFileUri shouldBe "content://src"
        state.sourceFileName shouldBe "report.pdf.crypt"
        state.suggestedOutputName shouldBe "report.pdf"
        state.state shouldBe RestoreState.SourceReady
        state.singleFilePassword shouldBe ""
    }

    "reset clears the persisted selection so process death cannot resurrect it" {
        val handle = SavedStateHandle()
        val before = restoreViewModel(FakeRestoreEngine(), handle = handle)
        before.setMode(RestoreMode.SINGLE_FILE)
        before.setSourceFile("content://src", "report.pdf.crypt")
        before.reset()

        val after = restoreViewModel(FakeRestoreEngine(), handle = handle)

        val state = after.uiState.value
        state.mode shouldBe RestoreMode.SINGLE_FILE
        state.sourceFileUri shouldBe null
        state.state shouldBe RestoreState.Idle
    }

    "setMode clears the persisted selection of the previous mode" {
        val handle = SavedStateHandle()
        val before = restoreViewModel(FakeRestoreEngine(), handle = handle)
        before.setMode(RestoreMode.SINGLE_FILE)
        before.setSourceFile("content://src", "report.pdf.crypt")
        before.setMode(RestoreMode.WHOLE_FOLDER)

        val after = restoreViewModel(FakeRestoreEngine(), handle = handle)

        val state = after.uiState.value
        state.mode shouldBe RestoreMode.WHOLE_FOLDER
        state.sourceFileUri shouldBe null
    }
})
