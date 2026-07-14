package ch.abwesend.foldervault.infrastructure.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Clock
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Verifies the periodic-schedule fix that keeps a freshly-created config's initial upload on the
 * foreground service (see review finding RV-15): the first periodic run must be deferred by one
 * full period so the FGS auto-start wins the per-config run lock instead of an immediately-firing
 * background worker — and, because [ch.abwesend.foldervault.FolderVaultApp] re-registers every
 * schedule with [androidx.work.ExistingPeriodicWorkPolicy.UPDATE] on every app start, that UPDATE
 * must preserve the already-computed next-run time rather than push it out by another period each
 * time. Drives the real [BackupScheduler] against the WorkManager test harness with an injectable
 * clock so the two enqueues can be separated in (virtual) time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupSchedulerTest {

    private var nowMillis = 1_700_000_000_000L
    private val testClock = Clock { nowMillis }

    private fun newScheduler(): Pair<BackupScheduler, WorkManager> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val configuration = Configuration.Builder()
            .setClock(testClock)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        return BackupScheduler(context, ForegroundRunState()) to WorkManager.getInstance(context)
    }

    private fun WorkManager.nextRunOf(configId: String): Long =
        getWorkInfosForUniqueWork(BackupWorker.workName(configId)).get().single().nextScheduleTimeMillis

    private fun BackupScheduler.schedule(configId: String, schedule: BackupSchedule) = schedulePeriodicIfNeeded(
        configId = configId,
        schedule = schedule,
        networkPolicy = NetworkPolicy.WIFI_ONLY,
        requiresCharging = false,
        globalDefault = BackupSchedule.DAILY,
    )

    @Test
    fun `a freshly scheduled daily backup does not fire immediately`() {
        val (scheduler, workManager) = newScheduler()

        scheduler.schedule("cfg-1", BackupSchedule.DAILY)

        // The first run is deferred by a day, so it cannot grab the run lock before the
        // foreground-service auto-start does at config-creation time.
        workManager.nextRunOf("cfg-1") shouldBeGreaterThanOrEqual nowMillis + TimeUnit.HOURS.toMillis(23)
    }

    @Test
    fun `the first-run delay is a fixed day even for a longer cadence`() {
        val (scheduler, workManager) = newScheduler()

        scheduler.schedule("cfg-1", BackupSchedule.WEEKLY)

        // Regardless of periodicity the first run is deferred by ~a day (not ~a week), so the
        // delay's only effect is losing the creation-time race with the foreground service.
        val firstRun = workManager.nextRunOf("cfg-1")
        firstRun shouldBeGreaterThanOrEqual nowMillis + TimeUnit.HOURS.toMillis(23)
        firstRun shouldBeLessThan nowMillis + TimeUnit.HOURS.toMillis(48)
    }

    @Test
    fun `re-registering with UPDATE preserves the next-run time instead of deferring it again`() {
        val (scheduler, workManager) = newScheduler()

        scheduler.schedule("cfg-1", BackupSchedule.DAILY)
        val firstNextRun = workManager.nextRunOf("cfg-1")

        // Simulate an app restart an hour later: FolderVaultApp re-registers every schedule.
        nowMillis += TimeUnit.HOURS.toMillis(1)
        scheduler.schedule("cfg-1", BackupSchedule.DAILY)

        // If UPDATE re-applied the fresh initial delay, this would jump to ~now+24h; it must not.
        workManager.nextRunOf("cfg-1") shouldBe firstNextRun
    }
}
