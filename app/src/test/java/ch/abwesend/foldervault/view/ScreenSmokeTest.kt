package ch.abwesend.folderVault.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.screens.OnboardingScreen
import ch.abwesend.foldervault.view.viewmodel.OnboardingViewModel
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

    private class FakeAppSettingsRepository : IAppSettingsRepository {
        private val _settings = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = _settings
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            _settings.value = transform(_settings.value)
        }
    }
}
