package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Pins down the manual-run routing: incomplete initial sync (never ran, mid-sync, or interrupted
 * with cross-run counters pending) goes to the foreground service; an established backup goes to
 * a WorkManager one-time run. The effective (possibly overridden) policy values are forwarded
 * verbatim to whichever host is chosen.
 */
class StartManualBackupUseCaseTest : StringSpec({

    fun config(lastRunStatus: BackupRunStatus, totalFilesDiscovered: Int) = BackupConfig(
        id = "cfg-1",
        displayName = "Photos",
        sourceTreeUri = "content://tree/photos",
        cloudProvider = "googledrive",
        cloudSubFolderId = "folder-id",
        cloudSubFolderName = "Photos_abc123",
        cloudAccountIdentifier = "account",
        schedule = BackupSchedule.DAILY,
        changedFilePolicy = ChangedFilePolicy.OVERWRITE,
        encryptionEnabled = false,
        encryptedPasswordBlob = null,
        encryptionSaltBase64 = null,
        retentionPolicy = RetentionPolicy.KeepAll,
        networkPolicy = NetworkPolicy.WIFI_ONLY,
        requiresCharging = false,
        createdAt = 0L,
        lastRunAt = null,
        lastRunStatus = lastRunStatus,
        filesUploaded = 0,
        filesSkipped = 0,
        filesFailed = 0,
        bytesUploaded = 0L,
        totalFilesDiscovered = totalFilesDiscovered,
        filesUploadedTotal = 0,
        lastRunCompletedNormally = false,
        isPaused = false,
    )

    data class SchedulerCall(val configId: String, val networkPolicy: NetworkPolicy, val requiresCharging: Boolean)

    class FakeScheduler : IBackupScheduler {
        val oneTimeCalls = mutableListOf<SchedulerCall>()
        override fun scheduleOneTime(
            configId: String,
            networkPolicy: NetworkPolicy,
            requiresCharging: Boolean,
            asContinuation: Boolean,
            forceInline: Boolean,
        ) {
            oneTimeCalls.add(SchedulerCall(configId, networkPolicy, requiresCharging))
        }
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
        ): Boolean = false
        override fun cancelOneTime(configId: String) = Unit
        override fun cancel(configId: String) = Unit
        override fun cancelAll() = Unit
        override fun ensureWatchdogScheduled() = Unit
        override fun observeIsRunning(configId: String): Flow<Boolean> = flowOf(false)
    }

    class FakeLauncher : IForegroundBackupLauncher {
        val startCalls = mutableListOf<SchedulerCall>()
        override fun start(configId: String, networkPolicy: NetworkPolicy, requiresCharging: Boolean) {
            startCalls.add(SchedulerCall(configId, networkPolicy, requiresCharging))
        }
    }

    "IDLE config (never ran) routes to the foreground service" {
        val scheduler = FakeScheduler()
        val launcher = FakeLauncher()
        StartManualBackupUseCase(scheduler, launcher)
            .start(config(BackupRunStatus.IDLE, totalFilesDiscovered = 0), NetworkPolicy.WIFI_ONLY, false)
        launcher.startCalls.size shouldBe 1
        scheduler.oneTimeCalls.size shouldBe 0
    }

    "initial sync in progress routes to the foreground service" {
        val scheduler = FakeScheduler()
        val launcher = FakeLauncher()
        StartManualBackupUseCase(scheduler, launcher)
            .start(
                config(BackupRunStatus.INITIAL_SYNC_IN_PROGRESS, totalFilesDiscovered = 500),
                NetworkPolicy.WIFI_ONLY,
                false,
            )
        launcher.startCalls.size shouldBe 1
        scheduler.oneTimeCalls.size shouldBe 0
    }

    "interrupted initial sync (CANCELLED with pending counters) routes to the foreground service" {
        val scheduler = FakeScheduler()
        val launcher = FakeLauncher()
        StartManualBackupUseCase(scheduler, launcher)
            .start(config(BackupRunStatus.CANCELLED, totalFilesDiscovered = 500), NetworkPolicy.WIFI_ONLY, false)
        launcher.startCalls.size shouldBe 1
        scheduler.oneTimeCalls.size shouldBe 0
    }

    "established backup (UP_TO_DATE, counters reset) routes to WorkManager" {
        val scheduler = FakeScheduler()
        val launcher = FakeLauncher()
        StartManualBackupUseCase(scheduler, launcher)
            .start(config(BackupRunStatus.UP_TO_DATE, totalFilesDiscovered = 0), NetworkPolicy.WIFI_ONLY, false)
        launcher.startCalls.size shouldBe 0
        scheduler.oneTimeCalls.size shouldBe 1
    }

    "failed run without pending counters routes to WorkManager" {
        val scheduler = FakeScheduler()
        val launcher = FakeLauncher()
        StartManualBackupUseCase(scheduler, launcher)
            .start(config(BackupRunStatus.FAILED, totalFilesDiscovered = 0), NetworkPolicy.WIFI_ONLY, false)
        launcher.startCalls.size shouldBe 0
        scheduler.oneTimeCalls.size shouldBe 1
    }

    "effective policy overrides are forwarded verbatim" {
        val scheduler = FakeScheduler()
        val launcher = FakeLauncher()
        StartManualBackupUseCase(scheduler, launcher)
            .start(config(BackupRunStatus.IDLE, 0), NetworkPolicy.ANY, requiresCharging = true)
        launcher.startCalls.single() shouldBe SchedulerCall("cfg-1", NetworkPolicy.ANY, true)
    }

    "needsForegroundService predicate matrix" {
        StartManualBackupUseCase.needsForegroundService(BackupRunStatus.IDLE, 0) shouldBe true
        StartManualBackupUseCase.needsForegroundService(BackupRunStatus.INITIAL_SYNC_IN_PROGRESS, 0) shouldBe true
        StartManualBackupUseCase.needsForegroundService(BackupRunStatus.CANCELLED, 1) shouldBe true
        StartManualBackupUseCase.needsForegroundService(BackupRunStatus.FAILED, 42) shouldBe true
        StartManualBackupUseCase.needsForegroundService(BackupRunStatus.UP_TO_DATE, 0) shouldBe false
        StartManualBackupUseCase.needsForegroundService(BackupRunStatus.COMPLETED_WITH_WARNINGS, 0) shouldBe false
        StartManualBackupUseCase.needsForegroundService(BackupRunStatus.FAILED, 0) shouldBe false
        StartManualBackupUseCase.needsForegroundService(BackupRunStatus.CANCELLED, 0) shouldBe false
    }
})
