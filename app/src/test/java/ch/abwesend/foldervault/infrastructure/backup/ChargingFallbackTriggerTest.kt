package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.logging.ILogger
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.infrastructure.room.dao.BackupRunDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for the streak-detection helper that guards the charging-only fallback trigger.
 * Verified in isolation from [BackupRunner] so the streak rules can evolve without a
 * heavyweight runner-level test.
 */
class ChargingFallbackTriggerTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    beforeTest { LoggerProvider.configure { mockk<ILogger>(relaxed = true) } }

    fun makeConfig(
        id: String = "cfg-1",
        requiresCharging: Boolean = false,
        networkPolicy: NetworkPolicy = NetworkPolicy.WIFI_ONLY,
    ) = BackupConfigEntity(
        id = id,
        displayName = "Test",
        sourceTreeUri = "",
        cloudProvider = "google_drive",
        cloudSubFolderId = "sub-id",
        cloudSubFolderName = "test_sub",
        cloudAccountIdentifier = "user@test.com",
        schedule = BackupSchedule.DAILY,
        changedFilePolicy = ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP,
        encryptionEnabled = false,
        encryptedPasswordBlob = null,
        encryptionParams = null,
        retentionPolicy = RetentionPolicy.KeepAll,
        networkPolicy = networkPolicy,
        requiresCharging = requiresCharging,
        createdAt = 0L,
        lastRunAt = null,
        lastRunStatus = BackupRunStatus.IDLE,
        filesUploaded = 0,
        filesSkipped = 0,
        filesFailed = 0,
        bytesUploaded = 0L,
        totalFilesDiscovered = 0,
        filesUploadedTotal = 0,
        lastRunCompletedNormally = false,
    )

    "schedules fallback when the last N runs were all cancelled" {
        val config = makeConfig(id = "cfg-A", networkPolicy = NetworkPolicy.WIFI_ONLY)
        val dao = mockk<BackupRunDao>()
        val scheduler = mockk<IBackupScheduler>(relaxed = true)
        coEvery {
            dao.getRecentStatuses("cfg-A", ChargingFallbackTrigger.CANCELLATION_STREAK_THRESHOLD)
        } returns List(ChargingFallbackTrigger.CANCELLATION_STREAK_THRESHOLD) { BackupRunStatus.CANCELLED }

        runTest { ChargingFallbackTrigger.maybeSchedule(config, dao, scheduler) }

        coVerify(exactly = 1) { scheduler.scheduleChargingFallback("cfg-A", NetworkPolicy.WIFI_ONLY) }
    }

    "does not schedule when at least one recent run succeeded" {
        val config = makeConfig(id = "cfg-B")
        val dao = mockk<BackupRunDao>()
        val scheduler = mockk<IBackupScheduler>(relaxed = true)
        coEvery {
            dao.getRecentStatuses("cfg-B", ChargingFallbackTrigger.CANCELLATION_STREAK_THRESHOLD)
        } returns listOf(BackupRunStatus.CANCELLED, BackupRunStatus.UP_TO_DATE, BackupRunStatus.CANCELLED)

        runTest { ChargingFallbackTrigger.maybeSchedule(config, dao, scheduler) }

        coVerify(exactly = 0) { scheduler.scheduleChargingFallback(any(), any()) }
    }

    "does not schedule when fewer than threshold rows exist" {
        val config = makeConfig(id = "cfg-C")
        val dao = mockk<BackupRunDao>()
        val scheduler = mockk<IBackupScheduler>(relaxed = true)
        // Only 2 rows total — this is the config's first-ever cancel-streak; give it more time.
        coEvery {
            dao.getRecentStatuses("cfg-C", ChargingFallbackTrigger.CANCELLATION_STREAK_THRESHOLD)
        } returns listOf(BackupRunStatus.CANCELLED, BackupRunStatus.CANCELLED)

        runTest { ChargingFallbackTrigger.maybeSchedule(config, dao, scheduler) }

        coVerify(exactly = 0) { scheduler.scheduleChargingFallback(any(), any()) }
    }

    "does not schedule when the config already requires charging" {
        val config = makeConfig(id = "cfg-D", requiresCharging = true)
        val dao = mockk<BackupRunDao>()
        val scheduler = mockk<IBackupScheduler>(relaxed = true)

        runTest { ChargingFallbackTrigger.maybeSchedule(config, dao, scheduler) }

        // We must not even query the DAO when the config already requires charging —
        // that would waste I/O for a decision that's already determined.
        coVerify(exactly = 0) { dao.getRecentStatuses(any(), any()) }
        coVerify(exactly = 0) { scheduler.scheduleChargingFallback(any(), any()) }
    }

    "passes through the config's network policy so the fallback stays Wi-Fi-only when set" {
        val config = makeConfig(id = "cfg-E", networkPolicy = NetworkPolicy.ANY)
        val dao = mockk<BackupRunDao>()
        val scheduler = mockk<IBackupScheduler>(relaxed = true)
        coEvery {
            dao.getRecentStatuses("cfg-E", ChargingFallbackTrigger.CANCELLATION_STREAK_THRESHOLD)
        } returns List(ChargingFallbackTrigger.CANCELLATION_STREAK_THRESHOLD) { BackupRunStatus.CANCELLED }

        runTest { ChargingFallbackTrigger.maybeSchedule(config, dao, scheduler) }

        coVerify(exactly = 1) { scheduler.scheduleChargingFallback("cfg-E", NetworkPolicy.ANY) }
    }
})
