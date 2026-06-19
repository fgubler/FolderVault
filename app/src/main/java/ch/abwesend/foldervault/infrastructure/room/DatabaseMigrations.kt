package ch.abwesend.foldervault.infrastructure.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val ALL: Array<Migration> = arrayOf(Migration1To2, Migration2To3)
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
