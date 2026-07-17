package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import android.net.Uri
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.logging.ILogger
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupRunDao
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest

/**
 * Run-level coverage of [BackupRunner.runBaselinePass] (RV-11): pins the orchestration contract
 * that the extracted pieces (BaselineRecorder, ChangeDetector) don't see — the committed
 * [BackupRunStatus], the emitted [MessageType], and the returned [RunResult] for a clean pass, a
 * cooperative stop, and an inaccessible source. Uses a fake [ILocalFileScanner] so no SAF/Android
 * machinery is needed; the DB seams are relaxed mocks whose relevant arguments are captured.
 */
class BackupRunnerBaselineTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val noOpLogger = object : ILogger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warning(message: String, error: Throwable?) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    beforeTest { LoggerProvider.configure { noOpLogger } }

    val config = BackupConfigEntity(
        id = "cfg",
        displayName = "Test",
        sourceTreeUri = "content://tree",
        cloudProvider = "googledrive",
        cloudSubFolderId = "sub",
        cloudSubFolderName = "sub_abc123",
        cloudAccountIdentifier = "acct",
        schedule = BackupSchedule.MANUAL_ONLY,
        changedFilePolicy = ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP,
        encryptionEnabled = false,
        encryptedPasswordBlob = null,
        encryptionParams = null,
        retentionPolicy = RetentionPolicy.KeepAll,
        networkPolicy = NetworkPolicy.ANY,
        syncLaterChangesOnly = true,
        baselineCompletedAt = null,
        createdAt = 1_000_000L,
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

    fun file(path: String) = LocalFileInfo(path, mockk<Uri>(), size = 100L, mtime = 500L)

    class Harness {
        val configDao = mockk<BackupConfigDao>(relaxed = true)
        val runDao = mockk<BackupRunDao>(relaxed = true)
        val messageDao = mockk<BackupMessageDao>(relaxed = true)
        val indexDao = mockk<UploadedFileIndexDao>(relaxed = true)
        val committedStatus = slot<BackupRunStatus>()
        val messages = mutableListOf<BackupMessageEntity>()

        fun runner(scanner: ILocalFileScanner): BackupRunner {
            coEvery { configDao.getByIdOnce("cfg") } returns config
            coEvery { indexDao.getCurrentVersion(any(), any()) } returns null
            coEvery {
                runDao.markComplete(any(), any(), capture(committedStatus), any(), any(), any(), any())
            } returns Unit
            coEvery { messageDao.coalesceInsert(capture(messages)) } returns Unit
            return BackupRunner(
                context = mockk<Context>(relaxed = true),
                authorizer = mockk<ICloudAuthorizer>(relaxed = true),
                cipher = mockk<IFvc1Cipher>(relaxed = true),
                encryptionRepository = mockk<IEncryptionRepository>(relaxed = true),
                backupConfigDao = configDao,
                uploadedFileIndexDao = indexDao,
                backupMessageDao = messageDao,
                backupRunDao = runDao,
                settingsRepository = mockk<IAppSettingsRepository>(relaxed = true),
                dispatchers = mockk<IDispatchers>(relaxed = true),
                scheduler = mockk<IBackupScheduler>(relaxed = true),
                fileScanner = scanner,
            )
        }
    }

    "clean baseline pass records the tree, marks it complete, and reports BASELINE_RECORDED + Success" {
        runTest {
            val h = Harness()
            val scanner = FakeLocalFileScanner(listOf(file("a.txt"), file("b.txt")))

            val result = h.runner(scanner).runBackup("cfg")

            result.shouldBeInstanceOf<RunResult.Success>()
            h.committedStatus.captured shouldBe BackupRunStatus.UP_TO_DATE
            h.messages.map { it.type } shouldContain MessageType.BASELINE_RECORDED
            coVerify(exactly = 1) { h.configDao.updateBaselineCompleted("cfg", any()) }
        }
    }

    "cooperatively stopped baseline pass commits INITIAL_SYNC_IN_PROGRESS and does not complete the baseline" {
        runTest {
            val h = Harness()
            val control = BackupRunControl()
            control.requestStop() // already stopping before the first file is reached
            val scanner = FakeLocalFileScanner(listOf(file("a.txt")))

            val result = h.runner(scanner).runBackup("cfg", control)

            result.shouldBeInstanceOf<RunResult.Success>()
            h.committedStatus.captured shouldBe BackupRunStatus.INITIAL_SYNC_IN_PROGRESS
            h.messages.map { it.type } shouldNotContain MessageType.BASELINE_RECORDED
            coVerify(exactly = 0) { h.configDao.updateBaselineCompleted(any(), any()) }
        }
    }

    "inaccessible source fails the baseline pass with FOLDER_UNREADABLE + FatalError" {
        runTest {
            val h = Harness()
            val scanner = FakeLocalFileScanner(inaccessible = true)

            val result = h.runner(scanner).runBackup("cfg")

            result.shouldBeInstanceOf<RunResult.FatalError>()
            h.committedStatus.captured shouldBe BackupRunStatus.FAILED
            h.messages.map { it.type } shouldContain MessageType.FOLDER_UNREADABLE
            coVerify(exactly = 0) { h.configDao.updateBaselineCompleted(any(), any()) }
        }
    }
})
