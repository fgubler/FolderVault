package ch.abwesend.foldervault.domain.database

import ch.abwesend.foldervault.domain.result.BinaryResult

/**
 * Startup guard around the local database: lets the UI detect an unopenable database
 * (e.g. missing / invalid migration) before any screen touches it, and offers the
 * explicit, user-confirmed escape hatch of wiping the local database.
 */
interface IDatabaseRecoveryService {

    /**
     * Forces the database open (running any pending migrations) and reports whether that
     * worked. Cheap once the database is already open.
     */
    suspend fun verifyDatabaseHealth(): BinaryResult<Unit, Exception>

    /**
     * Deletes the local database and recreates it with a fresh, empty schema. All local data
     * (backup configurations, upload index, history) is lost and scheduled backups are
     * cancelled — data already uploaded to the cloud is not touched. Only ever call this after
     * explicit user confirmation.
     */
    suspend fun resetDatabase(): BinaryResult<Unit, Exception>
}
