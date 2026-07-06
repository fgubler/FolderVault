package ch.abwesend.foldervault.infrastructure.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Registry of Room schema migrations. Add entries here as the schema evolves post-v1.
 */
object DatabaseMigrations {

    /**
     * v1 → v2: introduces the BackupRun table tracking per-run history
     * (start/end time, status, file counts) shown on the backup detail screen.
     */
    internal val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS BackupRun (
                       id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                       backupConfigId TEXT NOT NULL,
                       runId TEXT NOT NULL,
                       startedAt INTEGER NOT NULL,
                       completedAt INTEGER,
                       status TEXT NOT NULL,
                       filesUploaded INTEGER NOT NULL,
                       filesSkipped INTEGER NOT NULL,
                       filesFailed INTEGER NOT NULL,
                       bytesUploaded INTEGER NOT NULL,
                       FOREIGN KEY (backupConfigId) REFERENCES BackupConfig(id) ON DELETE CASCADE
                   )"""
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_BackupRun_backupConfigId ON BackupRun (backupConfigId)")
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS index_BackupRun_backupConfigId_startedAt
                   ON BackupRun (backupConfigId, startedAt)"""
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_BackupRun_runId ON BackupRun (runId)")
        }
    }

    /**
     * v2 → v3: adds the per-config `requiresCharging` flag on BackupConfig. Existing rows
     * default to 0 (disabled) so the migration is a no-op behaviour-wise for users who don't
     * enable the new option.
     */
    internal val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE BackupConfig ADD COLUMN requiresCharging INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
