package ch.abwesend.folderVault.infrastructure.backup

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
import ch.abwesend.foldervault.infrastructure.room.FolderVaultDatabase
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MessageCoalescingTest {

    private lateinit var db: FolderVaultDatabase
    private lateinit var dao: BackupMessageDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().context,
            FolderVaultDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.backupMessageDao()
        db.backupConfigDao().let { configDao ->
            kotlinx.coroutines.runBlocking {
                configDao.upsert(backupConfigEntity("config-1"))
            }
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `first message for a run is inserted with count 1`() = runTest {
        dao.coalesceInsert(message("run-1", "config-1", MessageType.UPLOAD_FAILED))

        val rows = dao.getUndismissed("config-1").first()
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].count)
    }

    @Test
    fun `second message for same run and type increments count not rows`() = runTest {
        dao.coalesceInsert(message("run-1", "config-1", MessageType.UPLOAD_FAILED))
        dao.coalesceInsert(message("run-1", "config-1", MessageType.UPLOAD_FAILED))
        dao.coalesceInsert(message("run-1", "config-1", MessageType.UPLOAD_FAILED))

        val rows = dao.getUndismissed("config-1").first()
        assertEquals(1, rows.size)
        assertEquals(3, rows[0].count)
    }

    @Test
    fun `messages for different types within the same run are not coalesced`() = runTest {
        dao.coalesceInsert(message("run-1", "config-1", MessageType.UPLOAD_FAILED))
        dao.coalesceInsert(message("run-1", "config-1", MessageType.AUTH_LOST))

        val rows = dao.getUndismissed("config-1").first()
        assertEquals(2, rows.size)
    }

    @Test
    fun `messages for different runs are not coalesced`() = runTest {
        dao.coalesceInsert(message("run-1", "config-1", MessageType.UPLOAD_FAILED))
        dao.coalesceInsert(message("run-2", "config-1", MessageType.UPLOAD_FAILED))

        val rows = dao.getUndismissed("config-1").first()
        assertEquals(2, rows.size)
        rows.forEach { assertEquals(1, it.count) }
    }

    @Test
    fun `null runId falls back to plain insert without coalescing`() = runTest {
        dao.coalesceInsert(message(runId = null, configId = "config-1", MessageType.UPLOAD_FAILED))
        dao.coalesceInsert(message(runId = null, configId = "config-1", MessageType.UPLOAD_FAILED))

        val rows = dao.getUndismissed("config-1").first()
        assertEquals(2, rows.size)
        rows.forEach { assertEquals(1, it.count) }
    }

    private fun message(runId: String?, configId: String, type: MessageType) = BackupMessageEntity(
        backupConfigId = configId,
        runId = runId,
        timestamp = System.currentTimeMillis(),
        severity = MessageSeverity.WARNING,
        type = type,
        messageText = null,
        formatArgs = emptyList(),
        relativePath = null,
        readAt = null,
    )

    private fun backupConfigEntity(id: String) = BackupConfigEntity(
        id = id,
        displayName = "Test Backup",
        sourceTreeUri = "content://com.example/tree/primary%3ADocuments",
        cloudProvider = "GOOGLE_DRIVE",
        cloudRootFolderId = "folder-${UUID.randomUUID()}",
        cloudRootFolderName = "FolderVault_${UUID.randomUUID()}",
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
