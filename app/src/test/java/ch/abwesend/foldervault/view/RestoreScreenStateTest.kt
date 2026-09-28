package ch.abwesend.foldervault.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.restore.IForegroundRestoreLauncher
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreMode
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.domain.restore.RestoreRunControl
import ch.abwesend.foldervault.domain.restore.RestoreScanResult
import ch.abwesend.foldervault.infrastructure.restore.RestoreRunCoordinator
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.screens.RestoreScreen
import ch.abwesend.foldervault.view.viewmodel.RestoreState
import ch.abwesend.foldervault.view.viewmodel.RestoreViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for which sections the restore screen shows in which state, and for the hand-off
 * of a successful run to the success screen.
 *
 * Two user reports drive these. After a successful single-file restore the password field used to
 * come back *focused*, keyboard up, pushing the result text out of view: the field stayed mounted
 * under the modal progress dialog, and when the dialog closed, window focus fell back to it. Nothing
 * in the app requests focus, so the fix is structural — a success leaves this screen altogether
 * (`onRestoreSucceeded`) and resets the form, while a failure keeps the controls for a retry. And
 * while a picked backup folder was being scanned, the pick button stayed tappable and the only hint
 * was a muted one-liner, so the wait read as nothing happening.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestoreScreenStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val scanGate = CompletableDeferred<Unit>()
    private val decryptGate = CompletableDeferred<Unit>()
    private val engine = GatedRestoreEngine(scanGate = scanGate, decryptGate = decryptGate)

    private val successes = mutableListOf<Pair<RestoreMode, RestoreResult.Success>>()

    @Test
    fun `a successful single-file restore hands its result over and resets the form`() {
        val viewModel = viewModel()
        setRestoreScreen(viewModel)

        startSingleFileRestore(viewModel)
        decryptGate.complete(Unit)
        composeTestRule.waitForIdle()

        assertEquals(listOf(RestoreMode.SINGLE_FILE to SUCCESS), successes)
        assertEquals(RestoreState.Idle, viewModel.uiState.value.state)
        assertEquals(RestoreMode.SINGLE_FILE, viewModel.uiState.value.mode)
        composeTestRule.onNodeWithText(PASSWORD_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(DECRYPT_BUTTON).assertDoesNotExist()
        composeTestRule.onNodeWithText("The file was decrypted and saved.").assertDoesNotExist()
        composeTestRule.onNodeWithText(START_OVER_BUTTON).assertDoesNotExist()
    }

    @Test
    fun `a single-file restore with the wrong password keeps the password field for a retry`() {
        engine.decryptResult = RestoreResult.InvalidPassword
        val viewModel = viewModel()
        setRestoreScreen(viewModel)

        startSingleFileRestore(viewModel)
        decryptGate.complete(Unit)
        composeTestRule.waitForIdle()

        assertEquals(emptyList<Pair<RestoreMode, RestoreResult.Success>>(), successes)
        composeTestRule.onNodeWithText(PASSWORD_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithText(DECRYPT_BUTTON).assertIsDisplayed()
    }

    @Test
    fun `a successful folder restore hands its result over and resets the form`() {
        val viewModel = viewModel()
        setRestoreScreen(viewModel)

        scanGate.complete(Unit)
        viewModel.setSourceFolder("content://src")
        viewModel.setOutputFolder("content://out")
        viewModel.startRestore("secret")
        composeTestRule.waitForIdle()
        decryptGate.complete(Unit)
        composeTestRule.waitForIdle()

        assertEquals(listOf(RestoreMode.WHOLE_FOLDER to SUCCESS), successes)
        assertEquals(RestoreState.Idle, viewModel.uiState.value.state)
        composeTestRule.onNodeWithText(PASSWORD_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(START_RESTORE_BUTTON).assertDoesNotExist()
        composeTestRule.onNodeWithText("Found 1 encrypted file(s).").assertDoesNotExist()
        composeTestRule.onNodeWithText(START_OVER_BUTTON).assertDoesNotExist()
    }

    @Test
    fun `a failed folder restore keeps the password and start controls for a retry`() {
        engine.decryptResult = RestoreResult.InvalidPassword
        val viewModel = viewModel()
        setRestoreScreen(viewModel)

        scanGate.complete(Unit)
        viewModel.setSourceFolder("content://src")
        viewModel.setOutputFolder("content://out")
        viewModel.startRestore("secret")
        composeTestRule.waitForIdle()
        decryptGate.complete(Unit)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(PASSWORD_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithText(START_RESTORE_BUTTON).assertIsDisplayed()
    }

    @Test
    fun `picking a backup folder disables the pick button and shows the scanning status until the scan ends`() {
        val viewModel = viewModel()
        setRestoreScreen(viewModel)

        viewModel.setSourceFolder("content://src")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(PICK_FOLDER_BUTTON).assertIsNotEnabled()
        composeTestRule.onNodeWithText(SCANNING_STATUS).assertIsDisplayed()

        scanGate.complete(Unit)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(PICK_FOLDER_BUTTON).assertIsEnabled()
        composeTestRule.onNodeWithText(SCANNING_STATUS).assertDoesNotExist()
        composeTestRule.onNodeWithText("Found 1 encrypted file(s).").assertIsDisplayed()
    }

    private fun startSingleFileRestore(viewModel: RestoreViewModel) {
        viewModel.setMode(RestoreMode.SINGLE_FILE)
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("secret")
        viewModel.startSingleFileRestore("content://out")
        composeTestRule.waitForIdle()
    }

    private fun setRestoreScreen(viewModel: RestoreViewModel) {
        composeTestRule.setContent {
            FolderVaultTheme {
                RestoreScreen(
                    onBack = {},
                    onRestoreSucceeded = { mode, result -> successes += mode to result },
                    viewModel = viewModel,
                )
            }
        }
    }

    private fun viewModel() = RestoreViewModel(
        engine = engine,
        coordinator = RestoreRunCoordinator(
            engine = engine,
            foregroundLauncher = NoForegroundRestoreLauncher,
            dispatchers = UnconfinedDispatchers,
        ),
        savedStateHandle = SavedStateHandle(),
    )

    private companion object {
        val SUCCESS = RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0)
        const val PASSWORD_LABEL = "Backup password"
        const val DECRYPT_BUTTON = "Decrypt & save as…"
        const val START_RESTORE_BUTTON = "Start restore"
        const val START_OVER_BUTTON = "Start over (clear selection)"
        const val PICK_FOLDER_BUTTON = "Pick backup folder"
        const val SCANNING_STATUS = "Analyzing backup folder…"
    }
}

private object UnconfinedDispatchers : IDispatchers {
    override val default = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
    override val main = Dispatchers.Unconfined
    override val mainImmediate = Dispatchers.Unconfined
}

/** The OS refused the foreground service, so the coordinator's in-app fallback takes the run. */
private object NoForegroundRestoreLauncher : IForegroundRestoreLauncher {
    override fun start(): Boolean = false
}

/**
 * Suspends the scan on [scanGate] and both restore calls on [decryptGate], so the test can assert on
 * the intermediate state before letting each step finish. [decryptResult] is what both restore calls
 * answer once released.
 */
private class GatedRestoreEngine(
    private val scanGate: CompletableDeferred<Unit>,
    private val decryptGate: CompletableDeferred<Unit>,
) : IRestoreEngine {

    var decryptResult: RestoreResult = RestoreResult.Success(decrypted = 1, copied = 0, skipped = 0, failed = 0)

    override suspend fun scanSourceFolder(sourceUri: String): RestoreScanResult {
        scanGate.await()
        return RestoreScanResult(cryptFileCount = 1, otherFileCount = 0)
    }

    @Suppress("LongParameterList")
    override suspend fun decryptAll(
        sourceUri: String,
        outputUri: String,
        password: String,
        collisionPolicy: RestoreCollisionPolicy,
        runControl: RestoreRunControl,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        decryptGate.await()
        return decryptResult
    }

    @Suppress("LongParameterList")
    override suspend fun decryptSingleFile(
        sourceFileUri: String,
        outputFileUri: String,
        password: String,
        runControl: RestoreRunControl,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        decryptGate.await()
        return decryptResult
    }
}
