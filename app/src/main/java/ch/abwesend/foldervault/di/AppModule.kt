package ch.abwesend.foldervault.di

import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.coroutine.AppDispatchers
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.crypto.IKeyStoreRepository
import ch.abwesend.foldervault.infrastructure.cloud.googledrive.GoogleDriveAuthorizationRepository
import ch.abwesend.foldervault.infrastructure.crypto.AndroidKeyStoreRepository
import ch.abwesend.foldervault.infrastructure.crypto.EncryptionRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single<IDispatchers> { AppDispatchers }
    single<IKeyStoreRepository> { AndroidKeyStoreRepository() }
    single<IEncryptionRepository> { EncryptionRepository() }
    single<ICloudAuthorizer> { GoogleDriveAuthorizationRepository(androidContext()) }
}
