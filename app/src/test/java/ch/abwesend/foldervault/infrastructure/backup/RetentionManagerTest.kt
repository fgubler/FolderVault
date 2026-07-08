package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.cloud.CloudEntry
import ch.abwesend.foldervault.domain.cloud.CloudFile
import ch.abwesend.foldervault.domain.cloud.CloudFolder
import ch.abwesend.foldervault.domain.cloud.CloudNotFoundException
import ch.abwesend.foldervault.domain.cloud.CloudTransientException
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.cloud.UploadContent
import ch.abwesend.foldervault.domain.logging.ILogger
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.UploadedFileIndexEntity
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Records every deleteFile call and returns a scripted result per file id, defaulting to success.
 */
private class FakeCloudProvider(
    private val results: Map<String, BinaryResult<Unit, Exception>> = emptyMap(),
) : ICloudStorageProvider {
    val deletedIds = mutableListOf<String>()

    override suspend fun deleteFile(fileId: String): BinaryResult<Unit, Exception> {
        deletedIds += fileId
        return results[fileId] ?: SuccessResult(Unit)
    }

    override suspend fun getAccountIdentifier(): BinaryResult<String, Exception> = notNeeded()
    override suspend fun createRootFolder(): BinaryResult<CloudFolder, Exception> = notNeeded()
    override suspend fun hasFolderAccess(folderId: String): BinaryResult<Boolean, Exception> = notNeeded()
    override suspend fun getOrCreateChildFolder(parentId: String, name: String): BinaryResult<CloudFolder, Exception> =
        notNeeded()
    override suspend fun listChildren(folderId: String): BinaryResult<List<CloudEntry>, Exception> = notNeeded()
    override suspend fun uploadFile(
        parentId: String,
        remoteName: String,
        mimeType: String,
        content: UploadContent,
        excludeIds: Set<String>,
    ): BinaryResult<CloudFile, Exception> = notNeeded()
    override suspend fun readRootMetadata(rootFolderId: String, name: String): BinaryResult<ByteArray?, Exception> =
        notNeeded()
    override suspend fun writeRootMetadata(
        rootFolderId: String,
        name: String,
        bytes: ByteArray,
    ): BinaryResult<Unit, Exception> = notNeeded()

    private fun notNeeded(): Nothing = throw UnsupportedOperationException("not needed for this test")
}

/**
 * In-memory fake of the parts of [UploadedFileIndexDao] the retention path touches.
 */
private class FakeUploadedFileIndexDao(
    initial: List<UploadedFileIndexEntity> = emptyList(),
) : UploadedFileIndexDao {
    val rows = initial.toMutableList()

    override suspend fun getPendingDeletions(configId: String): List<UploadedFileIndexEntity> =
        rows.filter { it.backupConfigId == configId && it.pendingDeletionCloudFileId != null }

    override suspend fun clearPendingDeletion(id: Long) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(pendingDeletionCloudFileId = null)
    }

    override suspend fun markPendingDeletion(id: Long, cloudFileId: String) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(pendingDeletionCloudFileId = cloudFileId)
    }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun getVersionHistory(
        backupConfigId: String,
        relativePath: String,
    ): List<UploadedFileIndexEntity> =
        rows.filter { it.backupConfigId == backupConfigId && it.relativePath == relativePath }
            .sortedByDescending { it.uploadedAt }

    override suspend fun getDistinctPaths(configId: String): List<String> =
        rows.filter { it.backupConfigId == configId }.map { it.relativePath }.distinct()

    override fun getCurrentVersions(backupConfigId: String) = notNeeded()
    override suspend fun getCurrentVersion(backupConfigId: String, relativePath: String) = notNeeded()
    override suspend fun insert(entity: UploadedFileIndexEntity): Long = notNeeded()
    override suspend fun clearCurrentFlag(backupConfigId: String, relativePath: String) = notNeeded()
    override suspend fun pruneOldVersions(backupConfigId: String, relativePath: String, keepCount: Int) = notNeeded()
    override suspend fun pruneVersionsOlderThan(backupConfigId: String, cutoffEpochMs: Long) = notNeeded()
    override suspend fun deleteAllForConfig(backupConfigId: String) = notNeeded()
    override suspend fun getCurrentVersionList(configId: String) = notNeeded()
    override suspend fun getOldVersionsOlderThan(configId: String, cutoffEpochMs: Long) = notNeeded()

    private fun notNeeded(): Nothing = throw UnsupportedOperationException("not needed for this test")
}

private fun pendingRow(id: Long, configId: String, pendingCloudFileId: String) = UploadedFileIndexEntity(
    id = id,
    backupConfigId = configId,
    relativePath = "photos/img$id.jpg",
    localLastModified = 0L,
    localSize = 1L,
    cloudFileId = "current-$id",
    remoteName = "img$id.jpg.crypt",
    uploadedAt = 0L,
    isCurrentVersion = true,
    pendingDeletionCloudFileId = pendingCloudFileId,
)

/** A plain (not-yet-pending) index row for a specific version of a path. */
private fun versionRow(
    id: Long,
    configId: String,
    relativePath: String,
    cloudFileId: String,
    uploadedAt: Long,
    isCurrent: Boolean,
) = UploadedFileIndexEntity(
    id = id,
    backupConfigId = configId,
    relativePath = relativePath,
    localLastModified = 0L,
    localSize = 1L,
    cloudFileId = cloudFileId,
    remoteName = "$relativePath.crypt",
    uploadedAt = uploadedAt,
    isCurrentVersion = isCurrent,
    pendingDeletionCloudFileId = null,
)

private fun configWithPolicy(id: String, policy: RetentionPolicy) = BackupConfigEntity(
    id = id,
    displayName = "Test",
    sourceTreeUri = "content://tree",
    cloudProvider = "googledrive",
    cloudSubFolderId = "sub",
    cloudSubFolderName = "sub_abc123",
    cloudAccountIdentifier = "acct",
    schedule = BackupSchedule.MANUAL_ONLY,
    changedFilePolicy = ChangedFilePolicy.OVERWRITE,
    encryptionEnabled = false,
    encryptedPasswordBlob = null,
    encryptionParams = null,
    retentionPolicy = policy,
    networkPolicy = NetworkPolicy.ANY,
    createdAt = 0L,
    lastRunAt = null,
    lastRunStatus = BackupRunStatus.IDLE,
    filesUploaded = 0,
    filesSkipped = 0,
    filesFailed = 0,
    bytesUploaded = 0L,
    totalFilesDiscovered = 0,
    filesUploadedTotal = 0,
    lastRunCompletedNormally = true,
)

class RetentionManagerTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val noOpLogger = object : ILogger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warning(message: String, error: Throwable?) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    beforeTest { LoggerProvider.configure { noOpLogger } }

    "reap clears the pending marker when the cloud file is already gone (404)" {
        val dao = FakeUploadedFileIndexDao(listOf(pendingRow(1L, "cfg", "gone-file")))
        val cloud = FakeCloudProvider(mapOf("gone-file" to ErrorResult(CloudNotFoundException())))
        val manager = RetentionManager(dao, cloud)

        manager.reapPendingDeletions("cfg")

        cloud.deletedIds shouldBe listOf("gone-file")
        dao.rows.single().pendingDeletionCloudFileId shouldBe null
    }

    "reap clears the pending marker on a successful delete" {
        val dao = FakeUploadedFileIndexDao(listOf(pendingRow(1L, "cfg", "live-file")))
        val cloud = FakeCloudProvider()
        val manager = RetentionManager(dao, cloud)

        manager.reapPendingDeletions("cfg")

        dao.rows.single().pendingDeletionCloudFileId shouldBe null
    }

    "reap keeps the pending marker on a transient failure so it retries next run" {
        val dao = FakeUploadedFileIndexDao(listOf(pendingRow(1L, "cfg", "flaky-file")))
        val cloud = FakeCloudProvider(mapOf("flaky-file" to ErrorResult(CloudTransientException())))
        val manager = RetentionManager(dao, cloud)

        manager.reapPendingDeletions("cfg")

        dao.rows.single().pendingDeletionCloudFileId shouldBe "flaky-file"
    }

    "retention keeps and marks the row when the cloud delete fails transiently (no orphan)" {
        // Two versions of one path, KeepLastN(1) → the older version must be deleted. The cloud
        // delete fails transiently, so the row must survive (otherwise the object is orphaned).
        val current = versionRow(1L, "cfg", "a.jpg", cloudFileId = "cloud-new", uploadedAt = 2L, isCurrent = true)
        val old = versionRow(2L, "cfg", "a.jpg", cloudFileId = "cloud-old", uploadedAt = 1L, isCurrent = false)
        val dao = FakeUploadedFileIndexDao(listOf(current, old))
        val cloud = FakeCloudProvider(mapOf("cloud-old" to ErrorResult(CloudTransientException())))
        val manager = RetentionManager(dao, cloud)

        manager.applyRetention(configWithPolicy("cfg", RetentionPolicy.KeepLastN(1)))

        cloud.deletedIds shouldBe listOf("cloud-old")
        val oldRow = dao.rows.single { it.id == 2L }
        oldRow.pendingDeletionCloudFileId shouldBe "cloud-old"
    }

    "retention deletes the row outright when the cloud delete succeeds" {
        val current = versionRow(1L, "cfg", "a.jpg", cloudFileId = "cloud-new", uploadedAt = 2L, isCurrent = true)
        val old = versionRow(2L, "cfg", "a.jpg", cloudFileId = "cloud-old", uploadedAt = 1L, isCurrent = false)
        val dao = FakeUploadedFileIndexDao(listOf(current, old))
        val cloud = FakeCloudProvider()
        val manager = RetentionManager(dao, cloud)

        manager.applyRetention(configWithPolicy("cfg", RetentionPolicy.KeepLastN(1)))

        dao.rows.map { it.id } shouldBe listOf(1L)
    }

    "reap drops the whole row once a retention-marked own object is finally gone" {
        // A retention row that marked its OWN cloud object (marker == cloudFileId): once the
        // delete finally lands the row itself must go, not merely have its marker cleared.
        val row = versionRow(5L, "cfg", "a.jpg", cloudFileId = "cloud-own", uploadedAt = 1L, isCurrent = false)
            .copy(pendingDeletionCloudFileId = "cloud-own")
        val dao = FakeUploadedFileIndexDao(listOf(row))
        val cloud = FakeCloudProvider() // succeeds
        val manager = RetentionManager(dao, cloud)

        manager.reapPendingDeletions("cfg")

        dao.rows.isEmpty() shouldBe true
    }
})
