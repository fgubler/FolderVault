package ch.abwesend.foldervault.infrastructure.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import org.koin.mp.KoinPlatform
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fires from the one-shot exact alarm set by [FgsLaunchScheduler] and starts
 * [BackupForegroundService]. Being an *exact-alarm callback* is exactly what exempts this start
 * from Android 12+'s background foreground-service-start restriction.
 *
 * Registered not-exported in the manifest, so only our own [android.app.PendingIntent] can trigger
 * it. Should the start be rejected anyway (e.g. the dataSync time budget is exhausted, throwing
 * `ForegroundServiceStartNotAllowedException`), the run degrades to a WorkManager one-time run
 * instead of crashing — the same graceful fallback as [ForegroundBackupLauncher].
 */
class BackupAlarmReceiver : BroadcastReceiver() {
    private val log get() = logger

    /**
     * Resolved lazily (not `injectAnywhere()`), so constructing this receiver never touches Koin.
     * Robolectric instantiates manifest-registered receivers while creating the `Application` —
     * before any test can start Koin — and an eager lookup there would crash app setup.
     */
    private val scheduler: IBackupScheduler by lazy { KoinPlatform.getKoin().get() }

    override fun onReceive(context: Context, intent: Intent) {
        val configId = intent.getStringExtra(BackupForegroundService.EXTRA_CONFIG_ID)
        val networkPolicy = intent.getStringExtra(BackupForegroundService.EXTRA_NETWORK_POLICY)
            ?.let { runCatching { NetworkPolicy.valueOf(it) }.getOrNull() }
            ?: NetworkPolicy.WIFI_ONLY
        val requiresCharging = intent.getBooleanExtra(BackupForegroundService.EXTRA_REQUIRES_CHARGING, false)
        if (configId == null) {
            log.error("FGS-launch alarm fired without a config id — ignoring")
        } else {
            launchService(context, configId, networkPolicy, requiresCharging)
        }
    }

    private fun launchService(
        context: Context,
        configId: String,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
    ) {
        try {
            val serviceIntent = Intent(context, BackupForegroundService::class.java).apply {
                putExtra(BackupForegroundService.EXTRA_CONFIG_ID, configId)
                putExtra(BackupForegroundService.EXTRA_NETWORK_POLICY, networkPolicy.name)
                putExtra(BackupForegroundService.EXTRA_REQUIRES_CHARGING, requiresCharging)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            log.info("FGS-launch alarm started the foreground backup service for config $configId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("FGS-launch alarm could not start the service for $configId — falling back to WorkManager", e)
            // forceInline: this run must not trampoline back to the service (which just failed to
            // start), or it would loop until the dataSync time budget resets.
            scheduler.scheduleOneTime(configId, networkPolicy, requiresCharging, forceInline = true)
        }
    }

    companion object {
        const val ACTION_LAUNCH = "ch.abwesend.foldervault.action.FGS_LAUNCH_BACKUP"
    }
}
