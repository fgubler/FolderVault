package ch.abwesend.foldervault.di

import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.coroutine.AppDispatchers
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.crypto.IKeyStoreRepository
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.infrastructure.backup.BackupNotificationManager
import ch.abwesend.foldervault.infrastructure.backup.BackupRunner
import ch.abwesend.foldervault.infrastructure.backup.BackupScheduler
import ch.abwesend.foldervault.infrastructure.cloud.googledrive.GoogleDriveAuthorizationRepository
import ch.abwesend.foldervault.infrastructure.crypto.AndroidKeyStoreRepository
import ch.abwesend.foldervault.infrastructure.crypto.EncryptionRepository
import ch.abwesend.foldervault.infrastructure.crypto.Fvc1Cipher
import ch.abwesend.foldervault.infrastructure.room.FolderVaultDatabase
import ch.abwesend.foldervault.infrastructure.settings.AppSettingsRepository
import org.koin.android.ext.koin.androidContext
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

    // Settings
    single<IAppSettingsRepository> { AppSettingsRepository(androidContext()) }

    // Backup notifications and scheduling
    single { BackupNotificationManager(androidContext(), get(), get()) }
    single { BackupScheduler(androidContext()) }

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
}
