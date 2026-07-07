package ch.abwesend.foldervault.infrastructure.room

import android.content.Context

/**
 * Isolates the physical file-system access to the local database so that
 * [DatabaseRecoveryService] can be unit-tested without a real database file.
 */
internal interface IDatabaseFileAccess {

    /**
     * Forces the database open, running any pending migrations — Room opens lazily on first
     * access, so this is where a missing or broken migration actually throws.
     */
    fun openDatabase()

    /** Deletes the database file(s), including journal / WAL side-files. */
    fun deleteDatabase()
}

/** Implements [IDatabaseFileAccess] on top of the Room [FolderVaultDatabase]. */
internal class RoomDatabaseFileAccess(
    context: Context,
    private val database: FolderVaultDatabase,
) : IDatabaseFileAccess {

    private val appContext = context.applicationContext

    override fun openDatabase() {
        database.openHelper.writableDatabase
    }

    override fun deleteDatabase() {
        appContext.deleteDatabase(FolderVaultDatabase.DB_NAME)
    }
}
