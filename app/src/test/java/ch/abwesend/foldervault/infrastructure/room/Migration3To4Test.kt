package ch.abwesend.folderVault.infrastructure.room

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import ch.abwesend.foldervault.infrastructure.room.DatabaseMigrations
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val V3_DB_VERSION = 3
private const val V4_DB_VERSION = 4

/**
 * Drives the Migration3To4 SQL directly against a fresh SupportSQLiteDatabase seeded with the
 * v3 schema. Avoids [androidx.room.testing.MigrationTestHelper] because the Robolectric unit-test
 * variant does not merge `sourceSets.test.assets` into the test classpath, so the helper cannot
 * load the per-version schema JSON from assets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Migration3To4Test {

    private lateinit var openHelper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(SeedV3Callback)
            .build()
        openHelper = FrameworkSQLiteOpenHelperFactory().create(config)
    }

    @After
    fun tearDown() {
        openHelper.close()
    }

    @Test
    fun migration_3_to_4_drops_root_columns_adds_sub_columns_and_wipes_data() {
        val db = openHelper.writableDatabase
        db.execSQL(SEED_BACKUP_CONFIG_SQL)
        db.execSQL(SEED_UPLOADED_FILE_INDEX_SQL)
        db.execSQL(SEED_BACKUP_MESSAGE_SQL)

        val migration = DatabaseMigrations.ALL.single {
            it.startVersion == V3_DB_VERSION && it.endVersion == V4_DB_VERSION
        }
        migration.migrate(db)

        fun count(table: String): Int = db.query("SELECT COUNT(*) FROM $table").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
        assertEquals(0, count("BackupConfig"))
        assertEquals(0, count("UploadedFileIndex"))
        assertEquals(0, count("BackupMessage"))

        val columnNames = mutableSetOf<String>()
        db.query("PRAGMA table_info(BackupConfig)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) columnNames += c.getString(nameIdx)
        }
        assertTrue("cloudSubFolderId" in columnNames, "new id column should exist")
        assertTrue("cloudSubFolderName" in columnNames, "new name column should exist")
        assertFalse("cloudRootFolderId" in columnNames, "old id column should be gone")
        assertFalse("cloudRootFolderName" in columnNames, "old name column should be gone")
    }

    private object SeedV3Callback : SupportSQLiteOpenHelper.Callback(V3_DB_VERSION) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(V3_BACKUP_CONFIG_SQL)
            db.execSQL(V3_UPLOADED_FILE_INDEX_SQL)
            db.execSQL(V3_BACKUP_MESSAGE_SQL)
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        private const val V3_BACKUP_CONFIG_SQL =
            "CREATE TABLE IF NOT EXISTS `BackupConfig` (" +
                "`id` TEXT NOT NULL, " +
                "`displayName` TEXT NOT NULL, " +
                "`sourceTreeUri` TEXT NOT NULL, " +
                "`cloudProvider` TEXT NOT NULL, " +
                "`cloudRootFolderId` TEXT NOT NULL, " +
                "`cloudRootFolderName` TEXT NOT NULL, " +
                "`cloudAccountIdentifier` TEXT NOT NULL, " +
                "`schedule` TEXT NOT NULL, " +
                "`changedFilePolicy` TEXT NOT NULL, " +
                "`encryptionEnabled` INTEGER NOT NULL, " +
                "`encryptedPasswordBlob` TEXT, " +
                "`retentionPolicy` TEXT NOT NULL, " +
                "`networkPolicy` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`lastRunAt` INTEGER, " +
                "`lastRunStatus` TEXT NOT NULL, " +
                "`filesUploaded` INTEGER NOT NULL, " +
                "`filesSkipped` INTEGER NOT NULL, " +
                "`filesFailed` INTEGER NOT NULL, " +
                "`bytesUploaded` INTEGER NOT NULL, " +
                "`totalFilesDiscovered` INTEGER NOT NULL, " +
                "`filesUploadedTotal` INTEGER NOT NULL, " +
                "`lastRunCompletedNormally` INTEGER NOT NULL, " +
                "`isPaused` INTEGER NOT NULL, " +
                "`enc_kdfAlgorithm` TEXT, " +
                "`enc_kdfIterations` INTEGER, " +
                "`enc_salt` TEXT, " +
                "`enc_cipherTransformation` TEXT, " +
                "`enc_gcmTagBits` INTEGER, " +
                "PRIMARY KEY(`id`))"

        private const val V3_UPLOADED_FILE_INDEX_SQL =
            "CREATE TABLE IF NOT EXISTS `UploadedFileIndex` (" +
                "`rowId` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "`backupConfigId` TEXT NOT NULL, " +
                "`relativePath` TEXT NOT NULL, " +
                "`localLastModified` INTEGER NOT NULL, " +
                "`localSize` INTEGER NOT NULL, " +
                "`cloudFileId` TEXT NOT NULL, " +
                "`remoteName` TEXT NOT NULL, " +
                "`uploadedAt` INTEGER NOT NULL, " +
                "`isCurrentVersion` INTEGER NOT NULL, " +
                "`pendingDeletionCloudFileId` TEXT)"

        private const val V3_BACKUP_MESSAGE_SQL =
            "CREATE TABLE IF NOT EXISTS `BackupMessage` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "`backupConfigId` TEXT, " +
                "`runId` TEXT, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`severity` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`messageText` TEXT NOT NULL, " +
                "`formatArgs` TEXT NOT NULL, " +
                "`relativePath` TEXT, " +
                "`readAt` INTEGER, " +
                "`dismissed` INTEGER NOT NULL DEFAULT 0)"

        private const val SEED_BACKUP_CONFIG_SQL =
            "INSERT INTO BackupConfig (" +
                "id, displayName, sourceTreeUri, cloudProvider, " +
                "cloudRootFolderId, cloudRootFolderName, cloudAccountIdentifier, " +
                "schedule, changedFilePolicy, encryptionEnabled, encryptedPasswordBlob, " +
                "retentionPolicy, networkPolicy, createdAt, lastRunAt, lastRunStatus, " +
                "filesUploaded, filesSkipped, filesFailed, bytesUploaded, " +
                "totalFilesDiscovered, filesUploadedTotal, lastRunCompletedNormally, isPaused" +
                ") VALUES (" +
                "'cfg-1', 'My Backup', 'content://example/tree/x', 'GOOGLE_DRIVE', " +
                "'root-1', 'FolderVault_root', 'user@example.com', " +
                "'DAILY', 'DUPLICATE_WITH_TIMESTAMP', 0, NULL, " +
                "'KEEP_ALL', 'WIFI_ONLY', 0, NULL, 'IDLE', " +
                "0, 0, 0, 0, " +
                "0, 0, 0, 0)"

        private const val SEED_UPLOADED_FILE_INDEX_SQL =
            "INSERT INTO UploadedFileIndex (" +
                "backupConfigId, relativePath, localLastModified, localSize, " +
                "cloudFileId, remoteName, uploadedAt, isCurrentVersion, pendingDeletionCloudFileId" +
                ") VALUES ('cfg-1', 'a.txt', 0, 0, 'cf', 'a.txt', 0, 1, NULL)"

        private const val SEED_BACKUP_MESSAGE_SQL =
            "INSERT INTO BackupMessage (" +
                "backupConfigId, runId, timestamp, severity, type, messageText, " +
                "formatArgs, relativePath, readAt, dismissed" +
                ") VALUES ('cfg-1', NULL, 0, 'INFO', 'INITIAL_SYNC_COMPLETE', 'x', '[]', NULL, NULL, 0)"
    }
}
