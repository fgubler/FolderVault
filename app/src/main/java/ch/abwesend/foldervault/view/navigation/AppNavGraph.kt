package ch.abwesend.foldervault.view.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import ch.abwesend.foldervault.view.screens.AddEditBackupScreen
import ch.abwesend.foldervault.view.screens.BackupDetailScreen
import ch.abwesend.foldervault.view.screens.HomeScreen
import ch.abwesend.foldervault.view.screens.OnboardingScreen
import ch.abwesend.foldervault.view.screens.RestoreScreen
import ch.abwesend.foldervault.view.screens.SettingsScreen

@Composable
fun AppNavGraph(startDestination: AppDestination = AppDestination.Home) {
    val backStack = rememberNavBackStack(startDestination)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = { key ->
            when (key) {
                is AppDestination.Onboarding -> NavEntry(key) {
                    OnboardingScreen(
                        onComplete = {
                            backStack.removeLastOrNull()
                            backStack.add(AppDestination.Home)
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
