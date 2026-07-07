package ch.abwesend.foldervault.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.database.IDatabaseRecoveryService
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.screens.DatabaseErrorScreen
import ch.abwesend.foldervault.view.screens.OnboardingScreen
import ch.abwesend.foldervault.view.viewmodel.DatabaseGuardViewModel
import ch.abwesend.foldervault.view.viewmodel.OnboardingViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScreenSmokeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `onboarding screen renders the first page title`() {
        val settingsRepo = FakeAppSettingsRepository()
        val viewModel = OnboardingViewModel(settingsRepo)

        composeTestRule.setContent {
            FolderVaultTheme {
                OnboardingScreen(
                    onComplete = {},
                    viewModel = viewModel,
                )
            }
        }

        composeTestRule
            .onNodeWithText("Incremental folder backup")
            .assertIsDisplayed()
    }

    @Test
    fun `onboarding screen shows Skip button on the first page`() {
        val settingsRepo = FakeAppSettingsRepository()
        val viewModel = OnboardingViewModel(settingsRepo)

        composeTestRule.setContent {
            FolderVaultTheme {
                OnboardingScreen(
                    onComplete = {},
                    viewModel = viewModel,
                )
            }
        }

        composeTestRule.onNodeWithText("Skip").assertIsDisplayed()
    }

    @Test
    fun `database error screen renders title and recovery options`() {
        val recoveryService = mockk<IDatabaseRecoveryService> {
            coEvery { verifyDatabaseHealth() } returns ErrorResult(IllegalStateException("boom"))
        }
        val viewModel = DatabaseGuardViewModel(
            recoveryService = recoveryService,
            logExporter = mockk(relaxed = true),
            dispatchers = unconfinedDispatchers,
        )

        composeTestRule.setContent {
            FolderVaultTheme {
                DatabaseErrorScreen(viewModel = viewModel)
            }
        }

        composeTestRule
            .onNodeWithText("There is a problem with the app's database")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Reset database").assertIsDisplayed()
        composeTestRule.onNodeWithText("Export today's logfile").assertIsDisplayed()
    }

    @Test
    fun `database error screen asks for confirmation before resetting`() {
        val recoveryService = mockk<IDatabaseRecoveryService> {
            coEvery { verifyDatabaseHealth() } returns ErrorResult(IllegalStateException("boom"))
            coEvery { resetDatabase() } returns SuccessResult(Unit)
        }
        val viewModel = DatabaseGuardViewModel(
            recoveryService = recoveryService,
            logExporter = mockk(relaxed = true),
            dispatchers = unconfinedDispatchers,
        )

        composeTestRule.setContent {
            FolderVaultTheme {
                DatabaseErrorScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithText("Reset database").performClick()

        composeTestRule.onNodeWithText("Reset the database?").assertIsDisplayed()
        coVerify(exactly = 0) { recoveryService.resetDatabase() }

        composeTestRule.onNodeWithText("Delete and reset").performClick()
        coVerify(exactly = 1) { recoveryService.resetDatabase() }
    }

    private val unconfinedDispatchers = object : IDispatchers {
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val main = Dispatchers.Unconfined
        override val mainImmediate = Dispatchers.Unconfined
    }

    private class FakeAppSettingsRepository : IAppSettingsRepository {
        private val _settings = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = _settings
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            _settings.value = transform(_settings.value)
        }
    }
}
