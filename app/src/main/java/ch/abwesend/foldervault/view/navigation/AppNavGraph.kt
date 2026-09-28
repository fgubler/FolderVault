package ch.abwesend.foldervault.view.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
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
import ch.abwesend.foldervault.view.screens.RestoreSuccessScreen
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
        entryProvider = { key -> backStack.entryFor(key) },
    )
}

/** Maps a destination [key] to its screen. Kept out of [AppNavGraph] to keep that function short. */
private fun NavBackStack<NavKey>.entryFor(key: NavKey): NavEntry<NavKey> = when (key) {
    is AppDestination.Onboarding -> NavEntry(key) {
        OnboardingScreen(
            onComplete = {
                removeLastOrNull()
                if (isEmpty()) add(AppDestination.Home)
            },
        )
    }
    is AppDestination.Home -> NavEntry(key) {
        HomeScreen(
            onOpenSettings = { add(AppDestination.Settings) },
            onAddBackup = { add(AppDestination.AddEditBackup()) },
            onOpenDetail = { configId -> add(AppDestination.BackupDetail(configId)) },
            onOpenRestore = { add(AppDestination.Restore) },
        )
    }
    is AppDestination.Settings -> NavEntry(key) {
        SettingsScreen(
            onBack = { removeLastOrNull() },
            onShowOnboarding = { add(AppDestination.Onboarding) },
        )
    }
    is AppDestination.BackupDetail -> NavEntry(key) {
        BackupDetailScreen(
            configId = key.configId,
            autoStartBackup = key.autoStartBackup,
            onBack = { removeLastOrNull() },
            onEdit = { add(AppDestination.AddEditBackup(key.configId)) },
            onDelete = { removeLastOrNull() },
            onShowRunHistory = { add(AppDestination.BackupRunHistory(key.configId)) },
        )
    }
    is AppDestination.BackupRunHistory -> NavEntry(key) {
        BackupRunHistoryScreen(
            configId = key.configId,
            onBack = { removeLastOrNull() },
        )
    }
    is AppDestination.AddEditBackup -> NavEntry(key) {
        AddEditBackupScreen(
            configId = key.configId,
            onBack = { removeLastOrNull() },
            onSave = { configId, isNewConfig ->
                removeLastOrNull()
                // A freshly created config lands on its detail screen, which starts
                // the initial upload (foreground service) after the usual prompts.
                if (isNewConfig) {
                    add(AppDestination.BackupDetail(configId, autoStartBackup = true))
                }
            },
        )
    }
    is AppDestination.Restore -> NavEntry(key) {
        RestoreScreen(
            onBack = { removeLastOrNull() },
            onRestoreSuccess = { mode, result ->
                add(AppDestination.RestoreSuccess.of(mode, result))
            },
        )
    }
    is AppDestination.RestoreSuccess -> NavEntry(key) {
        RestoreSuccessScreen(
            mode = key.mode,
            result = key.result,
            // The restore form below was reset when it handed over, so popping back
            // to it is "restore another": same mode, clean selection.
            onRestoreAnother = { removeLastOrNull() },
            onFinish = { returnHome() },
        )
    }
    else -> error("Unknown destination: $key")
}

/**
 * Pops everything above the home screen, or starts a fresh one when the stack began elsewhere
 * (a notification deep link opens straight on a backup's detail screen).
 */
private fun NavBackStack<NavKey>.returnHome() {
    removeAll { it != AppDestination.Home }
    if (isEmpty()) add(AppDestination.Home)
}
