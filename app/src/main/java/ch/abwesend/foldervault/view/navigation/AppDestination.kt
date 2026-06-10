package ch.abwesend.foldervault.view.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed interface AppDestination : NavKey {
    @Serializable data object Onboarding : AppDestination

    @Serializable data object Home : AppDestination

    @Serializable data object Settings : AppDestination

    @Serializable data class BackupDetail(val configId: String) : AppDestination

    @Serializable data class AddEditBackup(val configId: String? = null) : AppDestination
}
