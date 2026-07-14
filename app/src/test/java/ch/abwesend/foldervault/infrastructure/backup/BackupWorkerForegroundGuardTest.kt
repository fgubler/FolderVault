package ch.abwesend.foldervault.infrastructure.backup

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
 * Verifies [BackupWorker]'s foreground-run guard: while the foreground service owns a config's
 * run — actively executing it or holding it in its serial queue — the worker must defer with a
 * retry instead of running. A *queued* config does not hold [BackupRunner]'s per-config lock, so
 * without the guard a periodic worker firing after the short first-run delay would steal the
 * initial upload from the service and crawl through it in 8-minute background windows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupWorkerForegroundGuardTest {

    private val configId = "config-under-test"
    private lateinit var configDao: BackupConfigDao
    private lateinit var runner: BackupRunner
    private lateinit var foregroundRunState: ForegroundRunState

    @Before
    fun setUp() {
        configDao = mockk()
        runner = mockk()
        foregroundRunState = ForegroundRunState()

        startKoin {
            modules(
                module {
                    single { configDao }
                    single { mockk<BackupMessageDao>(relaxUnitFun = true) }
                    single { runner }
                    single { mockk<BackupNotificationManager>(relaxUnitFun = true) }
                    single { foregroundRunState }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `worker retries without running while the foreground service owns the config's run`() = runBlocking {
        coEvery { configDao.getByIdOnce(configId) } returns backupConfigEntity(configId)
        foregroundRunState.markRunning(configId) // running or queued in the service — same signal

        val result = buildWorker(configId).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { runner.runBackup(any(), any()) }
    }

    @Test
    fun `worker runs normally once the foreground service has released the config`() = runBlocking {
        coEvery { configDao.getByIdOnce(configId) } returns backupConfigEntity(configId)
        coEvery { runner.runBackup(configId, any()) } returns RunResult.SkippedConcurrentRun
        foregroundRunState.markRunning(configId)
        foregroundRunState.markStopped(configId)

        buildWorker(configId).doWork()

        coVerify(exactly = 1) { runner.runBackup(configId, any()) }
    }

    private fun buildWorker(configId: String): BackupWorker {
        val context = InstrumentationRegistry.getInstrumentation().context
        return TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(workDataOf(BackupWorker.KEY_CONFIG_ID to configId))
            .build()
    }

    private fun backupConfigEntity(id: String) = BackupConfigEntity(
        id = id,
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
