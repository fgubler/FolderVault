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

    /**
     * Every migration MUST call this first: Room's post-migration validation compares the
     * complete index set of each table against the entities, and the hand-created partial
     * unique index on UploadedFileIndex (not declarable via `@Index` — Room has no
     * partial-index support) fails that comparison with "Migration didn't properly handle:
     * UploadedFileIndex". Dropping it here keeps validation clean; the database callback
     * recreates it in `onOpen`, which runs after migrations and validation on every open.
     */
    private fun SupportSQLiteDatabase.dropPartialIndexesForValidation() {
        execSQL("DROP INDEX IF EXISTS idx_uploaded_file_index_current_version")
    }

    /**
     * v3 → v4: adds the "only sync changes from now on" option. `syncLaterChangesOnly` marks a
     * config whose pre-existing files must never be uploaded; `baselineCompletedAt` records when
     * the baseline snapshot of those files finished (NULL = baseline still pending);
     * `isBaseline` marks UploadedFileIndex rows that represent baselined-but-never-uploaded
     * files. Existing rows default to disabled, so the migration changes no behaviour.
     */
    internal val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.dropPartialIndexesForValidation()
            db.execSQL("ALTER TABLE BackupConfig ADD COLUMN syncLaterChangesOnly INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE BackupConfig ADD COLUMN baselineCompletedAt INTEGER")
            db.execSQL("ALTER TABLE UploadedFileIndex ADD COLUMN isBaseline INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
