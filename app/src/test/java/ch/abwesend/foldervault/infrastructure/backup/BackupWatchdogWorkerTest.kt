package ch.abwesend.foldervault.infrastructure.backup

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Verifies [BackupWatchdogWorker]'s overdue selection: a non-paused config whose periodic schedule
 * has not fired for more than a full extra interval gets a one-time catch-up run plus a single
 * [MessageType.WATCHDOG_TRIGGERED_RUN] breadcrumb; up-to-date, paused, and manual-only configs are
 * left alone, and the breadcrumb is not duplicated while one is already present.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupWatchdogWorkerTest {

    private lateinit var configDao: BackupConfigDao
    private lateinit var messageDao: BackupMessageDao
    private lateinit var scheduler: IBackupScheduler
    private lateinit var settingsRepository: IAppSettingsRepository

    private val withinGraceMs = TimeUnit.HOURS.toMillis(36) // past the expected trigger, inside grace
    private val threeDaysMs = TimeUnit.DAYS.toMillis(3)

    @Before
    fun setUp() {
        configDao = mockk()
        messageDao = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        settingsRepository = mockk {
            every { settings } returns flowOf(AppSettings(defaultSchedule = BackupSchedule.DAILY))
        }
        // Default: no existing breadcrumb, so the message is inserted.
        coEvery { messageDao.getCountForType(any(), any()) } returns 0

        startKoin {
            modules(
                module {
                    single { configDao }
                    single { messageDao }
                    single { scheduler }
                    single { settingsRepository }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `an overdue config gets a catch-up run and a single breadcrumb`() = runBlocking {
        val overdue = configEntity(id = "overdue", lastRunAtAgoMs = threeDaysMs)
        every { configDao.getAll() } returns flowOf(listOf(overdue))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verify(exactly = 1) { scheduler.scheduleOneTime("overdue", NetworkPolicy.WIFI_ONLY, false, any(), any()) }
        coVerify(exactly = 1) { messageDao.coalesceInsert(match { it.type == MessageType.WATCHDOG_TRIGGERED_RUN }) }
    }

    @Test
    fun `a config still within the grace window is left alone`() = runBlocking {
        // 36 h after the last run is past the expected trigger (lastRun + 1 day) but still inside
        // the one-interval grace, so the watchdog must not fire yet.
        val recent = configEntity(id = "recent", lastRunAtAgoMs = withinGraceMs)
        every { configDao.getAll() } returns flowOf(listOf(recent))

        buildWorker().doWork()

        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { messageDao.coalesceInsert(any()) }
    }

    @Test
    fun `a paused overdue config is skipped`() = runBlocking {
        val paused = configEntity(id = "paused", lastRunAtAgoMs = threeDaysMs, isPaused = true)
        every { configDao.getAll() } returns flowOf(listOf(paused))

        buildWorker().doWork()

        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a manual-only config never counts as overdue`() = runBlocking {
        val manual = configEntity(id = "manual", lastRunAtAgoMs = threeDaysMs, schedule = BackupSchedule.MANUAL_ONLY)
        every { configDao.getAll() } returns flowOf(listOf(manual))

        buildWorker().doWork()

        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `no duplicate breadcrumb while one is already present, but the run is still enqueued`() = runBlocking {
        val overdue = configEntity(id = "overdue", lastRunAtAgoMs = threeDaysMs)
        every { configDao.getAll() } returns flowOf(listOf(overdue))
        coEvery { messageDao.getCountForType("overdue", MessageType.WATCHDOG_TRIGGERED_RUN) } returns 1

        buildWorker().doWork()

        verify(exactly = 1) { scheduler.scheduleOneTime("overdue", any(), any(), any(), any()) }
        coVerify(exactly = 0) { messageDao.coalesceInsert(any()) }
    }

    private fun buildWorker(): BackupWatchdogWorker {
        val context = InstrumentationRegistry.getInstrumentation().context
        return TestListenableWorkerBuilder<BackupWatchdogWorker>(context).build()
    }

    private fun configEntity(
        id: String,
        lastRunAtAgoMs: Long,
        isPaused: Boolean = false,
        schedule: BackupSchedule = BackupSchedule.DAILY,
    ) = BackupConfigEntity(
        id = id,
        displayName = "Test Backup",
        sourceTreeUri = "content://com.example/tree/primary%3ADocuments",
        cloudProvider = "GOOGLE_DRIVE",
        cloudSubFolderId = "folder-1",
        cloudSubFolderName = "test_sub",
        cloudAccountIdentifier = "user@example.com",
        schedule = schedule,
        changedFilePolicy = ChangedFilePolicy.OVERWRITE,
        encryptionEnabled = false,
        encryptedPasswordBlob = null,
        encryptionParams = null,
        retentionPolicy = RetentionPolicy.KeepAll,
        networkPolicy = NetworkPolicy.WIFI_ONLY,
        createdAt = System.currentTimeMillis() - lastRunAtAgoMs,
        lastRunAt = System.currentTimeMillis() - lastRunAtAgoMs,
        lastRunStatus = BackupRunStatus.UP_TO_DATE,
        filesUploaded = 0,
        filesSkipped = 0,
        filesFailed = 0,
        bytesUploaded = 0L,
        totalFilesDiscovered = 0,
        filesUploadedTotal = 0,
        lastRunCompletedNormally = true,
        isPaused = isPaused,
    )
}
