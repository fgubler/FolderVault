package ch.abwesend.foldervault.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import ch.abwesend.foldervault.view.viewmodel.RestoreViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Compose tests for the restore progress dialog's escape hatch (review S18).
 *
 * A modal `AlertDialog` consumes the back gesture, so an empty `onDismissRequest` pinned the user
 * to the restore screen for the whole run — up to an hour on a large backup, and the exact opposite
 * of what `RestoreForegroundService` exists for. Only the whole-folder run may be left behind: it
 * is owned by `IRestoreRunCoordinator` and survives the screen, while the single-file run is scoped
 * to the ViewModel and would be cancelled mid-write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestoreProgressDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val gate = CompletableDeferred<Unit>()
    private val engine = GatedRestoreEngine(gate)

    @Test
    fun `a folder restore the service could not take offers a way off the screen`() {
        val coordinator = coordinator()
        val viewModel = viewModel(coordinator)
        var backCalls = 0
        setRestoreScreen(viewModel) { backCalls++ }

        startFolderRestore(viewModel)

        composeTestRule.onNodeWithText("Leave this screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Leave this screen").performClick()
        assertTrue(backCalls == 1, "leaving the screen must navigate back, not swallow the tap")
    }

    @Test
    fun `a service-hosted folder restore is labelled as continuing in the background`() {
        val coordinator = coordinator()
        val viewModel = viewModel(coordinator)
        setRestoreScreen(viewModel) {}

        startFolderRestore(viewModel)
        coordinator.markHostedInForegroundService()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Continue in background").assertIsDisplayed()
    }

    @Test
    fun `a single-file restore may now also be left behind, since the coordinator hosts it too`() {
        // It used to run on `viewModelScope`, so leaving cancelled it mid-write and this dialog had
        // to stay blocking. Now it is coordinator-hosted like the folder flow and the same escape
        // hatch applies — which is the whole point of moving it.
        val viewModel = viewModel(coordinator())
        var backCalls = 0
        setRestoreScreen(viewModel) { backCalls++ }

        viewModel.setMode(RestoreMode.SINGLE_FILE)
        viewModel.setSourceFile("content://src", "report.pdf.crypt")
        viewModel.setSingleFilePassword("secret")
        viewModel.startSingleFileRestore("content://out")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Leave this screen").performClick()
        assertTrue(backCalls == 1, "leaving the screen must navigate back, not swallow the tap")
    }

    private fun startFolderRestore(viewModel: RestoreViewModel) {
        viewModel.setSourceFolder("content://src")
        viewModel.setOutputFolder("content://out")
        viewModel.startRestore("secret")
        composeTestRule.waitForIdle()
    }

    private fun setRestoreScreen(viewModel: RestoreViewModel, onBack: () -> Unit) {
        composeTestRule.setContent {
            FolderVaultTheme {
                RestoreScreen(onBack = onBack, viewModel = viewModel)
            }
        }
    }

    private fun coordinator(): RestoreRunCoordinator = RestoreRunCoordinator(
        engine = engine,
        foregroundLauncher = NoForegroundRestoreLauncher,
        dispatchers = UnconfinedDispatchers,
    )

    private fun viewModel(coordinator: RestoreRunCoordinator) = RestoreViewModel(
        engine = engine,
        coordinator = coordinator,
        savedStateHandle = SavedStateHandle(),
    )
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

/** Suspends both restore calls on [gate], so the dialog stays up while the test asserts on it. */
private class GatedRestoreEngine(private val gate: CompletableDeferred<Unit>) : IRestoreEngine {

    override suspend fun scanSourceFolder(sourceUri: String): RestoreScanResult =
        RestoreScanResult(cryptFileCount = 1, otherFileCount = 0)

    @Suppress("LongParameterList")
    override suspend fun decryptAll(
        sourceUri: String,
        outputUri: String,
        password: String,
        collisionPolicy: RestoreCollisionPolicy,
        runControl: RestoreRunControl,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        gate.await()
        return RestoreResult.Success(1, 0, 0, 0)
    }

    @Suppress("LongParameterList")
    override suspend fun decryptSingleFile(
        sourceFileUri: String,
        outputFileUri: String,
        password: String,
        runControl: RestoreRunControl,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreResult {
        gate.await()
        return RestoreResult.Success(1, 0, 0, 0)
    }
}
