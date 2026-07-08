package ch.abwesend.foldervault.infrastructure.room

import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Exercises [DatabaseRecoveryService] against a fake [IDatabaseFileAccess]: the physical
 * file-system access is deliberately abstracted away so these tests run as plain JVM tests
 * (no Robolectric, no real database file). The real file behavior — e.g. a schema version
 * Room cannot migrate down from — maps to [IDatabaseFileAccess.openDatabase] throwing.
 */
class DatabaseRecoveryServiceTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val fileAccess = FakeDatabaseFileAccess()
    val scheduler = FakeBackupScheduler()

    val dispatchers = object : IDispatchers {
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
    }

    val service = DatabaseRecoveryService(fileAccess, scheduler, dispatchers)

    "verifyDatabaseHealth succeeds when the database opens" {
        runTest {
            val result = service.verifyDatabaseHealth()

            result.shouldBeInstanceOf<SuccessResult<Unit>>()
        }
    }

    "verifyDatabaseHealth fails when opening the database throws (e.g. missing migration)" {
        runTest {
            val cause = IllegalStateException("A migration from 99 to 3 was required but not found")
            fileAccess.openFailure = cause

            val result = service.verifyDatabaseHealth()

            result.shouldBeInstanceOf<ErrorResult<Exception>>().error shouldBe cause
        }
    }

    "resetDatabase deletes the database and reopens it afterwards" {
        runTest {
            val result = service.resetDatabase()

            result.shouldBeInstanceOf<SuccessResult<Unit>>()
            fileAccess.calls shouldContainExactly listOf("deleteDatabase", "openDatabase")
        }
    }

    "resetDatabase cancels all scheduled backup work" {
        runTest {
            service.resetDatabase()

            scheduler.cancelAllCalls shouldBe 1
        }
    }

    "resetDatabase recovers a database that fails to open" {
        runTest {
            fileAccess.openFailure = IllegalStateException("A migration from 99 to 3 was required but not found")
            service.verifyDatabaseHealth().shouldBeInstanceOf<ErrorResult<Exception>>()

            val result = service.resetDatabase()

            result.shouldBeInstanceOf<SuccessResult<Unit>>()
            service.verifyDatabaseHealth().shouldBeInstanceOf<SuccessResult<Unit>>()
        }
    }

    "resetDatabase fails when the fresh database cannot be opened" {
        runTest {
            val cause = IllegalStateException("disk full")
            fileAccess.openFailure = cause
            fileAccess.deletionFixesOpenFailure = false

            val result = service.resetDatabase()

            result.shouldBeInstanceOf<ErrorResult<Exception>>().error shouldBe cause
        }
    }

    "resetDatabase fails without touching the scheduler when deletion throws" {
        runTest {
            val cause = IllegalStateException("cannot delete")
            fileAccess.deleteFailure = cause

            val result = service.resetDatabase()

            result.shouldBeInstanceOf<ErrorResult<Exception>>().error shouldBe cause
            scheduler.cancelAllCalls shouldBe 0
        }
    }

    "verifyDatabaseHealth rethrows coroutine cancellation instead of reporting an error" {
        runTest {
            fileAccess.openFailure = CancellationException("cancelled")
            fileAccess.deletionFixesOpenFailure = false

            shouldThrow<CancellationException> { service.verifyDatabaseHealth() }
        }
    }
})

/**
 * Replaces the physical database file with in-memory state: deleting the "file" clears a
 * configured open-failure, mirroring how deleting a broken database lets Room recreate a
 * healthy one on the next open.
 */
private class FakeDatabaseFileAccess : IDatabaseFileAccess {

    /** Chronological record of the calls received, by method name. */
    val calls = mutableListOf<String>()

    /** When set, [openDatabase] throws this — simulates a broken / unmigratable database. */
    var openFailure: Exception? = null

    /** When set, [deleteDatabase] throws this. */
    var deleteFailure: Exception? = null

    /** Whether deleting the database heals a configured [openFailure] (the realistic default). */
    var deletionFixesOpenFailure: Boolean = true

    override fun openDatabase() {
        calls += "openDatabase"
        openFailure?.let { throw it }
    }

    override fun deleteDatabase() {
        calls += "deleteDatabase"
        deleteFailure?.let { throw it }
        if (deletionFixesOpenFailure) openFailure = null
    }
}

/** Call-counting stand-in for the WorkManager-backed scheduler. */
private class FakeBackupScheduler : IBackupScheduler {

    var cancelAllCalls = 0
        private set

    override fun scheduleOneTime(
        configId: String,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
        asContinuation: Boolean,
    ) = Unit

    override fun schedulePeriodicIfNeeded(
        configId: String,
        schedule: BackupSchedule,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
        globalDefault: BackupSchedule,
    ) = Unit

    override suspend fun scheduleChargingFallback(
        configId: String,
        networkPolicy: NetworkPolicy,
        asContinuation: Boolean,
    ) = true

    override fun cancel(configId: String) = Unit

    override fun cancelAll() {
        cancelAllCalls++
    }

    override fun observeIsRunning(configId: String): Flow<Boolean> = flowOf(false)
}
