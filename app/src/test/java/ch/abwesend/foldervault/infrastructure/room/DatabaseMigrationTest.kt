package ch.abwesend.foldervault.infrastructure.room

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies [DatabaseMigrations.MIGRATION_1_2] applies cleanly against a v1-shape database.
 *
 * Implemented without [androidx.room.testing.MigrationTestHelper] because under AGP 9 the
 * schema JSON exported to `$projectDir/schemas` is not merged into the unit-test asset path
 * that the helper reads from. Instead we open a raw SupportSQLiteDatabase, hand-create the
 * relevant v1 tables, run the migration, and assert the resulting shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DatabaseMigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(V1_VERSION) {
                override fun onCreate(db: SupportSQLiteDatabase) = createV1Schema(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    error("Unexpected upgrade in test setup")
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
    }

    @After
    fun tearDown() = helper.close()

    @Test
    fun `migration 1 to 2 creates BackupRun table with expected columns and indices`() {
        val db = helper.writableDatabase
        seedV1BackupConfig(db, configId = "config-1")

        DatabaseMigrations.MIGRATION_1_2.migrate(db)

        // Inserting a representative row proves the columns are wired up correctly — if any
        // column were missing or mistyped the INSERT would throw.
        db.execSQL(
            """INSERT INTO BackupRun (
                   backupConfigId, runId, startedAt, completedAt, status,
                   filesUploaded, filesSkipped, filesFailed, bytesUploaded
               ) VALUES ('config-1', 'r1', 100, 200, 'UP_TO_DATE', 5, 0, 0, 1024)"""
        )
        db.query("SELECT bytesUploaded FROM BackupRun WHERE runId = 'r1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1024L, c.getLong(0))
        }

        // All three indices declared on BackupRunEntity must be created.
        assertNotNull(findIndex(db, "index_BackupRun_backupConfigId"))
        assertNotNull(findIndex(db, "index_BackupRun_backupConfigId_startedAt"))
        assertNotNull(findIndex(db, "index_BackupRun_runId"))
    }

    @Test
    fun `migration 1 to 2 preserves existing BackupConfig rows`() {
        val db = helper.writableDatabase
        seedV1BackupConfig(db, configId = "kept-id", displayName = "Survives")

        DatabaseMigrations.MIGRATION_1_2.migrate(db)

        db.query("SELECT displayName FROM BackupConfig WHERE id = 'kept-id'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Survives", c.getString(0))
        }
    }

    @Test
    fun `migration 1 to 2 enables FK cascade from BackupConfig to BackupRun`() {
        val db = helper.writableDatabase
        seedV1BackupConfig(db, configId = "config-cascade")
        DatabaseMigrations.MIGRATION_1_2.migrate(db)

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            """INSERT INTO BackupRun (
                   backupConfigId, runId, startedAt, completedAt, status,
                   filesUploaded, filesSkipped, filesFailed, bytesUploaded
               ) VALUES ('config-cascade', 'r-cascade', 1, NULL, 'RUNNING', 0, 0, 0, 0)"""
        )
        db.execSQL("DELETE FROM BackupConfig WHERE id = 'config-cascade'")

        db.query("SELECT COUNT(*) FROM BackupRun WHERE runId = 'r-cascade'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun `migration 2 to 3 adds requiresCharging column defaulting to 0`() {
        val db = helper.writableDatabase
        seedV1BackupConfig(db, configId = "config-charging")
        DatabaseMigrations.MIGRATION_1_2.migrate(db)

        DatabaseMigrations.MIGRATION_2_3.migrate(db)

        db.query("SELECT requiresCharging FROM BackupConfig WHERE id = 'config-charging'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun `migration 2 to 3 preserves existing BackupConfig rows`() {
        val db = helper.writableDatabase
        seedV1BackupConfig(db, configId = "kept-across-v3", displayName = "Survives v3")
        DatabaseMigrations.MIGRATION_1_2.migrate(db)

        DatabaseMigrations.MIGRATION_2_3.migrate(db)

        db.query("SELECT displayName FROM BackupConfig WHERE id = 'kept-across-v3'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Survives v3", c.getString(0))
        }
    }

    @Test
    fun `migration 3 to 4 adds baseline columns with expected defaults`() {
        val db = helper.writableDatabase
        seedV1BackupConfig(db, configId = "config-baseline")
        seedV1UploadedFileIndex(db, configId = "config-baseline", relativePath = "docs/a.txt")
        DatabaseMigrations.MIGRATION_1_2.migrate(db)
        DatabaseMigrations.MIGRATION_2_3.migrate(db)

        DatabaseMigrations.MIGRATION_3_4.migrate(db)

        db.query(
            "SELECT syncLaterChangesOnly, baselineCompletedAt FROM BackupConfig WHERE id = 'config-baseline'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertTrue(c.isNull(1))
        }
        db.query(
            "SELECT isBaseline FROM UploadedFileIndex WHERE relativePath = 'docs/a.txt'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun `migration 3 to 4 allows writing the new columns`() {
        val db = helper.writableDatabase
        seedV1BackupConfig(db, configId = "config-write")
        DatabaseMigrations.MIGRATION_1_2.migrate(db)
        DatabaseMigrations.MIGRATION_2_3.migrate(db)

        DatabaseMigrations.MIGRATION_3_4.migrate(db)

        db.execSQL(
            "UPDATE BackupConfig SET syncLaterChangesOnly = 1, baselineCompletedAt = 1234 WHERE id = 'config-write'"
        )
        db.execSQL(
            """INSERT INTO UploadedFileIndex (
                   backupConfigId, relativePath, localLastModified, localSize,
                   cloudFileId, remoteName, uploadedAt, isCurrentVersion,
                   pendingDeletionCloudFileId, isBaseline
               ) VALUES ('config-write', 'new.bin', 10, 20, '', '', 30, 1, NULL, 1)"""
        )
        db.query(
            "SELECT syncLaterChangesOnly, baselineCompletedAt FROM BackupConfig WHERE id = 'config-write'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(1234L, c.getLong(1))
        }
        db.query("SELECT isBaseline FROM UploadedFileIndex WHERE relativePath = 'new.bin'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    /**
     * Subset of the v1 schema: only the tables the tested migrations touch or reference. Other
     * v1 tables (BackupMessage, NotificationThrottleState) are irrelevant here and omitted to
     * keep the test focused.
     */
    private fun createV1Schema(db: SupportSQLiteDatabase) {
        createV1UploadedFileIndex(db)
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS BackupConfig (
                   `id` TEXT NOT NULL, `displayName` TEXT NOT NULL, `sourceTreeUri` TEXT NOT NULL,
                   `cloudProvider` TEXT NOT NULL, `cloudSubFolderId` TEXT NOT NULL,
                   `cloudSubFolderName` TEXT NOT NULL, `cloudAccountIdentifier` TEXT NOT NULL,
                   `schedule` TEXT NOT NULL, `changedFilePolicy` TEXT NOT NULL,
                   `encryptionEnabled` INTEGER NOT NULL, `encryptedPasswordBlob` TEXT,
                   `retentionPolicy` TEXT NOT NULL, `networkPolicy` TEXT NOT NULL,
                   `createdAt` INTEGER NOT NULL, `lastRunAt` INTEGER, `lastRunStatus` TEXT NOT NULL,
                   `filesUploaded` INTEGER NOT NULL, `filesSkipped` INTEGER NOT NULL,
                   `filesFailed` INTEGER NOT NULL, `bytesUploaded` INTEGER NOT NULL,
                   `totalFilesDiscovered` INTEGER NOT NULL, `filesUploadedTotal` INTEGER NOT NULL,
                   `lastRunCompletedNormally` INTEGER NOT NULL, `isPaused` INTEGER NOT NULL,
                   `enc_kdfAlgorithm` TEXT, `enc_kdfIterations` INTEGER, `enc_salt` TEXT,
                   `enc_cipherTransformation` TEXT, `enc_gcmTagBits` INTEGER,
                   PRIMARY KEY(`id`)
               )"""
        )
    }

    private fun createV1UploadedFileIndex(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS UploadedFileIndex (
                   `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                   `backupConfigId` TEXT NOT NULL, `relativePath` TEXT NOT NULL,
                   `localLastModified` INTEGER NOT NULL, `localSize` INTEGER NOT NULL,
                   `cloudFileId` TEXT NOT NULL, `remoteName` TEXT NOT NULL,
                   `uploadedAt` INTEGER NOT NULL, `isCurrentVersion` INTEGER NOT NULL,
                   `pendingDeletionCloudFileId` TEXT,
                   FOREIGN KEY (`backupConfigId`) REFERENCES BackupConfig(`id`) ON DELETE CASCADE
               )"""
        )
    }

    private fun seedV1UploadedFileIndex(
        db: SupportSQLiteDatabase,
        configId: String,
        relativePath: String,
    ) {
        db.execSQL(
            """INSERT INTO UploadedFileIndex (
                   backupConfigId, relativePath, localLastModified, localSize,
                   cloudFileId, remoteName, uploadedAt, isCurrentVersion, pendingDeletionCloudFileId
               ) VALUES ('$configId', '$relativePath', 1, 2, 'cloud-1', 'a.txt', 3, 1, NULL)"""
        )
    }

    private fun seedV1BackupConfig(
        db: SupportSQLiteDatabase,
        configId: String,
        displayName: String = "Test",
    ) {
        db.execSQL(
            """INSERT INTO BackupConfig (
                   id, displayName, sourceTreeUri, cloudProvider,
                   cloudSubFolderId, cloudSubFolderName, cloudAccountIdentifier,
                   schedule, changedFilePolicy, encryptionEnabled, encryptedPasswordBlob,
                   retentionPolicy, networkPolicy, createdAt, lastRunAt, lastRunStatus,
                   filesUploaded, filesSkipped, filesFailed, bytesUploaded,
                   totalFilesDiscovered, filesUploadedTotal, lastRunCompletedNormally, isPaused
               ) VALUES (
                   '$configId', '$displayName', 'content://x', 'GOOGLE_DRIVE',
                   'sub', 'sub_name', 'a@b.c',
                   'DAILY', 'DUPLICATE_WITH_TIMESTAMP', 0, NULL,
                   'KeepAll', 'WIFI_ONLY', 0, NULL, 'IDLE',
                   0, 0, 0, 0,
                   0, 0, 0, 0
               )"""
        )
    }

    private fun findIndex(db: SupportSQLiteDatabase, indexName: String): String? =
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = '$indexName'").use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private companion object {
        const val V1_VERSION = 1
    }
}
