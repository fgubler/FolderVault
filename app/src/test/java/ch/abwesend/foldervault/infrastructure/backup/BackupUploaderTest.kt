package ch.abwesend.folderVault.infrastructure.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import ch.abwesend.foldervault.domain.cloud.CloudFile
import ch.abwesend.foldervault.domain.cloud.CloudQuotaExceededException
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.logging.ILogger
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.infrastructure.backup.BackupUploader
import ch.abwesend.foldervault.infrastructure.backup.FolderPathCache
import ch.abwesend.foldervault.infrastructure.backup.RunSummary
import ch.abwesend.foldervault.infrastructure.backup.UploadMode
import ch.abwesend.foldervault.infrastructure.backup.UploadTask
import ch.abwesend.foldervault.infrastructure.backup.UploadTier
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.ByteArrayInputStream
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class BackupUploaderTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val testDispatcher = UnconfinedTestDispatcher()

    val noOpLogger = mockk<ILogger>(relaxed = true)

    beforeTest { LoggerProvider.configure { noOpLogger } }

    fun makeConfig(id: String) = BackupConfigEntity(
        id = id,
        displayName = "Test",
        sourceTreeUri = "",
        cloudProvider = "google_drive",
        cloudSubFolderId = "sub-id",
        cloudSubFolderName = "test_sub",
        cloudAccountIdentifier = "user@test.com",
        schedule = BackupSchedule.DAILY,
        changedFilePolicy = ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP,
        encryptionEnabled = false,
        encryptedPasswordBlob = null,
        encryptionParams = null,
        retentionPolicy = RetentionPolicy.KeepAll,
        networkPolicy = NetworkPolicy.WIFI_ONLY,
        createdAt = 0L,
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

    fun makeContext(): Context {
        val contentResolver = mockk<ContentResolver>()
        every { contentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        return mockk<Context> {
            every { this@mockk.contentResolver } returns contentResolver
            every { getString(any()) } returns ""
        }
    }

    fun makeUploader(context: Context, cloudProvider: ICloudStorageProvider): Pair<BackupUploader, FolderPathCache> {
        val dispatchers = mockk<IDispatchers> { every { io } returns testDispatcher }
        val uploader = BackupUploader(
            context = context,
            cipher = mockk<IFvc1Cipher>(),
            authorizer = mockk<ICloudAuthorizer>(),
            uploadedFileIndexDao = mockk<UploadedFileIndexDao>(relaxed = true),
            backupMessageDao = mockk<BackupMessageDao>(relaxed = true),
            dispatchers = dispatchers,
            cloudProvider = cloudProvider,
        )
        return uploader to FolderPathCache(cloudProvider)
    }

    "first CloudQuotaExceededException does not set quotaExceeded" {
        val cloudProvider = mockk<ICloudStorageProvider> {
            coEvery { uploadFile(any(), any(), any(), any(), any()) } returns ErrorResult(CloudQuotaExceededException())
        }
        val (uploader, folderCache) = makeUploader(makeContext(), cloudProvider)
        val stagingDir = Files.createTempDirectory("fv-uploader-test").toFile()
        try {
            val channel = Channel<UploadTask>(1)
            channel.send(UploadTask("file1.txt", mockk<Uri>(), 100L, 0L, UploadMode.NEW, UploadTier.NORMAL))
            channel.close()

            val summary = RunSummary()
            uploader.processChannel(makeConfig("cfg-1"), channel, "run-1", stagingDir, folderCache, null, null, summary)

            summary.consecutiveQuotaCount shouldBe 1
            summary.quotaExceeded shouldBe false
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    "OVERSIZED task is uploaded and increments oversizedUploaded" {
        val cloudProvider = mockk<ICloudStorageProvider>(relaxed = true) {
            coEvery {
                uploadFile(any(), any(), any(), any(), any())
            } returns SuccessResult(CloudFile("cf-1", "big.iso"))
        }
        val (uploader, folderCache) = makeUploader(makeContext(), cloudProvider)
        val stagingDir = Files.createTempDirectory("fv-uploader-test").toFile()
        try {
            val channel = Channel<UploadTask>(1)
            channel.send(
                UploadTask("big.iso", mockk<Uri>(), 500L, 0L, UploadMode.NEW, UploadTier.OVERSIZED),
            )
            channel.close()

            val summary = RunSummary()
            uploader.processChannel(
                makeConfig("cfg-oversized"),
                channel,
                "run-x",
                stagingDir,
                folderCache,
                null,
                null,
                summary,
            )

            summary.oversizedUploaded shouldBe 1
            summary.filesUploaded shouldBe 1
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    "OVERSIZED task after hitTimeBudget increments oversizedDeferred" {
        val cloudProvider = mockk<ICloudStorageProvider>(relaxed = true)
        val backupMessageDao = mockk<BackupMessageDao>(relaxed = true)
        val dispatchers = mockk<IDispatchers> { every { io } returns testDispatcher }
        val uploader = BackupUploader(
            context = makeContext(),
            cipher = mockk<IFvc1Cipher>(),
            authorizer = mockk<ICloudAuthorizer>(),
            uploadedFileIndexDao = mockk<UploadedFileIndexDao>(relaxed = true),
            backupMessageDao = backupMessageDao,
            dispatchers = dispatchers,
            cloudProvider = cloudProvider,
        )
        val folderCache = FolderPathCache(cloudProvider)
        val stagingDir = Files.createTempDirectory("fv-uploader-test").toFile()
        try {
            val channel = Channel<UploadTask>(1)
            channel.send(
                UploadTask("big.iso", mockk<Uri>(), 500L, 0L, UploadMode.NEW, UploadTier.OVERSIZED),
            )
            channel.close()

            val summary = RunSummary().apply { hitTimeBudget = true }
            uploader.processChannel(
                makeConfig("cfg-deferred"),
                channel,
                "run-x",
                stagingDir,
                folderCache,
                null,
                null,
                summary,
            )

            summary.oversizedDeferred shouldBe 1
            summary.filesUploaded shouldBe 0
            coVerify(exactly = 1) {
                backupMessageDao.coalesceInsert(
                    match { it.type == MessageType.FILE_TOO_LARGE && it.severity == MessageSeverity.INFO },
                )
            }
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    "two OVERSIZED tasks deferred produce exactly one FILE_TOO_LARGE message" {
        val cloudProvider = mockk<ICloudStorageProvider>(relaxed = true)
        val backupMessageDao = mockk<BackupMessageDao>(relaxed = true)
        val dispatchers = mockk<IDispatchers> { every { io } returns testDispatcher }
        val uploader = BackupUploader(
            context = makeContext(),
            cipher = mockk<IFvc1Cipher>(),
            authorizer = mockk<ICloudAuthorizer>(),
            uploadedFileIndexDao = mockk<UploadedFileIndexDao>(relaxed = true),
            backupMessageDao = backupMessageDao,
            dispatchers = dispatchers,
            cloudProvider = cloudProvider,
        )
        val folderCache = FolderPathCache(cloudProvider)
        val stagingDir = Files.createTempDirectory("fv-uploader-test").toFile()
        try {
            val uri = mockk<Uri>()
            val channel = Channel<UploadTask>(2)
            channel.send(UploadTask("a.iso", uri, 500L, 0L, UploadMode.NEW, UploadTier.OVERSIZED))
            channel.send(UploadTask("b.iso", uri, 600L, 0L, UploadMode.NEW, UploadTier.OVERSIZED))
            channel.close()

            val summary = RunSummary().apply { hitTimeBudget = true }
            uploader.processChannel(
                makeConfig("cfg-two"),
                channel,
                "run-y",
                stagingDir,
                folderCache,
                null,
                null,
                summary,
            )

            summary.oversizedDeferred shouldBe 2
            coVerify(exactly = 1) {
                backupMessageDao.coalesceInsert(match { it.type == MessageType.FILE_TOO_LARGE })
            }
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    "no OVERSIZED tasks deferred means no FILE_TOO_LARGE message" {
        val cloudProvider = mockk<ICloudStorageProvider>(relaxed = true)
        val backupMessageDao = mockk<BackupMessageDao>(relaxed = true)
        val dispatchers = mockk<IDispatchers> { every { io } returns testDispatcher }
        val uploader = BackupUploader(
            context = makeContext(),
            cipher = mockk<IFvc1Cipher>(),
            authorizer = mockk<ICloudAuthorizer>(),
            uploadedFileIndexDao = mockk<UploadedFileIndexDao>(relaxed = true),
            backupMessageDao = backupMessageDao,
            dispatchers = dispatchers,
            cloudProvider = cloudProvider,
        )
        val folderCache = FolderPathCache(cloudProvider)
        val stagingDir = Files.createTempDirectory("fv-uploader-test").toFile()
        try {
            val channel = Channel<UploadTask>(1)
            channel.send(
                UploadTask("small.txt", mockk<Uri>(), 100L, 0L, UploadMode.NEW, UploadTier.NORMAL),
            )
            channel.close()

            val summary = RunSummary().apply { hitTimeBudget = true }
            uploader.processChannel(
                makeConfig("cfg-none"),
                channel,
                "run-z",
                stagingDir,
                folderCache,
                null,
                null,
                summary,
            )

            summary.oversizedDeferred shouldBe 0
            coVerify(exactly = 0) {
                backupMessageDao.coalesceInsert(match { it.type == MessageType.FILE_TOO_LARGE })
            }
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    "NEW upload passes an empty excludeIds set to the cloud provider" {
        val cloudProvider = mockk<ICloudStorageProvider>(relaxed = true) {
            coEvery { uploadFile(any(), any(), any(), any(), any()) } returns SuccessResult(CloudFile("c-1", "a.txt"))
        }
        val (uploader, folderCache) = makeUploader(makeContext(), cloudProvider)
        val stagingDir = Files.createTempDirectory("fv-uploader-test").toFile()
        try {
            val channel = Channel<UploadTask>(1)
            channel.send(UploadTask("a.txt", mockk<Uri>(), 100L, 0L, UploadMode.NEW, UploadTier.NORMAL))
            channel.close()

            uploader.processChannel(
                makeConfig("cfg-new"),
                channel,
                "run-new",
                stagingDir,
                folderCache,
                null,
                null,
                RunSummary(),
            )

            coVerify(exactly = 1) {
                cloudProvider.uploadFile(any(), any(), any(), any(), eq(emptySet()))
            }
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    "CHANGED_OVERWRITE upload passes previousCloudFileId as excludeIds" {
        val cloudProvider = mockk<ICloudStorageProvider>(relaxed = true) {
            coEvery { uploadFile(any(), any(), any(), any(), any()) } returns SuccessResult(CloudFile("c-new", "a.txt"))
        }
        val (uploader, folderCache) = makeUploader(makeContext(), cloudProvider)
        val stagingDir = Files.createTempDirectory("fv-uploader-test").toFile()
        try {
            val channel = Channel<UploadTask>(1)
            channel.send(
                UploadTask(
                    relativePath = "a.txt",
                    documentUri = mockk<Uri>(),
                    localSize = 100L,
                    localMtime = 0L,
                    mode = UploadMode.CHANGED_OVERWRITE,
                    tier = UploadTier.NORMAL,
                    previousCloudFileId = "prev-cloud-id",
                ),
            )
            channel.close()

            uploader.processChannel(
                makeConfig("cfg-ovw"),
                channel,
                "run-ovw",
                stagingDir,
                folderCache,
                null,
                null,
                RunSummary(),
            )

            coVerify(exactly = 1) {
                cloudProvider.uploadFile(any(), any(), any(), any(), eq(setOf("prev-cloud-id")))
            }
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    "retry-induced duplicate is reused, not re-uploaded — second attempt is short-circuited by the provider" {
        // Models the GoogleDriveRepository contract: when a transient error fires AFTER Drive
        // accepted the upload, the next attempt finds the just-created file by name and returns
        // it. From the BackupUploader's perspective: a single uploadFile call succeeds, no
        // duplicate, no filesFailed.
        val cloudProvider = mockk<ICloudStorageProvider>(relaxed = true) {
            coEvery {
                uploadFile(any(), any(), any(), any(), any())
            } returns SuccessResult(CloudFile("recovered-cloud-id", "a.txt"))
        }
        val (uploader, folderCache) = makeUploader(makeContext(), cloudProvider)
        val stagingDir = Files.createTempDirectory("fv-uploader-test").toFile()
        try {
            val channel = Channel<UploadTask>(1)
            channel.send(UploadTask("a.txt", mockk<Uri>(), 100L, 0L, UploadMode.NEW, UploadTier.NORMAL))
            channel.close()

            val summary = RunSummary()
            uploader.processChannel(
                makeConfig("cfg-recover"),
                channel,
                "run-recover",
                stagingDir,
                folderCache,
                null,
                null,
                summary,
            )

            summary.filesUploaded shouldBe 1
            summary.filesFailed shouldBe 0
            coVerify(exactly = 1) { cloudProvider.uploadFile(any(), any(), any(), any(), any()) }
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    "second consecutive CloudQuotaExceededException sets quotaExceeded = true" {
        val cloudProvider = mockk<ICloudStorageProvider> {
            coEvery { uploadFile(any(), any(), any(), any(), any()) } returns ErrorResult(CloudQuotaExceededException())
        }
        val (uploader, folderCache) = makeUploader(makeContext(), cloudProvider)
        val stagingDir = Files.createTempDirectory("fv-uploader-test").toFile()
        try {
            val channel = Channel<UploadTask>(3)
            val uri = mockk<Uri>()
            channel.send(UploadTask("file1.txt", uri, 100L, 0L, UploadMode.NEW, UploadTier.NORMAL))
            channel.send(UploadTask("file2.txt", uri, 100L, 0L, UploadMode.NEW, UploadTier.NORMAL))
            channel.send(UploadTask("file3.txt", uri, 100L, 0L, UploadMode.NEW, UploadTier.NORMAL))
            channel.close()

            val summary = RunSummary()
            uploader.processChannel(makeConfig("cfg-2"), channel, "run-2", stagingDir, folderCache, null, null, summary)

            summary.quotaExceeded shouldBe true
            summary.consecutiveQuotaCount shouldBe 2
        } finally {
            stagingDir.deleteRecursively()
        }
    }
})
