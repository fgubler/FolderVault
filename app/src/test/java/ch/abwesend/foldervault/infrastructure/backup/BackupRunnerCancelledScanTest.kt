package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import android.net.Uri
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.cloud.CloudAuthResult
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.logging.ILogger
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupRunDao
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/**
 * End-to-end coverage of the [CrossRunProgress.PERSIST] commit for hard-cancelled runs (RV-16 /
 * RV-17): drives the real [BackupRunner.runBackup] pipeline with a scanner that suspends until the
 * run is cancelled, pinning that
 * - a run cancelled *mid-scan* (discovered total still 0) keeps the previous run's
 *   `totalFilesDiscovered` instead of regressing it to 0 — losing it would drop the next resume
 *   of an in-flight initial sync from the foreground service back to WorkManager (RV-17), and
 * - a run cancelled *after* the scan persists the freshly discovered total (RV-16).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRunnerCancelledScanTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val testDispatcher = UnconfinedTestDispatcher()

    val noOpLogger = object : ILogger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warning(message: String, error: Throwable?) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    beforeTest { LoggerProvider.configure { noOpLogger } }

    /** An established initial sync: a previous run discovered 42 files and uploaded 5 of them. */
    val config = BackupConfigEntityFixture(
        totalFilesDiscovered = 42,
        filesUploadedTotal = 5,
    ).entity

    class Harness(dispatcher: CoroutineDispatcher) {
        val configDao = mockk<BackupConfigDao>(relaxed = true)
        val runDao = mockk<BackupRunDao>(relaxed = true)
        val messageDao = mockk<BackupMessageDao>(relaxed = true)
        val indexDao = mockk<UploadedFileIndexDao>(relaxed = true)
        val committedStatus = slot<BackupRunStatus>()
        private val dispatchers = mockk<IDispatchers> {
            every { io } returns dispatcher
            every { default } returns dispatcher
        }

        fun runner(entity: BackupConfigEntity, scanner: ILocalFileScanner): BackupRunner {
            val context = mockk<Context>(relaxed = true) {
                every { cacheDir } returns Files.createTempDirectory("fv-runner-cancel-test").toFile()
            }
            coEvery { configDao.getByIdOnce(entity.id) } returns entity
            coEvery {
                runDao.markComplete(any(), any(), capture(committedStatus), any(), any(), any(), any())
            } returns Unit
            val authorizer = mockk<ICloudAuthorizer> {
                coEvery { authorize(any()) } returns
                    CloudAuthResult.Authorized(mockk<ICloudStorageProvider>(relaxed = true))
            }
            val settingsRepository = mockk<IAppSettingsRepository> {
                every { settings } returns flowOf(AppSettings())
            }
            return BackupRunner(
                context = context,
                authorizer = authorizer,
                cipher = mockk<IFvc1Cipher>(relaxed = true),
                encryptionRepository = mockk<IEncryptionRepository>(relaxed = true),
                backupConfigDao = configDao,
                uploadedFileIndexDao = indexDao,
                backupMessageDao = messageDao,
                backupRunDao = runDao,
                settingsRepository = settingsRepository,
                dispatchers = dispatchers,
                scheduler = mockk<IBackupScheduler>(relaxed = true),
                fileScanner = scanner,
            )
        }
    }

    "run hard-cancelled mid-scan keeps the previous run's discovered total instead of regressing it to 0" {
        runTest(testDispatcher) {
            val h = Harness(testDispatcher)
            val scanStarted = CompletableDeferred<Unit>()
            val hangingScanner = object : ILocalFileScanner {
                override suspend fun scan(
                    config: BackupConfigEntity,
                    summary: RunSummary,
                ): List<LocalFileInfo> {
                    scanStarted.complete(Unit)
                    awaitCancellation()
                }
            }

            val job = launch { h.runner(config, hangingScanner).runBackup(config.id) }
            scanStarted.await()
            job.cancelAndJoin()

            h.committedStatus.captured shouldBe BackupRunStatus.CANCELLED
            coVerify(exactly = 1) {
                h.configDao.updateCrossRunProgress(
                    id = config.id,
                    totalFilesDiscovered = 42,
                    filesUploadedTotal = 5,
                )
            }
        }
    }

    "run hard-cancelled after the scan persists the freshly discovered total" {
        runTest(testDispatcher) {
            val h = Harness(testDispatcher)
            val scanCompleted = CompletableDeferred<Unit>()
            val scanner = object : ILocalFileScanner {
                override suspend fun scan(
                    config: BackupConfigEntity,
                    summary: RunSummary,
                ): List<LocalFileInfo> =
                    List(3) { i -> LocalFileInfo("file$i.txt", mockk<Uri>(), size = 100L, mtime = 500L) }
            }
            // Hang the analyzer right after the scan (the discovered total is already recorded on
            // the summary at this point), so the cancellation strikes mid-analysis.
            coEvery { h.indexDao.getCurrentVersion(any(), any()) } coAnswers {
                scanCompleted.complete(Unit)
                awaitCancellation()
            }

            val job = launch { h.runner(config, scanner).runBackup(config.id) }
            scanCompleted.await()
            job.cancelAndJoin()

            h.committedStatus.captured shouldBe BackupRunStatus.CANCELLED
            coVerify(exactly = 1) {
                h.configDao.updateCrossRunProgress(
                    id = config.id,
                    totalFilesDiscovered = 3,
                    filesUploadedTotal = 5,
                )
            }
        }
    }
})

/** Builds the entity fixture with the cross-run counters this test cares about. */
private class BackupConfigEntityFixture(
    totalFilesDiscovered: Int,
    filesUploadedTotal: Int,
) {
    val entity = BackupConfigEntity(
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
        syncLaterChangesOnly = false,
        baselineCompletedAt = null,
        createdAt = 1_000_000L,
        lastRunAt = null,
        lastRunStatus = BackupRunStatus.IDLE,
        filesUploaded = 0,
        filesSkipped = 0,
        filesFailed = 0,
        bytesUploaded = 0L,
        totalFilesDiscovered = totalFilesDiscovered,
        filesUploadedTotal = filesUploadedTotal,
        lastRunCompletedNormally = false,
    )
}
