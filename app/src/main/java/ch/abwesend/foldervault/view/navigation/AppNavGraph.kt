package ch.abwesend.foldervault.view.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import ch.abwesend.foldervault.view.screens.HomeScreen
import ch.abwesend.foldervault.view.screens.OnboardingScreen
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
                    OnboardingScreen(onComplete = { backStack.add(AppDestination.Home) })
                }
                is AppDestination.Home -> NavEntry(key) {
                    HomeScreen(onOpenSettings = { backStack.add(AppDestination.Settings) })
                }
                is AppDestination.Settings -> NavEntry(key) {
                    SettingsScreen(onBack = { backStack.removeLastOrNull() })
                }
                else -> error("Unknown destination: $key")
            }
        },
    )
}
