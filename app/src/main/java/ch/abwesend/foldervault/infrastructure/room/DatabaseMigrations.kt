package ch.abwesend.foldervault.infrastructure.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val ALL: Array<Migration> = arrayOf(Migration1To2, Migration2To3, Migration3To4)
}

private object Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE BackupConfig ADD COLUMN isPaused INTEGER NOT NULL DEFAULT 0")
    }
}

@Suppress("MagicNumber")
private object Migration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE UploadedFileIndex ADD COLUMN pendingDeletionCloudFileId TEXT")
    }
}

/**
 * One-time wipe of [BackupConfig] (and its FK-cascaded children) to drop the obsolete
 * `cloudRootFolderId` / `cloudRootFolderName` columns and add the new
 * `cloudSubFolderId` / `cloudSubFolderName` columns. CASCADE delete cannot be relied on during
 * migrations (`PRAGMA foreign_keys` is off by default), so dependent tables are emptied
 * explicitly. v1 fresh-install semantics — existing dev data is forfeit; old Drive roots are
 * orphaned and must be cleaned up manually.
 */
@Suppress("MagicNumber")
private object Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM UploadedFileIndex")
        db.execSQL("DELETE FROM BackupMessage")
        db.execSQL("DROP TABLE BackupConfig")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `BackupConfig` (" +
                "`id` TEXT NOT NULL, " +
                "`displayName` TEXT NOT NULL, " +
                "`sourceTreeUri` TEXT NOT NULL, " +
                "`cloudProvider` TEXT NOT NULL, " +
                "`cloudSubFolderId` TEXT NOT NULL, " +
                "`cloudSubFolderName` TEXT NOT NULL, " +
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
        )
    }
}
