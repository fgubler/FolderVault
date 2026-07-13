package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.backup.IForegroundBackupLauncher
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import kotlin.coroutines.cancellation.CancellationException

/**
 * Starts [BackupForegroundService]. All call sites are foreground UI actions, so the FGS start
 * is normally allowed; should the OS reject it anyway (edge cases around backgrounding at the
 * exact wrong moment), the run degrades gracefully to a WorkManager one-time run instead of
 * being lost.
 */
class ForegroundBackupLauncher(
    private val context: Context,
    private val scheduler: IBackupScheduler,
) : IForegroundBackupLauncher {
    private val log get() = logger

    override fun start(configId: String, networkPolicy: NetworkPolicy, requiresCharging: Boolean) {
        try {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                putExtra(BackupForegroundService.EXTRA_CONFIG_ID, configId)
                putExtra(BackupForegroundService.EXTRA_NETWORK_POLICY, networkPolicy.name)
                putExtra(BackupForegroundService.EXTRA_REQUIRES_CHARGING, requiresCharging)
            }
            ContextCompat.startForegroundService(context, intent)
            log.info("Started foreground backup service for config $configId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Could not start foreground backup service for $configId — falling back to WorkManager", e)
            scheduler.scheduleOneTime(configId, networkPolicy, requiresCharging)
        }
    }
}
