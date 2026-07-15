package ch.abwesend.foldervault.view

import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IForegroundBackupLauncher
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.NetworkPolicy
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

    "backUpNow on a config that never ran routes to the foreground launcher instead of WorkManager" {
        val configId = "cfg-18"
        val launcher = mockk<IForegroundBackupLauncher>(relaxed = true)
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, lastRunStatus = BackupRunStatus.IDLE),
            foregroundLauncher = launcher,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.backUpNow()

        verify(exactly = 1) { launcher.start(configId, NetworkPolicy.WIFI_ONLY, false) }
        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any()) }
        job.cancel()
    }

    "autoStartBackup starts the initial upload once the config loads, if it never ran" {
        val configId = "cfg-19"
        val launcher = mockk<IForegroundBackupLauncher>(relaxed = true)
        val (vm, _) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, lastRunStatus = BackupRunStatus.IDLE),
            foregroundLauncher = launcher,
            autoStartBackup = true,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        verify(exactly = 1) { launcher.start(configId, NetworkPolicy.WIFI_ONLY, false) }
        job.cancel()
    }

    "autoStartBackup does nothing when the config already ran" {
        val configId = "cfg-20"
        val launcher = mockk<IForegroundBackupLauncher>(relaxed = true)
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, lastRunStatus = BackupRunStatus.UP_TO_DATE),
            foregroundLauncher = launcher,
            autoStartBackup = true,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        verify(exactly = 0) { launcher.start(any(), any(), any()) }
        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any()) }
        job.cancel()
    }

    "reliableExecutionActive is false when the extended-run-time opt-in is off" {
        val configId = "cfg-ra1"
        val (vm, _) = buildVm(
            configId,
            makeConfig(configId, isPaused = false),
            appSettings = AppSettings(exactAlarmBackupsEnabled = false),
        )
        val job = vm.reliableExecutionActive.launchIn(CoroutineScope(testDispatcher))

        vm.reliableExecutionActive.value shouldBe false
        job.cancel()
    }

    "reliableExecutionActive is true when the opt-in is on and exact alarms are available" {
        val configId = "cfg-ra2"
        // Plain JVM tests report SDK_INT = 0 (< 31), where the permission is irrelevant and the
        // opt-in alone activates the enhancement — matching ExecutionStrategySelector's own matrix.
        val (vm, _) = buildVm(
            configId,
            makeConfig(configId, isPaused = false),
            fgsLaunchScheduler = mockk(relaxed = true) { every { isExactAlarmPermitted() } returns true },
            appSettings = AppSettings(exactAlarmBackupsEnabled = true),
        )
        val job = vm.reliableExecutionActive.launchIn(CoroutineScope(testDispatcher))

        vm.reliableExecutionActive.value shouldBe true
        job.cancel()
    }

    "continuesAutomatically is false for a manual-only config" {
        val configId = "cfg-21"
        val (vm, _) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, schedule = BackupSchedule.MANUAL_ONLY),
        )
        val job = vm.continuesAutomatically.launchIn(CoroutineScope(testDispatcher))

        vm.continuesAutomatically.value shouldBe false
        job.cancel()
    }

    "continuesAutomatically resolves a delegating schedule against the global default" {
        val configId = "cfg-22"
        // The default AppSettings has a DAILY global default — a config delegating to it
        // therefore continues automatically.
        val (vm, _) = buildVm(
            configId,
            makeConfig(configId, isPaused = false, schedule = BackupSchedule.USE_GLOBAL_DEFAULT),
        )
        val job = vm.continuesAutomatically.launchIn(CoroutineScope(testDispatcher))

        vm.continuesAutomatically.value shouldBe true
        job.cancel()
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

    "unpausing re-registers the periodic schedule" {
        val configId = "cfg-21"
        val config = makeConfig(configId, isPaused = true)
        val configRepo = mockk<IBackupConfigRepository>(relaxed = true) {
            every { getById(configId) } returns flowOf(config)
        }
        val (vm, scheduler) = buildVm(configId, config, configRepo = configRepo)
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.togglePause()

        // Pausing cancelled the periodic slot, so unpausing must enqueue it afresh; the
        // scheduler's short first-run delay then lets the overdue backup run promptly (RV-18).
        verify(exactly = 1) {
            scheduler.schedulePeriodicIfNeeded(
                configId = configId,
                schedule = BackupSchedule.DAILY,
                networkPolicy = NetworkPolicy.WIFI_ONLY,
                requiresCharging = false,
                globalDefault = BackupSchedule.DAILY,
            )
        }
        job.cancel()
    }
})
