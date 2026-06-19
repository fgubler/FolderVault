package ch.abwesend.foldervault.infrastructure.room

import android.app.Application
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
import ch.abwesend.foldervault.infrastructure.room.entity.UploadedFileIndexEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// application = Application::class avoids FolderVaultApp.onCreate() starting Koin for each test method
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomDatabaseTest {

    private lateinit var db: FolderVaultDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().context,
            FolderVaultDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `database opens without error`() {
        assertNotNull(db.backupConfigDao())
        assertNotNull(db.uploadedFileIndexDao())
        assertNotNull(db.backupMessageDao())
        assertNotNull(db.notificationThrottleStateDao())
    }

    @Test
    fun `upsert and retrieve a BackupConfig`() = runTest {
        val dao = db.backupConfigDao()
        dao.upsert(backupConfigEntity("config-1", "My Backup"))
        val retrieved = dao.getByIdOnce("config-1")
        assertNotNull(retrieved)
        assertEquals("My Backup", retrieved.displayName)
    }

    @Test
    fun `deleting BackupConfig cascades to UploadedFileIndex`() = runTest {
        val configDao = db.backupConfigDao()
        val indexDao = db.uploadedFileIndexDao()

        val configId = "config-cascade"
        configDao.upsert(backupConfigEntity(configId, "Cascade Test"))

        indexDao.upsertCurrentVersion(
            UploadedFileIndexEntity(
                backupConfigId = configId,
                relativePath = "documents/file.txt",
                localLastModified = System.currentTimeMillis(),
                localSize = 1024L,
                cloudFileId = "cloud-id-1",
                remoteName = "file.txt",
                uploadedAt = System.currentTimeMillis(),
                isCurrentVersion = true,
            )
        )
        assertNotNull(indexDao.getCurrentVersion(configId, "documents/file.txt"))

        configDao.deleteById(configId)
        assertNull(indexDao.getCurrentVersion(configId, "documents/file.txt"))
    }

    @Test
    fun `deleting BackupConfig cascades to BackupMessage`() = runTest {
        val configDao = db.backupConfigDao()
        val messageDao = db.backupMessageDao()

        val configId = "config-msg-cascade"
        configDao.upsert(backupConfigEntity(configId, "Msg Cascade Test"))

        messageDao.insert(
            BackupMessageEntity(
                backupConfigId = configId,
                runId = null,
                timestamp = System.currentTimeMillis(),
                severity = MessageSeverity.INFO,
                type = MessageType.GENERIC_INFO,
                messageText = null,
                formatArgs = emptyList(),
                relativePath = null,
                readAt = null,
            )
        )

        configDao.deleteById(configId)

        val remaining = messageDao.getUndismissed(configId).first()
        assertEquals(0, remaining.size)
    }

    @Test
    fun `upsertCurrentVersion clears previous current flag`() = runTest {
        val configDao = db.backupConfigDao()
        val indexDao = db.uploadedFileIndexDao()

        val configId = "config-version"
        configDao.upsert(backupConfigEntity(configId, "Version Test"))

        val path = "docs/report.pdf"
        val t1 = System.currentTimeMillis() - 10_000
        val t2 = System.currentTimeMillis()

        indexDao.upsertCurrentVersion(
            UploadedFileIndexEntity(
                backupConfigId = configId,
                relativePath = path,
                localLastModified = t1,
                localSize = 500L,
                cloudFileId = "id-v1",
                remoteName = "report.pdf",
                uploadedAt = t1,
                isCurrentVersion = true,
            )
        )
        indexDao.upsertCurrentVersion(
            UploadedFileIndexEntity(
                backupConfigId = configId,
                relativePath = path,
                localLastModified = t2,
                localSize = 600L,
                cloudFileId = "id-v2",
                remoteName = "report.pdf",
                uploadedAt = t2,
                isCurrentVersion = true,
            )
        )

        val current = indexDao.getCurrentVersion(configId, path)
        assertNotNull(current)
        assertEquals("id-v2", current.cloudFileId)

        val history = indexDao.getVersionHistory(configId, path)
        assertEquals(2, history.size)
    }

    private fun backupConfigEntity(id: String, name: String) = BackupConfigEntity(
        id = id,
        displayName = name,
        sourceTreeUri = "content://com.example/tree/primary%3ADocuments",
        cloudProvider = "GOOGLE_DRIVE",
        cloudSubFolderId = "folder-${UUID.randomUUID()}",
        cloudSubFolderName = "test_${UUID.randomUUID()}",
        cloudAccountIdentifier = "user@example.com",
        schedule = BackupSchedule.DAILY,
        changedFilePolicy = ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP,
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
