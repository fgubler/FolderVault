package ch.abwesend.foldervault.view.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.view.screens.AddEditBackupScreen
import ch.abwesend.foldervault.view.screens.BackupDetailScreen
import ch.abwesend.foldervault.view.screens.HomeScreen
import ch.abwesend.foldervault.view.screens.OnboardingScreen
import ch.abwesend.foldervault.view.screens.RestoreScreen
import ch.abwesend.foldervault.view.screens.SettingsScreen
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

@Composable
fun AppNavGraph(
    startDestination: AppDestination = AppDestination.Home,
    settingsRepo: IAppSettingsRepository = koinInject(),
) {
    val backStack = rememberNavBackStack(startDestination)
    var hasAutoShownOnboarding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasAutoShownOnboarding && startDestination == AppDestination.Home) {
            hasAutoShownOnboarding = true
            if (settingsRepo.settings.first().showOnboarding) {
                backStack.add(AppDestination.Onboarding)
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = { key ->
            when (key) {
                is AppDestination.Onboarding -> NavEntry(key) {
                    OnboardingScreen(
                        onComplete = {
                            backStack.removeLastOrNull()
                            if (backStack.isEmpty()) backStack.add(AppDestination.Home)
                        },
                    )
                }
                is AppDestination.Home -> NavEntry(key) {
                    HomeScreen(
                        onOpenSettings = { backStack.add(AppDestination.Settings) },
                        onAddBackup = { backStack.add(AppDestination.AddEditBackup()) },
                        onOpenDetail = { configId -> backStack.add(AppDestination.BackupDetail(configId)) },
                        onOpenRestore = { backStack.add(AppDestination.Restore) },
                    )
                }
                is AppDestination.Settings -> NavEntry(key) {
                    SettingsScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onShowOnboarding = { backStack.add(AppDestination.Onboarding) },
                    )
                }
                is AppDestination.BackupDetail -> NavEntry(key) {
                    BackupDetailScreen(
                        configId = key.configId,
                        onBack = { backStack.removeLastOrNull() },
                        onEdit = { backStack.add(AppDestination.AddEditBackup(key.configId)) },
                        onDelete = { backStack.removeLastOrNull() },
                    )
                }
                is AppDestination.AddEditBackup -> NavEntry(key) {
                    AddEditBackupScreen(
                        configId = key.configId,
                        onBack = { backStack.removeLastOrNull() },
                        onSave = { backStack.removeLastOrNull() },
                    )
                }
                is AppDestination.Restore -> NavEntry(key) {
                    RestoreScreen(onBack = { backStack.removeLastOrNull() })
                }
                else -> error("Unknown destination: $key")
            }
        },
    )
}
