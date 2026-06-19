package ch.abwesend.folderVault.infrastructure.backup

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.infrastructure.backup.BackupNotificationManager
import ch.abwesend.foldervault.infrastructure.backup.BackupRunner
import ch.abwesend.foldervault.infrastructure.backup.BackupWorker
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupWorkerErrorSurfaceTest {

    private val configId = "config-under-test"
    private lateinit var configDao: BackupConfigDao
    private lateinit var messageDao: BackupMessageDao
    private lateinit var runner: BackupRunner
    private lateinit var notificationManager: BackupNotificationManager

    @Before
    fun setUp() {
        configDao = mockk()
        messageDao = mockk(relaxUnitFun = true)
        runner = mockk()
        notificationManager = mockk(relaxUnitFun = true)

        startKoin {
            modules(
                module {
                    single { configDao }
                    single { messageDao }
                    single { runner }
                    single { notificationManager }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `unexpected exception in run pipeline surfaces a GENERIC_ERROR message`() = runBlocking {
        val config = backupConfigEntity(configId)
        coEvery { configDao.getByIdOnce(configId) } returns config
        coEvery { runner.runBackup(configId, any()) } throws IllegalStateException("boom")

        val worker = buildWorker(configId)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify(exactly = 1) {
            messageDao.coalesceInsert(
                match { entity ->
                    entity.backupConfigId == configId &&
                        entity.type == MessageType.GENERIC_ERROR &&
                        entity.severity == MessageSeverity.ERROR
                }
            )
        }
        coVerify(exactly = 1) {
            notificationManager.postProblemNotificationIfNeeded(
                configId = configId,
                configName = config.displayName,
                runId = any(),
            )
        }
    }

    @Test
    fun `missing configId returns failure without touching DAO or notification manager`() = runBlocking {
        val worker = buildWorker(configIdInInputData = null)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify(exactly = 0) { messageDao.coalesceInsert(any<BackupMessageEntity>()) }
        coVerify(exactly = 0) {
            notificationManager.postProblemNotificationIfNeeded(any(), any(), any())
        }
    }

    @Test
    fun `worker-level exception during config lookup still surfaces a problem notification`() = runBlocking {
        val config = backupConfigEntity(configId)
        // First call (inner block) throws; second call (surfaceFatalError) returns the config
        // so the user sees a notification deep-linking to the right backup.
        var callCount = 0
        coEvery { configDao.getByIdOnce(configId) } answers {
            callCount++
            if (callCount == 1) error("db hiccup") else config
        }

        val worker = buildWorker(configId)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertTrue(callCount >= 2, "config DAO should be queried again from surfaceFatalError")
        coVerify(exactly = 1) {
            messageDao.coalesceInsert(
                match { it.type == MessageType.GENERIC_ERROR && it.severity == MessageSeverity.ERROR }
            )
        }
    }

    private fun buildWorker(configIdInInputData: String?): BackupWorker {
        val context = InstrumentationRegistry.getInstrumentation().context
        val inputData = if (configIdInInputData != null) {
            workDataOf(BackupWorker.KEY_CONFIG_ID to configIdInInputData)
        } else {
            workDataOf()
        }
        return TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(inputData)
            .build()
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
