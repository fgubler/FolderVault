package ch.abwesend.foldervault

import android.app.Application
import ch.abwesend.foldervault.di.appModule
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.logging.ITelemetryToggle
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.result.rethrowCancellation
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.infrastructure.backup.BackupNotificationManager
import ch.abwesend.foldervault.infrastructure.logging.CrashlyticsSink
import ch.abwesend.foldervault.infrastructure.logging.LocalLogSink
import ch.abwesend.foldervault.infrastructure.logging.PrivateLogger
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupRunDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class FolderVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        configureLogging()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@FolderVaultApp)
            modules(appModule)
        }
        get<BackupNotificationManager>().createNotificationChannels()
        applyInitialTelemetrySettings()
        sweepStaleRunningBackupRuns()
        reRegisterPeriodicBackups()
    }

    /**
     * Re-registers periodic WorkManager jobs for every non-paused backup config on app start.
     * Safety net: the schedule is only ever (re-)registered from the config save / resume paths,
     * so any event that clears a config's periodic work without going through those paths — a
     * REPLACE enqueue that landed on the shared unique name in older builds, WorkManager dropping
     * work after a force-stop, etc. — would otherwise leave the backup silently unscheduled until
     * the user next edits it. [IBackupScheduler.schedulePeriodicIfNeeded] uses
     * [androidx.work.ExistingPeriodicWorkPolicy.UPDATE], so this is idempotent for configs that
     * are already scheduled; for `MANUAL_ONLY` ones it cancels only the periodic slot, leaving
     * pending one-time runs, continuations, and charging-only fallbacks untouched.
     *
     * Must not crash on a broken database — like [sweepStaleRunningBackupRuns], this is an early
     * background access and a failure here must not pre-empt the database-error screen.
     */
    private fun reRegisterPeriodicBackups() {
        val configDao = get<BackupConfigDao>()
        val scheduler = get<IBackupScheduler>()
        val settingsRepo = get<IAppSettingsRepository>()
        CoroutineScope(get<IDispatchers>().io).launch {
            try {
                val globalDefault = settingsRepo.settings.first().defaultSchedule
                configDao.getAll().first()
                    .filterNot { it.isPaused }
                    .forEach { config ->
                        scheduler.schedulePeriodicIfNeeded(
                            configId = config.id,
                            schedule = config.schedule,
                            networkPolicy = config.networkPolicy,
                            requiresCharging = config.requiresCharging,
                            globalDefault = globalDefault,
                        )
                    }
            } catch (e: Exception) {
                e.rethrowCancellation()
                logger.warning("Could not re-register periodic backups on startup — database unavailable?", e)
            }
        }
    }

    /**
     * Marks any backup-run row still in RUNNING state past the grace window as CANCELLED.
     * Process death leaves these rows behind — no code runs at death, so the cleanup is
     * deferred to the next app start. The grace window prevents racing legitimately
     * long-running backups.
     *
     * Must not crash on a broken database: this is the first database access after app start,
     * and a failure here would kill the process before the database-error screen can appear
     * (the screen owns surfacing and recovering from that failure).
     */
    private fun sweepStaleRunningBackupRuns() {
        val dao = get<BackupRunDao>()
        CoroutineScope(get<IDispatchers>().io).launch {
            try {
                val now = System.currentTimeMillis()
                val updated = dao.markStaleRunningAsCancelled(
                    staleBefore = now - BackupRunDao.STALE_GRACE_WINDOW_MS,
                    now = now,
                )
                if (updated > 0) {
                    logger.info("Swept $updated stale RUNNING backup-run rows to CANCELLED")
                }
            } catch (e: Exception) {
                e.rethrowCancellation()
                logger.warning("Could not sweep stale backup runs — database unavailable?", e)
            }
        }
    }

    private fun configureLogging() {
        val local = LocalLogSink(this)
        val remote = CrashlyticsSink()
        LoggerProvider.configure { tag -> PrivateLogger(tag, local, remote) }
    }

    private fun applyInitialTelemetrySettings() {
        val toggle = get<ITelemetryToggle>()
        val settingsRepo = get<IAppSettingsRepository>()
        CoroutineScope(get<IDispatchers>().io).launch {
            toggle.setEnabled(settingsRepo.settings.first().anonymousErrorReports)
        }
    }
}
