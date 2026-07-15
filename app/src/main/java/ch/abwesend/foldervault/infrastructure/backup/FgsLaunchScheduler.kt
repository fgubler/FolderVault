package ch.abwesend.foldervault.infrastructure.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import ch.abwesend.foldervault.domain.backup.IFgsLaunchScheduler
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sets a one-shot exact alarm a few seconds out whose receiver ([BackupAlarmReceiver]) starts
 * [BackupForegroundService]. See [IFgsLaunchScheduler] for why the exact-alarm hop is required.
 */
class FgsLaunchScheduler(
    private val context: Context,
) : IFgsLaunchScheduler {
    private val log get() = logger
    private val alarmManager: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    override fun scheduleImmediateLaunch(
        configId: String,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
    ): Boolean {
        val manager = alarmManager
        return when {
            manager == null -> {
                log.error("No AlarmManager available — cannot trampoline config $configId to the service")
                false
            }
            !manager.canScheduleExact() -> {
                log.warning("Exact alarms not permitted — config $configId must run inline instead")
                false
            }
            else -> setLaunchAlarm(manager, configId, networkPolicy, requiresCharging)
        }
    }

    override fun isExactAlarmPermitted(): Boolean = alarmManager?.canScheduleExact() ?: false

    private fun setLaunchAlarm(
        manager: AlarmManager,
        configId: String,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
    ): Boolean = try {
        val triggerAt = System.currentTimeMillis() + LAUNCH_DELAY.toMillis()
        manager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            launchPendingIntent(configId, networkPolicy, requiresCharging),
        )
        log.info("Scheduled FGS-launch trampoline for config $configId")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // e.g. SecurityException if the grant vanished between the check and the call.
        log.error("Failed to set FGS-launch trampoline for config $configId — running inline", e)
        false
    }

    override fun cancel(configId: String) {
        // Extras are irrelevant here (they never take part in PendingIntent matching); the per-config
        // data Uri and request code identify the alarm to cancel.
        alarmManager?.cancel(launchPendingIntent(configId, NetworkPolicy.WIFI_ONLY, requiresCharging = false))
        log.info("Cancelled any pending FGS-launch trampoline for config $configId")
    }

    /**
     * One [PendingIntent] per config. The per-config data Uri makes the intents distinct even if two
     * config-id hash codes collide on the request code, so [cancel] never hits the wrong alarm.
     */
    private fun launchPendingIntent(
        configId: String,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
    ): PendingIntent {
        val intent = Intent(context, BackupAlarmReceiver::class.java).apply {
            action = BackupAlarmReceiver.ACTION_LAUNCH
            data = "$LAUNCH_URI_SCHEME://$configId".toUri()
            putExtra(BackupForegroundService.EXTRA_CONFIG_ID, configId)
            putExtra(BackupForegroundService.EXTRA_NETWORK_POLICY, networkPolicy.name)
            putExtra(BackupForegroundService.EXTRA_REQUIRES_CHARGING, requiresCharging)
        }
        return PendingIntent.getBroadcast(
            context,
            configId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** On API < 31 exact alarms need no permission; on 31+ the user grant is required. */
    private fun AlarmManager.canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms()

    companion object {
        /** Small offset so the alarm fires from its own (launch-exempt) callback, not the worker. */
        private val LAUNCH_DELAY: Duration = Duration.ofSeconds(10)
        private const val LAUNCH_URI_SCHEME = "foldervault-fgs-launch"
    }
}
