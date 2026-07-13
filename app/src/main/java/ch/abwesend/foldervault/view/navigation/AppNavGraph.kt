package ch.abwesend.foldervault.view.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.view.screens.AddEditBackupScreen
import ch.abwesend.foldervault.view.screens.BackupDetailScreen
import ch.abwesend.foldervault.view.screens.BackupRunHistoryScreen
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
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
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
                        autoStartBackup = key.autoStartBackup,
                        onBack = { backStack.removeLastOrNull() },
                        onEdit = { backStack.add(AppDestination.AddEditBackup(key.configId)) },
                        onDelete = { backStack.removeLastOrNull() },
                        onShowRunHistory = { backStack.add(AppDestination.BackupRunHistory(key.configId)) },
                    )
                }
                is AppDestination.BackupRunHistory -> NavEntry(key) {
                    BackupRunHistoryScreen(
                        configId = key.configId,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                is AppDestination.AddEditBackup -> NavEntry(key) {
                    AddEditBackupScreen(
                        configId = key.configId,
                        onBack = { backStack.removeLastOrNull() },
                        onSave = { configId, isNewConfig ->
                            backStack.removeLastOrNull()
                            // A freshly created config lands on its detail screen, which starts
                            // the initial upload (foreground service) after the usual prompts.
                            if (isNewConfig) {
                                backStack.add(AppDestination.BackupDetail(configId, autoStartBackup = true))
                            }
                        },
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
