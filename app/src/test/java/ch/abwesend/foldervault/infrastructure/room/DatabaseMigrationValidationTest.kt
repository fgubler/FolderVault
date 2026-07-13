package ch.abwesend.foldervault.infrastructure.room

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Opens a real on-disk database in the exact state a v3 device install has — including the
 * hand-created partial unique index `idx_uploaded_file_index_current_version` — and lets Room
 * run the full production open path (migrations + schema validation + callbacks).
 *
 * Regression test for the v3→v4 startup failure: Room validates the *complete* index set of
 * every table after a migration, so the partial index (which the entity cannot declare — Room
 * has no partial-index support) made validation fail with "Migration didn't properly handle:
 * UploadedFileIndex". The raw-SQL tests in [DatabaseMigrationTest] bypass Room's validation and
 * could not catch this. The fix: migrations drop the partial index and [DatabaseCallback]
 * recreates it in `onOpen`, which runs after validation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DatabaseMigrationValidationTest {

    private lateinit var context: Context
    private lateinit var db: FolderVaultDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().context
        context.getDatabasePath(FolderVaultDatabase.DB_NAME).parentFile?.mkdirs()
        createV3DeviceDatabase(context.getDatabasePath(FolderVaultDatabase.DB_NAME).absolutePath)
        db = FolderVaultDatabase.create(context)
    }

    @After
    fun tearDown() {
        db.close()
        context.getDatabasePath(FolderVaultDatabase.DB_NAME).delete()
    }

    @Test
    fun `opening a real v3 database migrates to v4 and passes Room's schema validation`() = runTest {
        // Any DAO call forces the open path: migrations, validation, callbacks. Before the fix
        // this threw IllegalStateException("Migration didn't properly handle: UploadedFileIndex").
        val config = db.backupConfigDao().getByIdOnce("cfg-device")
        assertNotNull(config)
        assertEquals(false, config.syncLaterChangesOnly)
        assertEquals(null, config.baselineCompletedAt)

        val indexed = db.uploadedFileIndexDao().getCurrentVersion("cfg-device", "docs/a.txt")
        assertNotNull(indexed)
        assertEquals(false, indexed.isBaseline)
    }

    @Test
    fun `the partial unique index is recreated after the migration`() = runTest {
        db.backupConfigDao().getByIdOnce("cfg-device") // force open

        val found = db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(PARTIAL_INDEX))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
        assertEquals(PARTIAL_INDEX, found)
    }

    /**
     * Replays what a real device at v3 has on disk: Room's own v3 `createSql` (from
     * `app/schemas/.../3.json`, verbatim), the partial index from the database callback,
     * Room's identity-hash bookkeeping table, `user_version = 3`, and a little data.
     */
    @Suppress("LongMethod")
    private fun createV3DeviceDatabase(path: String) {
        val raw = SQLiteDatabase.openOrCreateDatabase(path, null)
        raw.use { d ->
            d.execSQL(
                """CREATE TABLE IF NOT EXISTS `BackupConfig` (`id` TEXT NOT NULL,
                   `displayName` TEXT NOT NULL, `sourceTreeUri` TEXT NOT NULL,
                   `cloudProvider` TEXT NOT NULL, `cloudSubFolderId` TEXT NOT NULL,
                   `cloudSubFolderName` TEXT NOT NULL, `cloudAccountIdentifier` TEXT NOT NULL,
                   `schedule` TEXT NOT NULL, `changedFilePolicy` TEXT NOT NULL,
                   `encryptionEnabled` INTEGER NOT NULL, `encryptedPasswordBlob` TEXT,
                   `retentionPolicy` TEXT NOT NULL, `networkPolicy` TEXT NOT NULL,
                   `requiresCharging` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                   `lastRunAt` INTEGER, `lastRunStatus` TEXT NOT NULL,
                   `filesUploaded` INTEGER NOT NULL, `filesSkipped` INTEGER NOT NULL,
                   `filesFailed` INTEGER NOT NULL, `bytesUploaded` INTEGER NOT NULL,
                   `totalFilesDiscovered` INTEGER NOT NULL, `filesUploadedTotal` INTEGER NOT NULL,
                   `lastRunCompletedNormally` INTEGER NOT NULL, `isPaused` INTEGER NOT NULL,
                   `enc_kdfAlgorithm` TEXT, `enc_kdfIterations` INTEGER, `enc_salt` TEXT,
                   `enc_cipherTransformation` TEXT, `enc_gcmTagBits` INTEGER, PRIMARY KEY(`id`))"""
            )
            d.execSQL(
                """CREATE TABLE IF NOT EXISTS `UploadedFileIndex` (
                   `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `backupConfigId` TEXT NOT NULL,
                   `relativePath` TEXT NOT NULL, `localLastModified` INTEGER NOT NULL,
                   `localSize` INTEGER NOT NULL, `cloudFileId` TEXT NOT NULL,
                   `remoteName` TEXT NOT NULL, `uploadedAt` INTEGER NOT NULL,
                   `isCurrentVersion` INTEGER NOT NULL, `pendingDeletionCloudFileId` TEXT,
                   FOREIGN KEY(`backupConfigId`) REFERENCES `BackupConfig`(`id`)
                   ON UPDATE NO ACTION ON DELETE CASCADE )"""
            )
            d.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_UploadedFileIndex_backupConfigId` " +
                    "ON `UploadedFileIndex` (`backupConfigId`)"
            )
            d.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_UploadedFileIndex_backupConfigId_relativePath_uploadedAt` " +
                    "ON `UploadedFileIndex` (`backupConfigId`, `relativePath`, `uploadedAt`)"
            )
            d.execSQL(
                """CREATE TABLE IF NOT EXISTS `BackupMessage` (
                   `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `backupConfigId` TEXT,
                   `runId` TEXT, `timestamp` INTEGER NOT NULL, `severity` TEXT NOT NULL,
                   `type` TEXT NOT NULL, `messageText` TEXT, `formatArgs` TEXT NOT NULL,
                   `relativePath` TEXT, `count` INTEGER NOT NULL, `readAt` INTEGER,
                   `dismissed` INTEGER NOT NULL,
                   FOREIGN KEY(`backupConfigId`) REFERENCES `BackupConfig`(`id`)
                   ON UPDATE NO ACTION ON DELETE CASCADE )"""
            )
            d.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_BackupMessage_backupConfigId` " +
                    "ON `BackupMessage` (`backupConfigId`)"
            )
            d.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_BackupMessage_backupConfigId_timestamp` " +
                    "ON `BackupMessage` (`backupConfigId`, `timestamp`)"
            )
            d.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_BackupMessage_backupConfigId_dismissed` " +
                    "ON `BackupMessage` (`backupConfigId`, `dismissed`)"
            )
            d.execSQL(
                """CREATE TABLE IF NOT EXISTS `BackupRun` (
                   `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `backupConfigId` TEXT NOT NULL,
                   `runId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `completedAt` INTEGER,
                   `status` TEXT NOT NULL, `filesUploaded` INTEGER NOT NULL,
                   `filesSkipped` INTEGER NOT NULL, `filesFailed` INTEGER NOT NULL,
                   `bytesUploaded` INTEGER NOT NULL,
                   FOREIGN KEY(`backupConfigId`) REFERENCES `BackupConfig`(`id`)
                   ON UPDATE NO ACTION ON DELETE CASCADE )"""
            )
            d.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_BackupRun_backupConfigId` ON `BackupRun` (`backupConfigId`)"
            )
            d.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_BackupRun_backupConfigId_startedAt` " +
                    "ON `BackupRun` (`backupConfigId`, `startedAt`)"
            )
            d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_BackupRun_runId` ON `BackupRun` (`runId`)")
            d.execSQL(
                """CREATE TABLE IF NOT EXISTS `NotificationThrottleState` (
                   `backupConfigId` TEXT NOT NULL, `messageType` TEXT NOT NULL,
                   `lastNotifiedAt` INTEGER NOT NULL, `lastRunId` TEXT,
                   PRIMARY KEY(`backupConfigId`, `messageType`),
                   FOREIGN KEY(`backupConfigId`) REFERENCES `BackupConfig`(`id`)
                   ON UPDATE NO ACTION ON DELETE CASCADE )"""
            )
            d.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_NotificationThrottleState_backupConfigId` " +
                    "ON `NotificationThrottleState` (`backupConfigId`)"
            )

            // The partial unique index a live install carries (created by DatabaseCallback).
            d.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS $PARTIAL_INDEX
                   ON UploadedFileIndex (backupConfigId, relativePath)
                   WHERE isCurrentVersion = 1"""
            )

            // Room's identity bookkeeping, exactly as Room writes it at v3.
            d.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            d.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$V3_IDENTITY_HASH')"
            )

            d.execSQL(
                """INSERT INTO BackupConfig (
                       id, displayName, sourceTreeUri, cloudProvider, cloudSubFolderId,
                       cloudSubFolderName, cloudAccountIdentifier, schedule, changedFilePolicy,
                       encryptionEnabled, encryptedPasswordBlob, retentionPolicy, networkPolicy,
                       requiresCharging, createdAt, lastRunAt, lastRunStatus,
                       filesUploaded, filesSkipped, filesFailed, bytesUploaded,
                       totalFilesDiscovered, filesUploadedTotal, lastRunCompletedNormally, isPaused
                   ) VALUES (
                       'cfg-device', 'Device config', 'content://x', 'google_drive', 'sub',
                       'sub_name', 'a@b.c', 'DAILY', 'DUPLICATE_WITH_TIMESTAMP',
                       0, NULL, 'KeepAll', 'WIFI_ONLY',
                       0, 0, NULL, 'IDLE',
                       0, 0, 0, 0,
                       0, 0, 0, 0
                   )"""
            )
            d.execSQL(
                """INSERT INTO UploadedFileIndex (
                       backupConfigId, relativePath, localLastModified, localSize,
                       cloudFileId, remoteName, uploadedAt, isCurrentVersion, pendingDeletionCloudFileId
                   ) VALUES ('cfg-device', 'docs/a.txt', 1, 2, 'cloud-1', 'a.txt', 3, 1, NULL)"""
            )

            d.version = V3_VERSION
        }
    }

    private companion object {
        const val V3_VERSION = 3

        /** identityHash of schemas/.../3.json — what Room wrote into room_master_table at v3. */
        const val V3_IDENTITY_HASH = "ec68b1e2f80b1a7c592d9fe1eba9d307"

        const val PARTIAL_INDEX = "idx_uploaded_file_index_current_version"
    }
}
