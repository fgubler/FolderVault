package ch.abwesend.foldervault.view

import androidx.lifecycle.SavedStateHandle
import ch.abwesend.foldervault.domain.logging.ILogger
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreMode
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.domain.restore.RestoreScanResult
import ch.abwesend.foldervault.view.viewmodel.RestoreState
import ch.abwesend.foldervault.view.viewmodel.RestoreViewModel
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
    var singleFileOutputFolderUri: String? = null
    var singleFileOutputName: String? = null
    var singleFilePassword: String? = null
    var singleFileCallCount = 0
    var decryptAllCallCount = 0

    override suspend fun scanSourceFolder(sourceUri: String): RestoreScanResult =
        RestoreScanResult(cryptFileCount = 0, otherFileCount = 0)

    override suspend fun decryptAll(
        sourceUri: String,
        outputUri: String,
        password: String,
        collisionPolicy: RestoreCollisionPolicy,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        decryptAllCallCount++
        gate?.await()
        return RestoreResult.Success(0, 0, 0, 0)
    }

    override suspend fun decryptSingleFile(
        sourceFileUri: String,
        outputFolderUri: String,
        outputFileName: String,
        password: String,
    ): RestoreResult {
        singleFileCallCount++
        singleFileSourceUri = sourceFileUri
        singleFileOutputFolderUri = outputFolderUri
        singleFileOutputName = outputFileName
        singleFilePassword = password
        gate?.await()
        return singleFileResult
    }
}

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
        val viewModel = RestoreViewModel(FakeRestoreEngine(), SavedStateHandle())
        viewModel.setSourceFile("content://src", "report.pdf.crypt")

        viewModel.setMode(RestoreMode.SINGLE_FILE)

        val state = viewModel.uiState.value
        state.mode shouldBe RestoreMode.SINGLE_FILE
        state.state shouldBe RestoreState.Idle
        state.sourceFileUri shouldBe null
        state.sourceFileName shouldBe null
    }

    "setSourceFile records the file, suggests a decrypted name, and marks it ready" {
        val viewModel = RestoreViewModel(FakeRestoreEngine(), SavedStateHandle())

        viewModel.setSourceFile("content://src", "sub/report.pdf.crypt")

        val state = viewModel.uiState.value
        state.sourceFileUri shouldBe "content://src"
        state.sourceFileName shouldBe "sub/report.pdf.crypt"
        state.suggestedOutputName shouldBe "report.pdf"
        state.state shouldBe RestoreState.SourceReady
    }

    "setSourceFile keeps a plain (non-crypt) name as the suggestion" {
        val viewModel = RestoreViewModel(FakeRestoreEngine(), SavedStateHandle())

        viewModel.setSourceFile("content://src", "notes.txt")

        viewModel.uiState.value.suggestedOutputName shouldBe "notes.txt"
    }

    "startSingleFileRestore delegates the picked source, output and password to the engine" {
        val engine = FakeRestoreEngine()
        val viewModel = RestoreViewModel(engine, SavedStateHandle())
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("secret")

        viewModel.startSingleFileRestore("content://out")

        engine.singleFileSourceUri shouldBe "content://src"
        engine.singleFileOutputFolderUri shouldBe "content://out"
        engine.singleFileOutputName shouldBe "report.pdf"
        engine.singleFilePassword shouldBe "secret"
    }

    "startSingleFileRestore lands on Done(Success) on a successful decrypt" {
        val viewModel = RestoreViewModel(FakeRestoreEngine(RestoreResult.Success(1, 0, 0, 0)), SavedStateHandle())
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("secret")

        viewModel.startSingleFileRestore("content://out")

        val done = viewModel.uiState.value.state
        done.shouldBeInstanceOf<RestoreState.Done>()
        done.result shouldBe RestoreResult.Success(1, 0, 0, 0)
    }

    "startSingleFileRestore surfaces InvalidPassword as the result" {
        val viewModel = RestoreViewModel(FakeRestoreEngine(RestoreResult.InvalidPassword), SavedStateHandle())
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
        val viewModel = RestoreViewModel(engine, SavedStateHandle())
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("secret")

        viewModel.startSingleFileRestore("content://out")
        viewModel.startSingleFileRestore("content://out2")
        gate.complete(Unit)

        engine.singleFileCallCount shouldBe 1
        engine.singleFileOutputFolderUri shouldBe "content://out"
    }

    "startRestore ignores a second start while a restore is running (review S2)" {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeRestoreEngine(gate = gate)
        val viewModel = RestoreViewModel(engine, SavedStateHandle())
        viewModel.setSourceFolder("content://src")
        viewModel.setOutputFolder("content://out")

        viewModel.startRestore("secret")
        viewModel.startRestore("secret")
        gate.complete(Unit)

        engine.decryptAllCallCount shouldBe 1
    }

    "a successful single-file restore clears the password from the state (review S3)" {
        val viewModel = RestoreViewModel(FakeRestoreEngine(RestoreResult.Success(1, 0, 0, 0)), SavedStateHandle())
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("secret")

        viewModel.startSingleFileRestore("content://out")

        viewModel.uiState.value.singleFilePassword shouldBe ""
    }

    "a failed single-file restore keeps the password so the user can correct it" {
        val viewModel = RestoreViewModel(FakeRestoreEngine(RestoreResult.InvalidPassword), SavedStateHandle())
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("almost-right")

        viewModel.startSingleFileRestore("content://out")

        viewModel.uiState.value.singleFilePassword shouldBe "almost-right"
    }

    "setSingleFilePassword keeps the password in the ui state" {
        val viewModel = RestoreViewModel(FakeRestoreEngine(), SavedStateHandle())

        viewModel.setSingleFilePassword("secret")

        viewModel.uiState.value.singleFilePassword shouldBe "secret"
    }

    "setMode clears the single-file password" {
        val viewModel = RestoreViewModel(FakeRestoreEngine(), SavedStateHandle())
        viewModel.setMode(RestoreMode.SINGLE_FILE)
        viewModel.setSingleFilePassword("secret")

        viewModel.setMode(RestoreMode.WHOLE_FOLDER)

        viewModel.uiState.value.singleFilePassword shouldBe ""
    }

    "setMode with the already-selected mode keeps the selection and password (stray tap, review B1)" {
        val viewModel = RestoreViewModel(FakeRestoreEngine(), SavedStateHandle())
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
        val viewModel = RestoreViewModel(FakeRestoreEngine(), SavedStateHandle())
        viewModel.setMode(RestoreMode.SINGLE_FILE)
        viewModel.setSingleFilePassword("secret")

        viewModel.reset()

        viewModel.uiState.value.singleFilePassword shouldBe ""
    }

    "reset keeps the selected mode so Start over does not flip flows" {
        val viewModel = RestoreViewModel(FakeRestoreEngine(), SavedStateHandle())
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
        val viewModel = RestoreViewModel(engine, SavedStateHandle())

        viewModel.startSingleFileRestore("content://out")

        engine.singleFileSourceUri shouldBe null
        viewModel.uiState.value.state shouldBe RestoreState.Idle
    }

    "the mode and single-file selection survive process death via SavedStateHandle, the password does not" {
        val handle = SavedStateHandle()
        val before = RestoreViewModel(FakeRestoreEngine(), handle)
        before.setMode(RestoreMode.SINGLE_FILE)
        before.setSourceFile("content://src", "report.pdf.crypt")
        before.setSingleFilePassword("secret")

        // A new ViewModel over the same handle simulates recreation after process death.
        val after = RestoreViewModel(FakeRestoreEngine(), handle)

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
        val before = RestoreViewModel(FakeRestoreEngine(), handle)
        before.setMode(RestoreMode.SINGLE_FILE)
        before.setSourceFile("content://src", "report.pdf.crypt")
        before.reset()

        val after = RestoreViewModel(FakeRestoreEngine(), handle)

        val state = after.uiState.value
        state.mode shouldBe RestoreMode.SINGLE_FILE
        state.sourceFileUri shouldBe null
        state.state shouldBe RestoreState.Idle
    }

    "setMode clears the persisted selection of the previous mode" {
        val handle = SavedStateHandle()
        val before = RestoreViewModel(FakeRestoreEngine(), handle)
        before.setMode(RestoreMode.SINGLE_FILE)
        before.setSourceFile("content://src", "report.pdf.crypt")
        before.setMode(RestoreMode.WHOLE_FOLDER)

        val after = RestoreViewModel(FakeRestoreEngine(), handle)

        val state = after.uiState.value
        state.mode shouldBe RestoreMode.WHOLE_FOLDER
        state.sourceFileUri shouldBe null
    }
})
