package ch.abwesend.foldervault.view

import android.app.PendingIntent
import android.content.Intent
import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupMessageRepository
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.backup.IForegroundBackupLauncher
import ch.abwesend.foldervault.domain.backup.StartManualBackupUseCase
import ch.abwesend.foldervault.domain.cloud.CloudAuthResult
import ch.abwesend.foldervault.domain.cloud.CloudNotFoundException
import ch.abwesend.foldervault.domain.cloud.CloudTransientException
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.network.INetworkConnectivityChecker
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.domain.storage.ReleaseSafPermissionIfUnusedUseCase
import ch.abwesend.foldervault.domain.system.IChargingStateChecker
import ch.abwesend.foldervault.view.viewmodel.BackupDetailViewModel
import ch.abwesend.foldervault.view.viewmodel.CloudDeleteState
import ch.abwesend.foldervault.view.viewmodel.DetailEvent
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class BackupDetailViewModelTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val testDispatcher = UnconfinedTestDispatcher()

    beforeTest { Dispatchers.setMain(testDispatcher) }
    afterTest { Dispatchers.resetMain() }

    /**
     * Defaults to [BackupRunStatus.UP_TO_DATE] (an established backup): manual runs of such a
     * config go through the WorkManager scheduler, which is what most tests here assert. A
     * config still in (or before) its initial sync routes to the foreground launcher instead —
     * covered by the dedicated routing test below and by [StartManualBackupUseCase]'s own tests.
     */
    fun makeConfig(
        id: String,
        isPaused: Boolean,
        networkPolicy: NetworkPolicy = NetworkPolicy.WIFI_ONLY,
        requiresCharging: Boolean = false,
        lastRunStatus: BackupRunStatus = BackupRunStatus.UP_TO_DATE,
        schedule: BackupSchedule = BackupSchedule.DAILY,
    ) = BackupConfig(
        id = id,
        displayName = "Test",
        sourceTreeUri = "",
        cloudProvider = "google_drive",
        cloudSubFolderId = "fid",
        cloudSubFolderName = "test_sub",
        cloudAccountIdentifier = "user@test.com",
        schedule = schedule,
        changedFilePolicy = ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP,
        encryptionEnabled = false,
        encryptedPasswordBlob = null,
        encryptionSaltBase64 = null,
        retentionPolicy = RetentionPolicy.KeepAll,
        networkPolicy = networkPolicy,
        requiresCharging = requiresCharging,
        createdAt = 0L,
        lastRunAt = null,
        lastRunStatus = lastRunStatus,
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
     * reports [isCharging]. The settings repo serves default [AppSettings] (daily default
     * schedule); the encryption repo is unused in these tests so it stays a bare [mockk].
     */
    @Suppress("LongParameterList")
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
        messageRepo: IBackupMessageRepository = mockk(relaxed = true) {
            every { getUndismissed(configId) } returns flowOf(emptyList())
            every { getUnreadCountBySeverity(configId, any()) } returns flowOf(0)
        },
        releaseSaf: ReleaseSafPermissionIfUnusedUseCase = mockk(relaxed = true),
        authorizer: ICloudAuthorizer = mockk(relaxed = true),
        foregroundLauncher: IForegroundBackupLauncher = mockk(relaxed = true),
        autoStartBackup: Boolean = false,
    ): Pair<BackupDetailViewModel, IBackupScheduler> {
        val connectivity = mockk<INetworkConnectivityChecker> {
            every { isOnUnmeteredNetwork() } returns onUnmetered
        }
        val charging = mockk<IChargingStateChecker> {
            every { isCharging() } returns isCharging
        }
        val settingsRepo = mockk<IAppSettingsRepository> {
            every { settings } returns flowOf(AppSettings())
        }
        val vm = BackupDetailViewModel(
            configId = configId,
            configRepo = configRepo,
            messageRepo = messageRepo,
            scheduler = scheduler,
            startManualBackup = StartManualBackupUseCase(scheduler, foregroundLauncher),
            authorizer = authorizer,
            encryptionRepo = mockk(),
            settingsRepo = settingsRepo,
            connectivityChecker = connectivity,
            chargingChecker = charging,
            releaseSafPermissionIfUnused = releaseSaf,
            autoStartBackup = autoStartBackup,
        )
        return vm to scheduler
    }

    /**
     * A cloud provider + authorizer pair for the delete-cloud-folder tests: the authorizer resolves
     * silently to [provider], whose folder deletion yields [deleteResult]. Override either to
     * exercise the consent and failure paths.
     */
    fun cloudDeps(
        account: String = "user@test.com",
        folderId: String = "fid",
        deleteResult: ch.abwesend.foldervault.domain.result.BinaryResult<Unit, Exception> = SuccessResult(Unit),
    ): Pair<ICloudAuthorizer, ICloudStorageProvider> {
        val provider = mockk<ICloudStorageProvider> {
            coEvery { deleteFile(folderId) } returns deleteResult
        }
        val authorizer = mockk<ICloudAuthorizer> {
            coEvery { authorize(account) } returns CloudAuthResult.Authorized(provider)
        }
        return authorizer to provider
    }

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

    "deleteBackup(false) deletes locally and never touches the cloud folder" {
        val configId = "cfg-del-1"
        val (authorizer, provider) = cloudDeps()
        val configRepo = mockk<IBackupConfigRepository>(relaxed = true) {
            every { getById(configId) } returns flowOf(makeConfig(configId, isPaused = false))
        }
        val messageRepo = mockk<IBackupMessageRepository>(relaxed = true) {
            every { getUndismissed(configId) } returns flowOf(emptyList())
            every { getUnreadCountBySeverity(configId, any()) } returns flowOf(0)
        }
        val (vm, scheduler) = buildVm(
            configId,
            makeConfig(configId, isPaused = false),
            configRepo = configRepo,
            messageRepo = messageRepo,
            authorizer = authorizer,
        )
        val events = mutableListOf<DetailEvent>()
        val eventsJob = vm.events.onEach { events.add(it) }.launchIn(CoroutineScope(testDispatcher))
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.deleteBackup(alsoDeleteCloudFolder = false)

        coVerify(exactly = 0) { authorizer.authorize(any()) }
        coVerify(exactly = 0) { provider.deleteFile(any()) }
        coVerify(exactly = 1) { messageRepo.deleteAllForConfig(configId) }
        coVerify(exactly = 1) { configRepo.deleteById(configId) }
        verify(exactly = 1) { scheduler.cancel(configId) }
        events shouldBe listOf(DetailEvent.Deleted)
        vm.cloudDeleteState.value shouldBe CloudDeleteState.Idle
        job.cancel()
        eventsJob.cancel()
    }

    "deleteBackup(true) deletes the Drive folder before removing the config" {
        val configId = "cfg-del-2"
        val (authorizer, provider) = cloudDeps(account = "user@test.com", folderId = "fid")
        val configRepo = mockk<IBackupConfigRepository>(relaxed = true) {
            every { getById(configId) } returns flowOf(makeConfig(configId, isPaused = false))
        }
        val (vm, _) = buildVm(
            configId,
            makeConfig(configId, isPaused = false),
            configRepo = configRepo,
            authorizer = authorizer,
        )
        val events = mutableListOf<DetailEvent>()
        val eventsJob = vm.events.onEach { events.add(it) }.launchIn(CoroutineScope(testDispatcher))
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.deleteBackup(alsoDeleteCloudFolder = true)

        // The Drive folder must be gone before the local record is dropped, so a failure can still
        // abort the local delete (see the failure test below).
        coVerifyOrder {
            authorizer.authorize("user@test.com")
            provider.deleteFile("fid")
            configRepo.deleteById(configId)
        }
        events shouldBe listOf(DetailEvent.Deleted)
        vm.cloudDeleteState.value shouldBe CloudDeleteState.Idle
        job.cancel()
        eventsJob.cancel()
    }

    "deleteBackup(true) treats an already-gone Drive folder as deleted" {
        val configId = "cfg-del-3"
        val (authorizer, _) = cloudDeps(deleteResult = ErrorResult(CloudNotFoundException()))
        val configRepo = mockk<IBackupConfigRepository>(relaxed = true) {
            every { getById(configId) } returns flowOf(makeConfig(configId, isPaused = false))
        }
        val (vm, _) = buildVm(
            configId,
            makeConfig(configId, isPaused = false),
            configRepo = configRepo,
            authorizer = authorizer,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.deleteBackup(alsoDeleteCloudFolder = true)

        coVerify(exactly = 1) { configRepo.deleteById(configId) }
        vm.cloudDeleteState.value shouldBe CloudDeleteState.Idle
        job.cancel()
    }

    "deleteBackup(true) keeps the config until the user acknowledges a failed Drive delete" {
        val configId = "cfg-del-4"
        val (authorizer, _) = cloudDeps(deleteResult = ErrorResult(CloudTransientException()))
        val configRepo = mockk<IBackupConfigRepository>(relaxed = true) {
            every { getById(configId) } returns flowOf(makeConfig(configId, isPaused = false))
        }
        val (vm, _) = buildVm(
            configId,
            makeConfig(configId, isPaused = false),
            configRepo = configRepo,
            authorizer = authorizer,
        )
        val events = mutableListOf<DetailEvent>()
        val eventsJob = vm.events.onEach { events.add(it) }.launchIn(CoroutineScope(testDispatcher))
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.deleteBackup(alsoDeleteCloudFolder = true)

        // Drive delete failed: warn, but do NOT drop the config yet.
        vm.cloudDeleteState.value shouldBe CloudDeleteState.FolderDeleteFailed
        coVerify(exactly = 0) { configRepo.deleteById(configId) }
        events.isEmpty() shouldBe true

        // Acknowledging honors the delete anyway — the folder is left on Drive, the config is removed.
        vm.acknowledgeFolderDeleteFailure()

        coVerify(exactly = 1) { configRepo.deleteById(configId) }
        events shouldBe listOf(DetailEvent.Deleted)
        vm.cloudDeleteState.value shouldBe CloudDeleteState.Idle
        job.cancel()
        eventsJob.cancel()
    }

    "deleteBackup(true) surfaces a consent prompt and resumes the delete once consent is given" {
        val configId = "cfg-del-5"
        val provider = mockk<ICloudStorageProvider> {
            coEvery { deleteFile("fid") } returns SuccessResult(Unit)
        }
        val pendingIntent = mockk<PendingIntent>()
        val resultIntent = mockk<Intent>()
        val authorizer = mockk<ICloudAuthorizer> {
            coEvery { authorize("user@test.com") } returns CloudAuthResult.ConsentRequired(pendingIntent)
            coEvery { authorizeFromIntent(resultIntent) } returns SuccessResult(provider)
        }
        val configRepo = mockk<IBackupConfigRepository>(relaxed = true) {
            every { getById(configId) } returns flowOf(makeConfig(configId, isPaused = false))
        }
        val (vm, _) = buildVm(
            configId,
            makeConfig(configId, isPaused = false),
            configRepo = configRepo,
            authorizer = authorizer,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.deleteBackup(alsoDeleteCloudFolder = true)

        val state = vm.cloudDeleteState.value
        state.shouldBeInstanceOf<CloudDeleteState.ConsentRequired>()
        state.pendingIntent shouldBe pendingIntent
        coVerify(exactly = 0) { configRepo.deleteById(configId) }

        vm.handleDriveConsentResult(resultIntent)

        coVerify(exactly = 1) { provider.deleteFile("fid") }
        coVerify(exactly = 1) { configRepo.deleteById(configId) }
        vm.cloudDeleteState.value shouldBe CloudDeleteState.Idle
        job.cancel()
    }

    "deleteBackup(true) treats a cancelled consent as a Drive-delete failure" {
        val configId = "cfg-del-6"
        val pendingIntent = mockk<PendingIntent>()
        val authorizer = mockk<ICloudAuthorizer> {
            coEvery { authorize("user@test.com") } returns CloudAuthResult.ConsentRequired(pendingIntent)
            coEvery { authorizeFromIntent(null) } returns ErrorResult(CloudTransientException())
        }
        val configRepo = mockk<IBackupConfigRepository>(relaxed = true) {
            every { getById(configId) } returns flowOf(makeConfig(configId, isPaused = false))
        }
        val (vm, _) = buildVm(
            configId,
            makeConfig(configId, isPaused = false),
            configRepo = configRepo,
            authorizer = authorizer,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.deleteBackup(alsoDeleteCloudFolder = true)
        vm.handleDriveConsentResult(null)

        vm.cloudDeleteState.value shouldBe CloudDeleteState.FolderDeleteFailed
        coVerify(exactly = 0) { configRepo.deleteById(configId) }

        vm.acknowledgeFolderDeleteFailure()

        coVerify(exactly = 1) { configRepo.deleteById(configId) }
        job.cancel()
    }
})
