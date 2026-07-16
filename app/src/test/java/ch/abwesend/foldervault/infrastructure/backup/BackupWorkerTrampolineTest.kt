package ch.abwesend.foldervault.infrastructure.backup

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import ch.abwesend.foldervault.domain.backup.IFgsLaunchScheduler
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Verifies [BackupWorker]'s "more reliable backups" trampoline decision. When the user opted in,
 * the exact-alarm permission is granted, and the run needs a long window (initial / large /
 * interrupted sync), the worker hands the run to the foreground service via a one-shot exact alarm
 * and returns without running inline. Every other case — opt-in off, permission missing, or an
 * established small delta — runs inline exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupWorkerTrampolineTest {

    private val configId = "config-under-test"
    private lateinit var configDao: BackupConfigDao
    private lateinit var runner: BackupRunner
    private lateinit var settingsRepository: IAppSettingsRepository
    private lateinit var fgsLaunchScheduler: IFgsLaunchScheduler

    @Before
    fun setUp() {
        configDao = mockk()
        runner = mockk()
        settingsRepository = mockk()
        fgsLaunchScheduler = mockk(relaxed = true)

        startKoin {
            modules(
                module {
                    single { configDao }
                    single { runner }
                    single { settingsRepository }
                    single { fgsLaunchScheduler }
                    single { ForegroundRunState() }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `opted-in, permitted, long-window run trampolines to the service instead of running inline`() =
        runBlocking {
            coEvery { configDao.getByIdOnce(configId) } returns backupConfigEntity(BackupRunStatus.IDLE, 0)
            every { settingsRepository.settings } returns flowOf(AppSettings(exactAlarmBackupsEnabled = true))
            every { fgsLaunchScheduler.isExactAlarmPermitted() } returns true
            every { fgsLaunchScheduler.scheduleImmediateLaunch(configId, any(), any()) } returns true

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(exactly = 1) { fgsLaunchScheduler.scheduleImmediateLaunch(configId, NetworkPolicy.WIFI_ONLY, false) }
            coVerify(exactly = 0) { runner.runBackup(any(), any()) }
        }

    @Test
    fun `opt-in off runs inline`() = runBlocking {
        coEvery { configDao.getByIdOnce(configId) } returns backupConfigEntity(BackupRunStatus.IDLE, 0)
        every { settingsRepository.settings } returns flowOf(AppSettings(exactAlarmBackupsEnabled = false))
        every { fgsLaunchScheduler.isExactAlarmPermitted() } returns true
        coEvery { runner.runBackup(configId, any()) } returns RunResult.SkippedConcurrentRun

        buildWorker().doWork()

        verify(exactly = 0) { fgsLaunchScheduler.scheduleImmediateLaunch(any(), any(), any()) }
        coVerify(exactly = 1) { runner.runBackup(configId, any()) }
    }

    @Test
    fun `established small delta runs inline even when opted-in and permitted`() = runBlocking {
        coEvery { configDao.getByIdOnce(configId) } returns backupConfigEntity(BackupRunStatus.UP_TO_DATE, 0)
        every { settingsRepository.settings } returns flowOf(AppSettings(exactAlarmBackupsEnabled = true))
        every { fgsLaunchScheduler.isExactAlarmPermitted() } returns true
        coEvery { runner.runBackup(configId, any()) } returns RunResult.SkippedConcurrentRun

        buildWorker().doWork()

        verify(exactly = 0) { fgsLaunchScheduler.scheduleImmediateLaunch(any(), any(), any()) }
        coVerify(exactly = 1) { runner.runBackup(configId, any()) }
    }

    @Test
    fun `a force-inline run never trampolines, breaking the budget-exhaustion loop`() = runBlocking {
        coEvery { configDao.getByIdOnce(configId) } returns backupConfigEntity(BackupRunStatus.IDLE, 0)
        every { settingsRepository.settings } returns flowOf(AppSettings(exactAlarmBackupsEnabled = true))
        every { fgsLaunchScheduler.isExactAlarmPermitted() } returns true
        coEvery { runner.runBackup(configId, any()) } returns RunResult.SkippedConcurrentRun

        buildWorker(forceInline = true).doWork()

        verify(exactly = 0) { fgsLaunchScheduler.scheduleImmediateLaunch(any(), any(), any()) }
        coVerify(exactly = 1) { runner.runBackup(configId, any()) }
    }

    @Test
    fun `permission missing falls through to an inline run`() = runBlocking {
        coEvery { configDao.getByIdOnce(configId) } returns backupConfigEntity(BackupRunStatus.IDLE, 0)
        every { settingsRepository.settings } returns flowOf(AppSettings(exactAlarmBackupsEnabled = true))
        every { fgsLaunchScheduler.isExactAlarmPermitted() } returns false
        coEvery { runner.runBackup(configId, any()) } returns RunResult.SkippedConcurrentRun

        buildWorker().doWork()

        verify(exactly = 0) { fgsLaunchScheduler.scheduleImmediateLaunch(any(), any(), any()) }
        coVerify(exactly = 1) { runner.runBackup(configId, any()) }
    }

    private fun buildWorker(forceInline: Boolean = false): BackupWorker {
        val context = InstrumentationRegistry.getInstrumentation().context
        return TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_CONFIG_ID to configId,
                    BackupWorker.KEY_FORCE_INLINE to forceInline,
                )
            )
            .build()
    }

    private fun backupConfigEntity(lastRunStatus: BackupRunStatus, totalFilesDiscovered: Int) = BackupConfigEntity(
        id = configId,
        displayName = "Test Backup",
        sourceTreeUri = "content://com.example/tree/primary%3ADocuments",
        cloudProvider = "GOOGLE_DRIVE",
        cloudSubFolderId = "folder-1",
        cloudSubFolderName = "test_sub",
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
        lastRunStatus = lastRunStatus,
        filesUploaded = 0,
        filesSkipped = 0,
        filesFailed = 0,
        bytesUploaded = 0L,
        totalFilesDiscovered = totalFilesDiscovered,
        filesUploadedTotal = 0,
        lastRunCompletedNormally = false,
    )
}
