package ch.abwesend.foldervault.infrastructure.backup

import android.net.Uri
import ch.abwesend.foldervault.domain.logging.ILogger
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.UploadedFileIndexEntity
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

/**
 * Scripted scanner: returns the canned file list, optionally flags the source as inaccessible
 * or triggers a side effect (e.g. a stop request) when the scan happens.
 */
private class FakeLocalFileScanner(
    private val files: List<LocalFileInfo> = emptyList(),
    private val inaccessible: Boolean = false,
) : ILocalFileScanner {
    override suspend fun scan(config: BackupConfigEntity, summary: RunSummary): List<LocalFileInfo> {
        if (inaccessible) summary.sourceFolderInaccessible = true
        return if (inaccessible) emptyList() else files
    }
}

/**
 * In-memory fake of the parts of [UploadedFileIndexDao] the baseline recorder touches.
 * [onInsert] allows tests to trigger side effects (e.g. requesting a stop) mid-pass.
 */
private class FakeIndexDao(
    initial: List<UploadedFileIndexEntity> = emptyList(),
    var onInsert: (UploadedFileIndexEntity) -> Unit = {},
) : UploadedFileIndexDao {
    val rows = initial.toMutableList()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    override suspend fun getCurrentVersion(backupConfigId: String, relativePath: String) =
        rows.firstOrNull {
            it.backupConfigId == backupConfigId && it.relativePath == relativePath && it.isCurrentVersion
        }

    override suspend fun insert(entity: UploadedFileIndexEntity): Long {
        val id = nextId++
        rows.add(entity.copy(id = id))
        onInsert(entity)
        return id
    }

    override suspend fun clearCurrentFlag(backupConfigId: String, relativePath: String) {
        rows.replaceAll {
            if (it.backupConfigId == backupConfigId && it.relativePath == relativePath) {
                it.copy(isCurrentVersion = false)
            } else {
                it
            }
        }
    }

    override suspend fun deleteSupersededBaselineRows(backupConfigId: String, relativePath: String) {
        rows.removeAll {
            it.backupConfigId == backupConfigId && it.relativePath == relativePath &&
                it.isBaseline && !it.isCurrentVersion
        }
    }

    override fun getCurrentVersions(backupConfigId: String): Flow<List<UploadedFileIndexEntity>> = notNeeded()
    override suspend fun getVersionHistory(backupConfigId: String, relativePath: String) = notNeeded()
    override suspend fun clearPendingDeletion(id: Long) = notNeeded()
    override suspend fun markPendingDeletion(id: Long, cloudFileId: String) = notNeeded()
    override suspend fun getPendingDeletions(configId: String) = notNeeded()
    override suspend fun pruneOldVersions(backupConfigId: String, relativePath: String, keepCount: Int) = notNeeded()
    override suspend fun pruneVersionsOlderThan(backupConfigId: String, cutoffEpochMs: Long) = notNeeded()
    override suspend fun deleteAllForConfig(backupConfigId: String) = notNeeded()
    override suspend fun getCurrentVersionList(configId: String) = notNeeded()
    override suspend fun getDistinctPaths(configId: String) = notNeeded()
    override suspend fun getOldVersionsOlderThan(configId: String, cutoffEpochMs: Long) = notNeeded()
    override suspend fun deleteById(id: Long) = notNeeded()

    private fun notNeeded(): Nothing = throw UnsupportedOperationException("not needed for this test")
}

/** Records [updateBaselineCompleted] calls; everything else is unsupported. */
private class FakeConfigDao : BackupConfigDao {
    var baselineCompletedAt: Long? = null

    override suspend fun updateBaselineCompleted(id: String, completedAt: Long) {
        baselineCompletedAt = completedAt
    }

    override fun getAll(): Flow<List<BackupConfigEntity>> = notNeeded()
    override fun getById(id: String): Flow<BackupConfigEntity?> = notNeeded()
    override suspend fun getByIdOnce(id: String): BackupConfigEntity? = notNeeded()
    override suspend fun upsert(entity: BackupConfigEntity) = notNeeded()
    override suspend fun delete(entity: BackupConfigEntity) = notNeeded()
    override suspend fun deleteById(id: String) = notNeeded()

    @Suppress("LongParameterList")
    override suspend fun updateRunStats(
        id: String,
        lastRunAt: Long,
        lastRunStatus: BackupRunStatus,
        filesUploaded: Int,
        filesSkipped: Int,
        filesFailed: Int,
        bytesUploaded: Long,
        lastRunCompletedNormally: Boolean,
    ) = notNeeded()
    override suspend fun updateCrossRunProgress(id: String, totalFilesDiscovered: Int, filesUploadedTotal: Int) =
        notNeeded()
    override suspend fun updatePaused(id: String, paused: Boolean) = notNeeded()

    private fun notNeeded(): Nothing = throw UnsupportedOperationException("not needed for this test")
}

private fun fileInfo(path: String, size: Long = 100L, mtime: Long? = 1_000L) =
    LocalFileInfo(relativePath = path, uri = mockk<Uri>(), size = size, mtime = mtime)

private fun baselineConfig(id: String = "cfg") = BackupConfigEntity(
    id = id,
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

class BaselineRecorderTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val noOpLogger = object : ILogger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warning(message: String, error: Throwable?) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    beforeTest { LoggerProvider.configure { noOpLogger } }

    "records every scanned file as a current baseline row without uploading" {
        runTest {
            val scanner = FakeLocalFileScanner(
                listOf(fileInfo("a.txt", size = 10L, mtime = 111L), fileInfo("sub/b.txt", size = 20L, mtime = null))
            )
            val indexDao = FakeIndexDao()
            val configDao = FakeConfigDao()
            val summary = RunSummary()
            val control = BackupRunControl()

            BaselineRecorder(scanner, indexDao, configDao)
                .recordBaseline(baselineConfig(), summary, control)

            indexDao.rows.size shouldBe 2
            val rowA = indexDao.rows.single { it.relativePath == "a.txt" }
            rowA.isBaseline.shouldBeTrue()
            rowA.isCurrentVersion.shouldBeTrue()
            rowA.cloudFileId shouldBe ""
            rowA.remoteName shouldBe ""
            rowA.localLastModified shouldBe 111L
            rowA.localSize shouldBe 10L
            val rowB = indexDao.rows.single { it.relativePath == "sub/b.txt" }
            rowB.localLastModified shouldBe 0L // unavailable mtime is stored as 0

            summary.filesSkipped shouldBe 2
            summary.totalFilesDiscovered shouldBe 2
            summary.filesUploaded shouldBe 0
            control.filesDiscovered.value shouldBe 2
        }
    }

    "sets baselineCompletedAt only after a full clean walk" {
        runTest {
            val scanner = FakeLocalFileScanner(listOf(fileInfo("a.txt")))
            val configDao = FakeConfigDao()

            BaselineRecorder(scanner, FakeIndexDao(), configDao)
                .recordBaseline(baselineConfig(), RunSummary(), control = null)

            configDao.baselineCompletedAt.shouldNotBeNull()
        }
    }

    "stop requested mid-pass sets hitTimeBudget and does not mark the baseline complete" {
        runTest {
            val scanner = FakeLocalFileScanner(listOf(fileInfo("a.txt"), fileInfo("b.txt"), fileInfo("c.txt")))
            val control = BackupRunControl()
            val indexDao = FakeIndexDao(onInsert = { control.requestStop() })
            val configDao = FakeConfigDao()
            val summary = RunSummary()

            BaselineRecorder(scanner, indexDao, configDao)
                .recordBaseline(baselineConfig(), summary, control)

            indexDao.rows.size shouldBe 1
            summary.hitTimeBudget.shouldBeTrue()
            configDao.baselineCompletedAt.shouldBeNull()
        }
    }

    "resume skips already-indexed paths and preserves their originally captured metadata" {
        runTest {
            val existing = UploadedFileIndexEntity(
                id = 1L,
                backupConfigId = "cfg",
                relativePath = "a.txt",
                localLastModified = 111L,
                localSize = 10L,
                cloudFileId = "",
                remoteName = "",
                uploadedAt = 500L,
                isCurrentVersion = true,
                isBaseline = true,
            )
            // The file changed between the interrupted pass and the resume — the original
            // baseline row must survive so the change is detected on the first incremental run.
            val scanner = FakeLocalFileScanner(
                listOf(fileInfo("a.txt", size = 999L, mtime = 222L), fileInfo("b.txt"))
            )
            val indexDao = FakeIndexDao(initial = listOf(existing))
            val configDao = FakeConfigDao()
            val summary = RunSummary()

            BaselineRecorder(scanner, indexDao, configDao)
                .recordBaseline(baselineConfig(), summary, control = null)

            indexDao.rows.size shouldBe 2
            val rowA = indexDao.rows.single { it.relativePath == "a.txt" }
            rowA.localLastModified shouldBe 111L
            rowA.localSize shouldBe 10L
            summary.filesSkipped shouldBe 2
            configDao.baselineCompletedAt.shouldNotBeNull()
        }
    }

    "inaccessible source records nothing and does not mark the baseline complete" {
        runTest {
            val scanner = FakeLocalFileScanner(inaccessible = true)
            val indexDao = FakeIndexDao()
            val configDao = FakeConfigDao()
            val summary = RunSummary()

            BaselineRecorder(scanner, indexDao, configDao)
                .recordBaseline(baselineConfig(), summary, control = null)

            indexDao.rows.size shouldBe 0
            summary.sourceFolderInaccessible.shouldBeTrue()
            summary.hitTimeBudget.shouldBeFalse()
            configDao.baselineCompletedAt.shouldBeNull()
        }
    }

    "empty source folder completes the baseline with zero files" {
        runTest {
            val configDao = FakeConfigDao()
            val summary = RunSummary()

            BaselineRecorder(FakeLocalFileScanner(), FakeIndexDao(), configDao)
                .recordBaseline(baselineConfig(), summary, control = null)

            summary.filesSkipped shouldBe 0
            summary.totalFilesDiscovered shouldBe 0
            configDao.baselineCompletedAt.shouldNotBeNull()
        }
    }
})
