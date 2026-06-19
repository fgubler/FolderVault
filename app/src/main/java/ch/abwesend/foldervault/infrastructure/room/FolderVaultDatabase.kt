package ch.abwesend.foldervault.infrastructure.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import ch.abwesend.foldervault.infrastructure.room.converter.RoomTypeConverters
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.NotificationThrottleStateDao
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
import ch.abwesend.foldervault.infrastructure.room.entity.NotificationThrottleStateEntity
import ch.abwesend.foldervault.infrastructure.room.entity.UploadedFileIndexEntity

@Database(
    entities = [
        BackupConfigEntity::class,
        UploadedFileIndexEntity::class,
        BackupMessageEntity::class,
        NotificationThrottleStateEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(RoomTypeConverters::class)
abstract class FolderVaultDatabase : RoomDatabase() {

    abstract fun backupConfigDao(): BackupConfigDao
    abstract fun uploadedFileIndexDao(): UploadedFileIndexDao
    abstract fun backupMessageDao(): BackupMessageDao
    abstract fun notificationThrottleStateDao(): NotificationThrottleStateDao

    companion object {
        private const val DB_NAME = "foldervault.db"

        fun create(context: Context): FolderVaultDatabase =
            Room.databaseBuilder(context, FolderVaultDatabase::class.java, DB_NAME)
                .addMigrations(*DatabaseMigrations.ALL)
                .addCallback(DatabaseCallback)
                .build()
    }
}

private object DatabaseCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("PRAGMA foreign_keys = ON")
    }

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        // Room does not support partial indexes natively.
        // This index enforces that at most one row per (backupConfigId, relativePath) has isCurrentVersion = 1.
        db.execSQL(
            """CREATE UNIQUE INDEX IF NOT EXISTS idx_uploaded_file_index_current_version
               ON UploadedFileIndex (backupConfigId, relativePath)
               WHERE isCurrentVersion = 1"""
        )
    }
}
