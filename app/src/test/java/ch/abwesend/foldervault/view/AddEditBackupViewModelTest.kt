package ch.abwesend.folderVault.view

import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.cloud.CloudAuthResult
import ch.abwesend.foldervault.domain.cloud.CloudFolder
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.logging.ILogger
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.view.viewmodel.AddEditBackupViewModel
import ch.abwesend.foldervault.view.viewmodel.CloudSetupState
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditBackupViewModelTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val testDispatcher = UnconfinedTestDispatcher()

    beforeTest {
        Dispatchers.setMain(testDispatcher)
        LoggerProvider.configure { mockk<ILogger>(relaxed = true) }
    }
    afterTest { Dispatchers.resetMain() }

    fun makeProvider(): ICloudStorageProvider = mockk {
        coEvery { createRootFolder() } returns SuccessResult(CloudFolder("folder-id", "FolderVault_test"))
        coEvery { getAccountIdentifier() } returns SuccessResult("user@test.com")
    }

    fun makeVm(provider: ICloudStorageProvider): AddEditBackupViewModel {
        val authorizer = mockk<ICloudAuthorizer> {
            coEvery { authorize() } returns CloudAuthResult.Authorized(provider)
        }
        val settingsRepo = mockk<IAppSettingsRepository> {
            every { settings } returns flowOf(AppSettings())
        }
        return AddEditBackupViewModel(
            configRepo = mockk<IBackupConfigRepository>(relaxed = true),
            scheduler = mockk<IBackupScheduler>(relaxed = true),
            authorizer = authorizer,
            encryptionRepo = mockk<IEncryptionRepository>(relaxed = true),
            cipher = mockk<IFvc1Cipher>(relaxed = true),
            settingsRepo = settingsRepo,
            existingConfigId = null,
        )
    }

    "startDriveSetup transitions to Done after folder is created" {
        val provider = makeProvider()
        val vm = makeVm(provider)

        vm.startDriveSetup()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.form.value.cloudSetup
        state.shouldBeInstanceOf<CloudSetupState.Done>()
        state.folderId shouldBe "folder-id"
    }
})
