package ch.abwesend.foldervault.view.navigation

import androidx.navigation3.runtime.NavKey
import ch.abwesend.foldervault.domain.restore.RestoreMode
import ch.abwesend.foldervault.domain.restore.RestoreResult
import kotlinx.serialization.Serializable

sealed interface AppDestination : NavKey {
    @Serializable data object Onboarding : AppDestination

    @Serializable data object Home : AppDestination

    @Serializable data object Settings : AppDestination

    @Serializable data class BackupDetail(
        val configId: String,
        /** Auto-starts the initial upload once — set when arriving from creating the config. */
        val autoStartBackup: Boolean = false,
    ) : AppDestination

    @Serializable data class BackupRunHistory(val configId: String) : AppDestination

    @Serializable data class AddEditBackup(val configId: String? = null) : AppDestination

    @Serializable data object Restore : AppDestination

    /**
     * Landing page of a restore that succeeded. Carries the result's counters rather than the
     * domain `RestoreResult` so the key stays a plain serializable value for the back stack.
     */
    @Serializable data class RestoreSuccess(
        val mode: RestoreMode,
        val decrypted: Int,
        val copied: Int,
        val skipped: Int,
        val failed: Int,
    ) : AppDestination {
        val result: RestoreResult.Success
            get() = RestoreResult.Success(decrypted = decrypted, copied = copied, skipped = skipped, failed = failed)

        companion object {
            fun of(mode: RestoreMode, result: RestoreResult.Success) = RestoreSuccess(
                mode = mode,
                decrypted = result.decrypted,
                copied = result.copied,
                skipped = result.skipped,
                failed = result.failed,
            )
        }
    }
}
