package ch.abwesend.foldervault.view

import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupMessageRepository
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.backup.IFgsLaunchScheduler
import ch.abwesend.foldervault.domain.backup.IForegroundBackupLauncher
import ch.abwesend.foldervault.domain.backup.StartManualBackupUseCase
import ch.abwesend.foldervault.domain.cloud.CloudAuthResult
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.network.INetworkConnectivityChecker
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.domain.storage.ReleaseSafPermissionIfUnusedUseCase
import ch.abwesend.foldervault.domain.system.IChargingStateChecker
import ch.abwesend.foldervault.view.viewmodel.BackupDetailViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared fixtures for [BackupDetailViewModel] specs ([BackupDetailViewModelTest] and
 * [BackupDetailViewModelDeleteTest]).
 */

/**
 * Defaults to [BackupRunStatus.UP_TO_DATE] (an established backup): manual runs of such a
 * config go through the WorkManager scheduler, which is what most tests here assert. A
 * config still in (or before) its initial sync routes to the foreground launcher instead —
 * covered by the dedicated routing test and by [StartManualBackupUseCase]'s own tests.
 */
internal fun makeConfig(
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
internal fun buildVm(
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
    fgsLaunchScheduler: IFgsLaunchScheduler = mockk(relaxed = true),
    appSettings: AppSettings = AppSettings(),
    autoStartBackup: Boolean = false,
): Pair<BackupDetailViewModel, IBackupScheduler> {
    val connectivity = mockk<INetworkConnectivityChecker> {
        every { isOnUnmeteredNetwork() } returns onUnmetered
    }
    val charging = mockk<IChargingStateChecker> {
        every { isCharging() } returns isCharging
    }
    val settingsRepo = mockk<IAppSettingsRepository> {
        every { settings } returns flowOf(appSettings)
    }
    val vm = BackupDetailViewModel(
        configId = configId,
        configRepo = configRepo,
        messageRepo = messageRepo,
        scheduler = scheduler,
        fgsLaunchScheduler = fgsLaunchScheduler,
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
 * silently to [ICloudStorageProvider], whose folder deletion yields [deleteResult]. Override
 * either to exercise the consent and failure paths.
 */
internal fun cloudDeps(
    account: String = "user@test.com",
    folderId: String = "fid",
    deleteResult: BinaryResult<Unit, Exception> = SuccessResult(Unit),
): Pair<ICloudAuthorizer, ICloudStorageProvider> {
    val provider = mockk<ICloudStorageProvider> {
        coEvery { deleteFile(folderId) } returns deleteResult
    }
    val authorizer = mockk<ICloudAuthorizer> {
        coEvery { authorize(account) } returns CloudAuthResult.Authorized(provider)
    }
    return authorizer to provider
}
