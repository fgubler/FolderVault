package ch.abwesend.foldervault.infrastructure.room

import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.database.IDatabaseRecoveryService
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.ifError
import ch.abwesend.foldervault.domain.result.mapValue
import ch.abwesend.foldervault.domain.result.runCatchingAsResult
import kotlinx.coroutines.withContext

/**
 * Implements [IDatabaseRecoveryService] on top of [IDatabaseFileAccess].
 *
 * The health check forces the database open, which is where a missing or broken migration
 * actually throws. The reset deletes the database file(s); the next open then recreates a
 * fresh schema from scratch, which is the only "destructive migration" this app allows
 * (see issue #13).
 */
internal class DatabaseRecoveryService(
    private val fileAccess: IDatabaseFileAccess,
    private val scheduler: IBackupScheduler,
    private val dispatchers: IDispatchers,
) : IDatabaseRecoveryService {

    override suspend fun verifyDatabaseHealth(): BinaryResult<Unit, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult { fileAccess.openDatabase() }
                .mapValue { }
                .ifError { logger.error("Database health check failed", it) }
        }

    override suspend fun resetDatabase(): BinaryResult<Unit, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                logger.warning("Resetting the local database on user request")
                fileAccess.deleteDatabase()
                scheduler.cancelAll()
                fileAccess.openDatabase() // reopen: recreates a fresh, empty schema
            }
                .mapValue { }
                .ifError { logger.error("Resetting the local database failed", it) }
        }
}
