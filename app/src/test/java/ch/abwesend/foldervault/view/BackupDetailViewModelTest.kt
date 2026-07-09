package ch.abwesend.foldervault.view

import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupMessageRepository
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.network.INetworkConnectivityChecker
import ch.abwesend.foldervault.domain.system.IChargingStateChecker
import ch.abwesend.foldervault.view.viewmodel.BackupDetailViewModel
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class BackupDetailViewModelTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val testDispatcher = UnconfinedTestDispatcher()

    beforeTest { Dispatchers.setMain(testDispatcher) }
    afterTest { Dispatchers.resetMain() }

    fun makeConfig(
        id: String,
        isPaused: Boolean,
        networkPolicy: NetworkPolicy = NetworkPolicy.WIFI_ONLY,
        requiresCharging: Boolean = false,
    ) = BackupConfig(
        id = id,
        displayName = "Test",
        sourceTreeUri = "",
        cloudProvider = "google_drive",
        cloudSubFolderId = "fid",
        cloudSubFolderName = "test_sub",
        cloudAccountIdentifier = "user@test.com",
        schedule = BackupSchedule.DAILY,
        changedFilePolicy = ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP,
        encryptionEnabled = false,
        encryptedPasswordBlob = null,
        encryptionSaltBase64 = null,
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
        isPaused = isPaused,
    )

    /**
     * Spins up a [BackupDetailViewModel] with sensible defaults: a config repo returning [config],
     * a message repo that surfaces nothing, a relaxed scheduler whose isRunning state defaults to
     * [isRunning], a connectivity checker that reports [onUnmetered], and a charging checker that
     * reports [isCharging]. Encryption + settings repos are unused in these tests so they are bare
     * [mockk]s.
     */
    fun buildVm(
        configId: String,
        config: BackupConfig?,
        isRunning: Boolean = false,
        onUnmetered: Boolean = true,
        isCharging: Boolean = true,
        scheduler: IBackupScheduler = mockk(relaxed = true) {
            every { observeIsRunning(configId) } returns MutableStateFlow(isRunning)
        },
        configRepo: IBackupConfigRepository = mockk {
            every { getById(configId) } returns flowOf(config)
        },
    ): Pair<BackupDetailViewModel, IBackupScheduler> {
        val messageRepo = mockk<IBackupMessageRepository> {
            every { getUndismissed(configId) } returns flowOf(emptyList())
            every { getUnreadCountBySeverity(configId, any()) } returns flowOf(0)
        }
        val connectivity = mockk<INetworkConnectivityChecker> {
            every { isOnUnmeteredNetwork() } returns onUnmetered
        }
        val charging = mockk<IChargingStateChecker> {
            every { isCharging() } returns isCharging
        }
        val vm = BackupDetailViewModel(
            configId = configId,
            configRepo = configRepo,
            messageRepo = messageRepo,
            scheduler = scheduler,
            encryptionRepo = mockk(),
            settingsRepo = mockk(),
            connectivityChecker = connectivity,
            chargingChecker = charging,
            releaseSafPermissionIfUnused = mockk(relaxed = true),
        )
        return vm to scheduler
    }

    "backUpNow does not call scheduler when config is paused" {
        val configId = "cfg-1"
        val (vm, scheduler) = buildVm(configId, makeConfig(configId, isPaused = true))
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()

        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any()) }
        job.cancel()
    }

    "backUpNow schedules with the config's network policy on Wi-Fi" {
        val configId = "cfg-2"
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, networkPolicy = NetworkPolicy.WIFI_ONLY),
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()

        verify(exactly = 1) {
            scheduler.scheduleOneTime(configId, NetworkPolicy.WIFI_ONLY, false)
        }
        vm.showMeteredOverridePrompt.value shouldBe false
        job.cancel()
    }

    "backUpNow does not schedule when config has not loaded yet" {
        val configId = "cfg-3"
        val (vm, scheduler) = buildVm(configId, config = null)

        vm.backUpNow()

        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any()) }
    }

    "backUpNow does not call scheduler when a backup is already running" {
        val configId = "cfg-4"
        val (vm, scheduler) = buildVm(configId, makeConfig(configId, isPaused = false), isRunning = true)
        val isRunningJob = vm.isRunning.launchIn(CoroutineScope(testDispatcher))
        val configJob = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()

        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any()) }
        isRunningJob.cancel()
        configJob.cancel()
    }

    "backUpNow on Wi-Fi-only config without Wi-Fi shows the prompt and does not schedule" {
        val configId = "cfg-5"
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, networkPolicy = NetworkPolicy.WIFI_ONLY),
            onUnmetered = false,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()

        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any()) }
        vm.showMeteredOverridePrompt.value shouldBe true
        job.cancel()
    }

    "ANY-network config schedules immediately even when not on Wi-Fi" {
        val configId = "cfg-6"
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, networkPolicy = NetworkPolicy.ANY),
            onUnmetered = false,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()

        verify(exactly = 1) {
            scheduler.scheduleOneTime(configId, NetworkPolicy.ANY, false)
        }
        vm.showMeteredOverridePrompt.value shouldBe false
        job.cancel()
    }

    "confirmMeteredOverride schedules with ANY and dismisses the prompt" {
        val configId = "cfg-7"
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, networkPolicy = NetworkPolicy.WIFI_ONLY),
            onUnmetered = false,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()
        vm.confirmMeteredOverride()

        verify(exactly = 1) {
            scheduler.scheduleOneTime(configId, NetworkPolicy.ANY, false)
        }
        vm.showMeteredOverridePrompt.value shouldBe false
        job.cancel()
    }

    "dismissMeteredOverride clears the prompt without scheduling" {
        val configId = "cfg-8"
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, networkPolicy = NetworkPolicy.WIFI_ONLY),
            onUnmetered = false,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()
        vm.dismissMeteredOverride()

        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any()) }
        vm.showMeteredOverridePrompt.value shouldBe false
        job.cancel()
    }

    "backUpNow on charging-only config while unplugged shows the charging prompt and does not schedule" {
        val configId = "cfg-9"
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, networkPolicy = NetworkPolicy.ANY, requiresCharging = true),
            isCharging = false,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()

        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any()) }
        vm.showChargingOverridePrompt.value shouldBe true
        job.cancel()
    }

    "backUpNow on charging-only config while charging schedules immediately with charging kept" {
        val configId = "cfg-10"
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, networkPolicy = NetworkPolicy.ANY, requiresCharging = true),
            isCharging = true,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()

        verify(exactly = 1) { scheduler.scheduleOneTime(configId, NetworkPolicy.ANY, true) }
        vm.showChargingOverridePrompt.value shouldBe false
        job.cancel()
    }

    "confirmChargingOverride schedules without the charging requirement and dismisses the prompt" {
        val configId = "cfg-11"
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, networkPolicy = NetworkPolicy.ANY, requiresCharging = true),
            isCharging = false,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()
        vm.confirmChargingOverride()

        verify(exactly = 1) { scheduler.scheduleOneTime(configId, NetworkPolicy.ANY, false) }
        vm.showChargingOverridePrompt.value shouldBe false
        job.cancel()
    }

    "dismissChargingOverride clears the prompt without scheduling" {
        val configId = "cfg-13"
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, networkPolicy = NetworkPolicy.ANY, requiresCharging = true),
            isCharging = false,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()
        vm.dismissChargingOverride()

        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any()) }
        vm.showChargingOverridePrompt.value shouldBe false
        job.cancel()
    }

    "confirmChargingOverride without an open prompt schedules nothing" {
        val configId = "cfg-15"
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, networkPolicy = NetworkPolicy.ANY, requiresCharging = true),
            isCharging = false,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        // No backUpNow() first — nothing captured a network policy, so a stray confirm
        // (e.g. from a future UI path that forgets to open the prompt) must not schedule.
        vm.confirmChargingOverride()

        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any()) }
        job.cancel()
    }

    "dismissChargingOverride clears the pending policy so a later stray confirm schedules nothing" {
        val configId = "cfg-16"
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, networkPolicy = NetworkPolicy.ANY, requiresCharging = true),
            isCharging = false,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()
        vm.dismissChargingOverride()
        vm.confirmChargingOverride()

        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any()) }
        job.cancel()
    }

    "metered then charging prompts resolve sequentially without stacking" {
        val configId = "cfg-14"
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, networkPolicy = NetworkPolicy.WIFI_ONLY, requiresCharging = true),
            onUnmetered = false,
            isCharging = false,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()
        // First only the Wi-Fi prompt shows; the charging prompt stays hidden.
        vm.showMeteredOverridePrompt.value shouldBe true
        vm.showChargingOverridePrompt.value shouldBe false

        vm.confirmMeteredOverride()
        // Wi-Fi prompt closes and hands off to the charging prompt; nothing scheduled yet.
        vm.showMeteredOverridePrompt.value shouldBe false
        vm.showChargingOverridePrompt.value shouldBe true
        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any()) }

        vm.confirmChargingOverride()
        // The Wi-Fi override carries through: the run drops both constraints for this one time.
        verify(exactly = 1) { scheduler.scheduleOneTime(configId, NetworkPolicy.ANY, false) }
        vm.showChargingOverridePrompt.value shouldBe false
        job.cancel()
    }

    "pausing persists the pause flag before cancelling scheduled work" {
        val configId = "cfg-17"
        val config = makeConfig(configId, isPaused = false)
        val configRepo = mockk<IBackupConfigRepository>(relaxed = true) {
            every { getById(configId) } returns flowOf(config)
        }
        val (vm, scheduler) = buildVm(configId, config, configRepo = configRepo)
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.togglePause()

        // Cancelling makes the in-flight worker re-fetch the config to decide whether a
        // charging-only fallback may still be scheduled — the pause flag must already be
        // persisted by then, or the worker races the write and re-enqueues work for the
        // config the user just paused.
        coVerifyOrder {
            configRepo.setPaused(configId, true)
            scheduler.cancel(configId)
        }
        job.cancel()
    }
})
