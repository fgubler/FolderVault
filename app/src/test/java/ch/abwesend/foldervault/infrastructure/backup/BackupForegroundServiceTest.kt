package ch.abwesend.foldervault.infrastructure.backup

import android.app.Application
import android.app.Notification
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
import ch.abwesend.foldervault.infrastructure.network.NetworkStateMonitor
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Robolectric tests driving the real [BackupForegroundService] through `onStartCommand` with a
 * mocked [BackupRunner] that honors the cooperative-stop contract of [BackupRunControl].
 *
 * Covers three review findings:
 * - B1: the framework's dataSync time-limit path invokes ONLY the two-argument
 *   `onTimeout(startId, fgsType)` overload, so the service must handle the timeout there.
 *   Before the fix the call landed in [android.app.Service]'s empty default implementation and
 *   neither the cooperative stop nor the continuation happened.
 * - B2: a start for a *second* config while the service is busy must not be dropped — it is
 *   queued and runs (serially) once the active run completes. Queued runs read as running for
 *   the UI, are handed to WorkManager on an OS timeout, and are dropped on a user stop.
 * - B3: the progress notification must show "N / M files" during the very FIRST run, where the
 *   persisted cross-run counters are still 0 — the discovered total arrives live through
 *   [BackupRunControl.filesDiscovered], not from the config row snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupForegroundServiceTest {

    private val configId = "config-under-test"
    private val secondConfigId = "second-config"

    private lateinit var runner: BackupRunner
    private lateinit var configDao: BackupConfigDao
    private lateinit var notificationManager: BackupNotificationManager
    private lateinit var scheduler: IBackupScheduler
    private lateinit var foregroundRunState: ForegroundRunState
    private lateinit var progressNotification: Notification

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
        foregroundRunState = ForegroundRunState()

        progressNotification = NotificationCompat.Builder(context, "test-channel")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        every {
            notificationManager.buildProgressNotification(any(), any(), any(), any(), any())
        } returns progressNotification
        every {
            notificationManager.updateProgressNotification(any(), any(), any(), any(), any())
        } returns progressNotification
        every { scheduler.scheduleOneTime(any(), any(), any(), any()) } answers {
            continuationScheduled.countDown()
        }
        every { scheduler.cancelOneTime(any()) } just runs

        coEvery { configDao.getByIdOnce(configId) } returns backupConfigEntity(configId)
        coEvery { configDao.getByIdOnce(secondConfigId) } returns backupConfigEntity(secondConfigId)

        startKoin {
            modules(
                module {
                    single { runner }
                    single { configDao }
                    single { notificationManager }
                    single<IBackupScheduler> { scheduler }
                    single { foregroundRunState }
                    single { NetworkStateMonitor(context) }
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
        mockRunnerStoppingCooperatively(configId, runStarted)

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
        every {
            notificationManager.updateProgressNotification(any(), any(), any(), any(), any())
        } answers {
            val filesUploaded = arg<Int>(1)
            val totalDiscovered = arg<Int>(2)
            if (filesUploaded == 3 && totalDiscovered == 42) {
                liveCountsPublished.countDown()
            }
            progressNotification
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

        service.onStartCommand(stopIntent(), 0, 2)
    }

    @Test
    fun `a second config started while busy is queued and runs after the active run completes`() {
        val releaseFirstRun = CountDownLatch(1)
        val secondRunStarted = CountDownLatch(1)
        val queuedCountShown = CountDownLatch(1)
        every {
            notificationManager.updateProgressNotification(any(), any(), any(), any(), any())
        } answers {
            if (arg<Int>(3) == 1) {
                queuedCountShown.countDown()
            }
            progressNotification
        }
        mockRunnerHeldOpen(configId, runStarted, releaseFirstRun)
        coEvery { runner.runBackup(secondConfigId, any()) } coAnswers {
            secondRunStarted.countDown()
            RunResult.Success(summary = RunSummary(), runId = "run-2")
        }

        val service = Robolectric.buildService(BackupForegroundService::class.java).create().get()
        service.onStartCommand(startIntent(), 0, 1)
        assertTrue(runStarted.await(10, TimeUnit.SECONDS), "the first backup run should have started")

        service.onStartCommand(startIntent(secondConfigId), 0, 2)

        assertTrue(
            foregroundRunState.isRunning(secondConfigId),
            "a queued run should read as running so the UI reflects the accepted tap",
        )
        assertTrue(
            queuedCountShown.await(10, TimeUnit.SECONDS),
            "the progress notification should mention the queued run",
        )
        assertFalse(secondRunStarted.await(1, TimeUnit.SECONDS), "the queued run must wait for the active one")

        releaseFirstRun.countDown()
        assertTrue(
            secondRunStarted.await(10, TimeUnit.SECONDS),
            "the queued run should start once the active one completes",
        )
        awaitNotRunning(secondConfigId)
    }

    @Test
    fun `a duplicate start for the already-active config is ignored`() {
        val releaseRun = CountDownLatch(1)
        mockRunnerHeldOpen(configId, runStarted, releaseRun)

        val service = Robolectric.buildService(BackupForegroundService::class.java).create().get()
        service.onStartCommand(startIntent(), 0, 1)
        assertTrue(runStarted.await(10, TimeUnit.SECONDS), "the backup run should have started")

        service.onStartCommand(startIntent(), 0, 2)
        releaseRun.countDown()

        awaitNotRunning(configId)
        // Settle: a wrongly-queued duplicate would launch (and mark itself running) only now.
        Thread.sleep(500)
        coVerify(exactly = 1) { runner.runBackup(configId, any()) }
    }

    @Test
    fun `the user stop action drains the queue without running or scheduling the queued run`() {
        mockRunnerStoppingCooperatively(configId, runStarted)

        val service = Robolectric.buildService(BackupForegroundService::class.java).create().get()
        service.onStartCommand(startIntent(), 0, 1)
        assertTrue(runStarted.await(10, TimeUnit.SECONDS), "the backup run should have started")
        service.onStartCommand(startIntent(secondConfigId), 0, 2)

        service.onStartCommand(stopIntent(), 0, 3)

        awaitNotRunning(configId)
        awaitNotRunning(secondConfigId)
        coVerify(exactly = 0) { runner.runBackup(secondConfigId, any()) }
        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any(), any()) }
    }

    @Test
    fun `the OS timeout hands queued runs over to WorkManager instead of starting them`() {
        mockRunnerStoppingCooperatively(configId, runStarted)

        val service = Robolectric.buildService(BackupForegroundService::class.java).create().get()
        service.onStartCommand(startIntent(), 0, 1)
        assertTrue(runStarted.await(10, TimeUnit.SECONDS), "the backup run should have started")
        service.onStartCommand(startIntent(secondConfigId), 0, 2)

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        verify(timeout = 10_000) {
            scheduler.scheduleOneTime(
                secondConfigId,
                NetworkPolicy.WIFI_ONLY,
                requiresCharging = false,
                asContinuation = false,
            )
        }
        verify(timeout = 10_000) {
            scheduler.scheduleOneTime(
                configId,
                NetworkPolicy.WIFI_ONLY,
                requiresCharging = false,
                asContinuation = false,
            )
        }
        coVerify(exactly = 0) { runner.runBackup(secondConfigId, any()) }
        awaitNotRunning(secondConfigId)
    }

    /**
     * A user stop followed by the OS timeout may end in the hard-cancel path (the run never
     * reaches a file boundary). The user explicitly declined the run — the hard-cancel path must
     * honor [ForegroundHandoverPolicy] like the normal result handling does, not enqueue the
     * WorkManager continuation unconditionally (RV-3).
     */
    @Test
    fun `the OS timeout hard-cancel path schedules no continuation after a user stop`() {
        val neverReleased = CountDownLatch(1)
        mockRunnerHeldOpen(configId, runStarted, neverReleased)

        val service = Robolectric.buildService(BackupForegroundService::class.java).create().get()
        service.onStartCommand(startIntent(), 0, 1)
        assertTrue(runStarted.await(10, TimeUnit.SECONDS), "the backup run should have started")

        // The runner ignores the cooperative stop, so the drain in onTimeout must time out.
        service.onStartCommand(stopIntent(), 0, 2)
        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        awaitNotRunning(configId)
        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any(), any()) }
    }

    @Test
    fun `starting a run cancels the config's pending one-time slot through the scheduler`() {
        mockRunnerStoppingCooperatively(configId, runStarted)

        val service = Robolectric.buildService(BackupForegroundService::class.java).create().get()
        service.onStartCommand(startIntent(), 0, 1)
        assertTrue(runStarted.await(10, TimeUnit.SECONDS), "the backup run should have started")

        // A queued one-time run would duplicate the foreground run once its constraints are met.
        verify(exactly = 1) { scheduler.cancelOneTime(configId) }
        service.onStartCommand(stopIntent(), 0, 2)
        awaitNotRunning(configId)
    }

    /** Runner fake that works until the service's [BackupRunControl] requests a cooperative stop. */
    private fun mockRunnerStoppingCooperatively(configId: String, started: CountDownLatch) {
        coEvery { runner.runBackup(configId, any()) } coAnswers {
            val control = secondArg<BackupRunControl?>()
            started.countDown()
            while (control?.shouldStop() != true) {
                delay(20)
            }
            RunResult.Success(summary = RunSummary().apply { hitTimeBudget = true }, runId = "run-$configId")
        }
    }

    /** Runner fake that keeps the run open until the test releases it, then completes normally. */
    private fun mockRunnerHeldOpen(configId: String, started: CountDownLatch, release: CountDownLatch) {
        coEvery { runner.runBackup(configId, any()) } coAnswers {
            started.countDown()
            while (release.count > 0) {
                delay(20)
            }
            RunResult.Success(summary = RunSummary(), runId = "run-$configId")
        }
    }

    /** Polls [ForegroundRunState] until the config no longer reads as running. */
    private fun awaitNotRunning(configId: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (foregroundRunState.isRunning(configId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertFalse(foregroundRunState.isRunning(configId), "config $configId should have stopped running")
    }

    private fun startIntent(forConfigId: String = configId) = Intent().apply {
        putExtra(BackupForegroundService.EXTRA_CONFIG_ID, forConfigId)
        putExtra(BackupForegroundService.EXTRA_NETWORK_POLICY, NetworkPolicy.WIFI_ONLY.name)
    }

    private fun stopIntent() = Intent().apply { action = BackupForegroundService.ACTION_STOP }

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
