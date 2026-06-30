package ch.abwesend.foldervault.infrastructure.room

import android.app.Application
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupRunEntity
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupRunDaoTest {

    private lateinit var db: FolderVaultDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().context,
            FolderVaultDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `insert then markComplete records the final state`() = runTest {
        val configId = seedConfig()
        val dao = db.backupRunDao()

        val started = 1_000L
        val completed = 2_500L
        dao.insert(runningEntity(configId = configId, runId = "r1", startedAt = started))
        dao.markComplete(
            runId = "r1",
            completedAt = completed,
            status = BackupRunStatus.UP_TO_DATE,
            filesUploaded = 5,
            filesSkipped = 2,
            filesFailed = 0,
            bytesUploaded = 1024L,
        )

        val saved = dao.findByRunId("r1")
        assertNotNull(saved)
        assertEquals(started, saved.startedAt)
        assertEquals(completed, saved.completedAt)
        assertEquals(BackupRunStatus.UP_TO_DATE, saved.status)
        assertEquals(5, saved.filesUploaded)
        assertEquals(2, saved.filesSkipped)
        assertEquals(1024L, saved.bytesUploaded)
    }

    @Test
    fun `observeByConfig emits entries newest first`() = runTest {
        val configId = seedConfig()
        val dao = db.backupRunDao()

        dao.insert(runningEntity(configId, "old", startedAt = 1_000L))
        dao.insert(runningEntity(configId, "newer", startedAt = 2_000L))
        dao.insert(runningEntity(configId, "newest", startedAt = 3_000L))

        val ordered = dao.observeByConfig(configId).first().map { it.runId }
        assertEquals(listOf("newest", "newer", "old"), ordered)
    }

    @Test
    fun `observeByConfig isolates per config`() = runTest {
        val configA = seedConfig("config-a")
        val configB = seedConfig("config-b")
        val dao = db.backupRunDao()

        dao.insert(runningEntity(configA, "a1", startedAt = 1L))
        dao.insert(runningEntity(configB, "b1", startedAt = 2L))

        assertEquals(listOf("a1"), dao.observeByConfig(configA).first().map { it.runId })
        assertEquals(listOf("b1"), dao.observeByConfig(configB).first().map { it.runId })
    }

    @Test
    fun `pruneOld keeps only the N most recent`() = runTest {
        val configId = seedConfig()
        val dao = db.backupRunDao()

        // 105 rows with increasing startedAt
        repeat(105) { i ->
            dao.insert(runningEntity(configId, "r$i", startedAt = i.toLong()))
        }

        dao.pruneOld(configId, keep = 100)

        val remaining = dao.observeByConfig(configId, limit = 200).first()
        assertEquals(100, remaining.size)
        // newest 100 should be r5..r104
        assertEquals(104L, remaining.first().startedAt)
        assertEquals(5L, remaining.last().startedAt)
        // earliest rows must be gone
        assertNull(dao.findByRunId("r0"))
        assertNull(dao.findByRunId("r4"))
        assertNotNull(dao.findByRunId("r5"))
    }

    @Test
    fun `pruneOld is a no-op when under the limit`() = runTest {
        val configId = seedConfig()
        val dao = db.backupRunDao()
        repeat(3) { i -> dao.insert(runningEntity(configId, "r$i", startedAt = i.toLong())) }

        dao.pruneOld(configId, keep = 100)

        assertEquals(3, dao.observeByConfig(configId).first().size)
    }

    @Test
    fun `deleting BackupConfig cascades to BackupRun`() = runTest {
        val configId = seedConfig()
        val dao = db.backupRunDao()
        dao.insert(runningEntity(configId, "r1", startedAt = 1L))

        db.backupConfigDao().deleteById(configId)

        assertTrue(dao.observeByConfig(configId).first().isEmpty())
        assertNull(dao.findByRunId("r1"))
    }

    private suspend fun seedConfig(id: String = "config-${UUID.randomUUID()}"): String {
        db.backupConfigDao().upsert(backupConfigEntity(id))
        return id
    }

    private fun backupConfigEntity(id: String) = BackupConfigEntity(
        id = id,
        displayName = "Test $id",
        sourceTreeUri = "content://com.example/tree/x",
        cloudProvider = "GOOGLE_DRIVE",
        cloudSubFolderId = "folder-$id",
        cloudSubFolderName = "test_$id",
        cloudAccountIdentifier = "user@example.com",
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

    private fun runningEntity(configId: String, runId: String, startedAt: Long) = BackupRunEntity(
        backupConfigId = configId,
        runId = runId,
        startedAt = startedAt,
        completedAt = null,
        status = BackupRunStatus.RUNNING,
        filesUploaded = 0,
        filesSkipped = 0,
        filesFailed = 0,
        bytesUploaded = 0L,
    )
}
