package ch.abwesend.foldervault.di

import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupMessageRepository
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.coroutine.AppDispatchers
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.crypto.IKeyStoreRepository
import ch.abwesend.foldervault.domain.logging.ILogExporter
import ch.abwesend.foldervault.domain.logging.ITelemetryToggle
import ch.abwesend.foldervault.domain.network.INetworkConnectivityChecker
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.infrastructure.backup.BackupConfigRepository
import ch.abwesend.foldervault.infrastructure.backup.BackupMessageRepository
import ch.abwesend.foldervault.infrastructure.backup.BackupNotificationManager
import ch.abwesend.foldervault.infrastructure.backup.BackupRunner
import ch.abwesend.foldervault.infrastructure.backup.BackupScheduler
import ch.abwesend.foldervault.infrastructure.cloud.googledrive.GoogleDriveAuthorizationRepository
import ch.abwesend.foldervault.infrastructure.crypto.AndroidKeyStoreRepository
import ch.abwesend.foldervault.infrastructure.crypto.EncryptionRepository
import ch.abwesend.foldervault.infrastructure.crypto.Fvc1Cipher
import ch.abwesend.foldervault.infrastructure.logging.FirebaseTelemetryToggle
import ch.abwesend.foldervault.infrastructure.logging.LocalLogFiles
import ch.abwesend.foldervault.infrastructure.network.AndroidNetworkConnectivityChecker
import ch.abwesend.foldervault.infrastructure.restore.RestoreEngine
import ch.abwesend.foldervault.infrastructure.room.FolderVaultDatabase
import ch.abwesend.foldervault.infrastructure.settings.AppSettingsRepository
import ch.abwesend.foldervault.view.viewmodel.AddEditBackupViewModel
import ch.abwesend.foldervault.view.viewmodel.BackupDetailViewModel
import ch.abwesend.foldervault.view.viewmodel.HomeViewModel
import ch.abwesend.foldervault.view.viewmodel.OnboardingViewModel
import ch.abwesend.foldervault.view.viewmodel.RestoreViewModel
import ch.abwesend.foldervault.view.viewmodel.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single<IDispatchers> { AppDispatchers }
    single<IKeyStoreRepository> { AndroidKeyStoreRepository() }
    single<IEncryptionRepository> { EncryptionRepository() }
    single<IFvc1Cipher> { Fvc1Cipher() }
    single<ICloudAuthorizer> { GoogleDriveAuthorizationRepository(androidContext()) }

    // Room
    single { FolderVaultDatabase.create(androidContext()) }
    single { get<FolderVaultDatabase>().backupConfigDao() }
    single { get<FolderVaultDatabase>().uploadedFileIndexDao() }
    single { get<FolderVaultDatabase>().backupMessageDao() }
    single { get<FolderVaultDatabase>().notificationThrottleStateDao() }

    // Repositories
    single<IBackupConfigRepository> { BackupConfigRepository(get()) }
    single<IBackupMessageRepository> { BackupMessageRepository(get()) }
    single<IRestoreEngine> { RestoreEngine(androidContext(), get(), get()) }

    // Settings
    single<IAppSettingsRepository> { AppSettingsRepository(androidContext()) }
    single<ITelemetryToggle> { FirebaseTelemetryToggle(androidContext()) }
    single { LocalLogFiles(androidContext()) }
    single<ILogExporter> { get<LocalLogFiles>() }

    // Backup notifications and scheduling
    single { BackupNotificationManager(androidContext(), get(), get()) }
    single<IBackupScheduler> { BackupScheduler(androidContext()) }
    single<INetworkConnectivityChecker> { AndroidNetworkConnectivityChecker(androidContext()) }

    // Backup pipeline
    single {
        BackupRunner(
            context = androidContext(),
            authorizer = get(),
            cipher = get(),
            encryptionRepository = get(),
            backupConfigDao = get(),
            uploadedFileIndexDao = get(),
            backupMessageDao = get(),
            settingsRepository = get(),
            dispatchers = get(),
        )
    }

    // ViewModels
    viewModel { RestoreViewModel(get()) }
    viewModel { HomeViewModel(get(), get()) }
    viewModel { OnboardingViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get()) }
    viewModel { params ->
        AddEditBackupViewModel(
            configRepo = get(),
            scheduler = get(),
            authorizer = get(),
            encryptionRepo = get(),
            cipher = get(),
            settingsRepo = get(),
            existingConfigId = params.getOrNull(),
        )
    }
    viewModel { params ->
        BackupDetailViewModel(
            configId = params.get(),
            configRepo = get(),
            messageRepo = get(),
            scheduler = get(),
            encryptionRepo = get(),
            settingsRepo = get(),
            connectivityChecker = get(),
        )
    }
}
