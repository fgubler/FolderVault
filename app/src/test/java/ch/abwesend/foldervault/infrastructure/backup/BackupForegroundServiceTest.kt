package ch.abwesend.foldervault.infrastructure.backup

import android.app.Application
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.coroutine.AppDispatchers
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Robolectric tests driving the real [BackupForegroundService] through `onStartCommand` with a
 * mocked [BackupRunner] that honors the cooperative-stop contract of [BackupRunControl].
 *
 * Covers two review findings:
 * - B1: the framework's dataSync time-limit path invokes ONLY the two-argument
 *   `onTimeout(startId, fgsType)` overload, so the service must handle the timeout there.
 *   Before the fix the call landed in [android.app.Service]'s empty default implementation and
 *   neither the cooperative stop nor the continuation happened.
 * - B3: the progress notification must show "N / M files" during the very FIRST run, where the
 *   persisted cross-run counters are still 0 — the discovered total arrives live through
 *   [BackupRunControl.filesDiscovered], not from the config row snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupForegroundServiceTest {

    private val configId = "config-under-test"

    private lateinit var runner: BackupRunner
    private lateinit var configDao: BackupConfigDao
    private lateinit var notificationManager: BackupNotificationManager
    private lateinit var scheduler: IBackupScheduler

    private val runStarted = CountDownLatch(1)
    private val continuationScheduled = CountDownLatch(1)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        makeActiveNetworkSatisfyWifiOnly(context)

        runner = mockk()
        configDao = mockk()
        notificationManager = mockk(relaxed = true)
        scheduler = mockk()

        every { notificationManager.buildProgressNotification(any(), any(), any(), any()) } returns
            NotificationCompat.Builder(context, "test-channel")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .build()
        every { scheduler.scheduleOneTime(any(), any(), any(), any()) } answers {
            continuationScheduled.countDown()
        }

        coEvery { configDao.getByIdOnce(configId) } returns backupConfigEntity(configId)

        startKoin {
            modules(
                module {
                    single { runner }
                    single { configDao }
                    single { notificationManager }
                    single<IBackupScheduler> { scheduler }
                    single { ForegroundRunState() }
                    single<IDispatchers> { AppDispatchers }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `dataSync onTimeout overload stops the run cooperatively and schedules a continuation`() {
        // Emulates the uploader's cooperative-stop contract: keep working until the control
        // signals a stop, then finish cleanly with work remaining (hitTimeBudget).
        coEvery { runner.runBackup(configId, any()) } coAnswers {
            val control = secondArg<BackupRunControl?>()
            runStarted.countDown()
            while (control?.shouldStop() != true) {
                delay(20)
            }
            RunResult.Success(summary = RunSummary().apply { hitTimeBudget = true }, runId = "run-1")
        }

        val service = Robolectric.buildService(BackupForegroundService::class.java).create().get()
        service.onStartCommand(startIntent(), 0, 1)
        assertTrue(runStarted.await(10, TimeUnit.SECONDS), "the backup run should have started")

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(
            continuationScheduled.await(10, TimeUnit.SECONDS),
            "the OS timeout should stop the run and hand over to a WorkManager continuation",
        )
        verify(exactly = 1) {
            scheduler.scheduleOneTime(
                configId,
                NetworkPolicy.WIFI_ONLY,
                requiresCharging = false,
                asContinuation = false,
            )
        }
    }

    @Test
    fun `progress notification shows the live discovered total during the very first run`() {
        val liveCountsPublished = CountDownLatch(1)
        // The config row snapshot has totalFilesDiscovered = 0 (first run) — counts can only
        // come from the run control's live flows.
        every { notificationManager.updateProgressNotification(any(), any(), any(), any()) } answers {
            val filesUploaded = arg<Int>(1)
            val totalDiscovered = arg<Int>(2)
            if (filesUploaded == 3 && totalDiscovered == 42) {
                liveCountsPublished.countDown()
            }
        }
        coEvery { runner.runBackup(configId, any()) } coAnswers {
            val control = secondArg<BackupRunControl?>()
            runStarted.countDown()
            control?.reportFilesDiscovered(42)
            control?.reportFileUploaded(3)
            while (control?.shouldStop() != true) {
                delay(20)
            }
            RunResult.Success(summary = RunSummary().apply { hitTimeBudget = true }, runId = "run-1")
        }

        val service = Robolectric.buildService(BackupForegroundService::class.java).create().get()
        service.onStartCommand(startIntent(), 0, 1)
        assertTrue(runStarted.await(10, TimeUnit.SECONDS), "the backup run should have started")

        assertTrue(
            liveCountsPublished.await(10, TimeUnit.SECONDS),
            "the progress notification should show the live 3 / 42 counts on a first run",
        )

        val stopIntent = Intent().apply { action = BackupForegroundService.ACTION_STOP }
        service.onStartCommand(stopIntent, 0, 2)
    }

    private fun startIntent() = Intent().apply {
        putExtra(BackupForegroundService.EXTRA_CONFIG_ID, configId)
        putExtra(BackupForegroundService.EXTRA_NETWORK_POLICY, NetworkPolicy.WIFI_ONLY.name)
    }

    /**
     * Robolectric's default network has no capabilities, so the service's network-policy
     * watcher would consider WIFI_ONLY violated and stop the run through its own path,
     * releasing the latches without the code under test being involved — silently voiding
     * what these tests assert. A satisfied network keeps that watcher quiet.
     */
    private fun makeActiveNetworkSatisfyWifiOnly(context: Application) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(connectivityManager).setNetworkCapabilities(connectivityManager.activeNetwork, capabilities)
    }

    private fun backupConfigEntity(id: String) = BackupConfigEntity(
        id = id,
        displayName = "Test Backup",
        sourceTreeUri = "content://com.example/tree/primary%3ADocuments",
        cloudProvider = "GOOGLE_DRIVE",
        cloudSubFolderId = "folder-${UUID.randomUUID()}",
        cloudSubFolderName = "test_${UUID.randomUUID()}",
        cloudAccountIdentifier = "user@example.com",
        schedule = BackupSchedule.DAILY,
        changedFilePolicy = ChangedFilePolicy.OVERWRITE,
        encryptionEnabled = false,
        encryptedPasswordBlob = null,
        encryptionParams = null,
        retentionPolicy = RetentionPolicy.KeepAll,
        networkPolicy = NetworkPolicy.WIFI_ONLY,
        createdAt = System.currentTimeMillis(),
        lastRunAt = null,
        lastRunStatus = BackupRunStatus.IDLE,
        filesUploaded = 0,
        filesSkipped = 0,
        filesFailed = 0,
        bytesUploaded = 0L,
        totalFilesDiscovered = 0,
        filesUploadedTotal = 0,
        lastRunCompletedNormally = false,
    )
}
