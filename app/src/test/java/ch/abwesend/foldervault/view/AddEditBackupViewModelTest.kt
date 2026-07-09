package ch.abwesend.foldervault.view

import android.net.Uri
import ch.abwesend.foldervault.domain.backup.BackupConfig
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
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.CloudAccountRoot
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.view.viewmodel.AddEditBackupViewModel
import ch.abwesend.foldervault.view.viewmodel.CloudSetupState
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
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
        // loadExisting() derives a folder label via Uri.parse; stub it so the plain-JVM test
        // (no Robolectric) doesn't hit the un-mocked android.net.Uri.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
    }
    afterTest {
        Dispatchers.resetMain()
        unmockkStatic(Uri::class)
    }

    fun makeProvider(): ICloudStorageProvider = mockk {
        coEvery { createRootFolder() } returns SuccessResult(CloudFolder("folder-id", "FolderVault_test"))
        coEvery { getAccountIdentifier() } returns SuccessResult("user@test.com")
        coEvery { getOrCreateChildFolder(any(), any()) } answers {
            SuccessResult(CloudFolder("sub-id", secondArg<String>()))
        }
    }

    fun makeSettingsRepo(initial: AppSettings = AppSettings()): IAppSettingsRepository =
        mockk(relaxed = true) {
            every { settings } returns flowOf(initial)
        }

    fun makeAuthorizer(provider: ICloudStorageProvider): ICloudAuthorizer =
        mockk {
            coEvery { authorize(any()) } returns CloudAuthResult.Authorized(provider)
        }

    fun makeVm(
        provider: ICloudStorageProvider,
        settingsRepo: IAppSettingsRepository = makeSettingsRepo(),
        authorizer: ICloudAuthorizer = makeAuthorizer(provider),
    ): AddEditBackupViewModel = AddEditBackupViewModel(
        configRepo = mockk<IBackupConfigRepository>(relaxed = true),
        scheduler = mockk<IBackupScheduler>(relaxed = true),
        authorizer = authorizer,
        encryptionRepo = mockk<IEncryptionRepository>(relaxed = true),
        cipher = mockk<IFvc1Cipher>(relaxed = true),
        settingsRepo = settingsRepo,
        releaseSafPermissionIfUnused = mockk(relaxed = true),
        existingConfigId = null,
    )

    "fresh install: startDriveSetup creates root and appends it to settings" {
        val provider = makeProvider()
        val settingsRepo = makeSettingsRepo()
        val vm = makeVm(provider, settingsRepo)

        vm.startDriveSetup()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.form.value.cloudSetup
        state.shouldBeInstanceOf<CloudSetupState.Done>()
        state.folderId shouldBe "folder-id"

        val transformSlot = slot<(AppSettings) -> AppSettings>()
        coVerify { settingsRepo.update(capture(transformSlot)) }
        val updated = transformSlot.captured(AppSettings())
        updated.cloudRoots shouldBe listOf(
            CloudAccountRoot(
                accountIdentifier = "user@test.com",
                rootFolderId = "folder-id",
                rootFolderName = "FolderVault_test",
            ),
        )
    }

    "existing account root: startDriveSetup reuses it and does NOT call createRootFolder" {
        val provider = makeProvider()
        val settingsWithRoot = AppSettings(
            cloudRoots = listOf(
                CloudAccountRoot(
                    accountIdentifier = "user@test.com",
                    rootFolderId = "existing-root",
                    rootFolderName = "FolderVault_existing",
                ),
            ),
        )
        val vm = makeVm(provider, makeSettingsRepo(settingsWithRoot))

        vm.startDriveSetup()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.form.value.cloudSetup
        state.shouldBeInstanceOf<CloudSetupState.Done>()
        state.folderId shouldBe "existing-root"
        state.folderName shouldBe "FolderVault_existing"
        coVerify(exactly = 0) { provider.createRootFolder() }
    }

    "second account: startDriveSetup creates a new root and keeps the other account's root" {
        val provider = makeProvider()
        val otherAccountRoot = CloudAccountRoot(
            accountIdentifier = "other@example.com",
            rootFolderId = "other-root",
            rootFolderName = "FolderVault_other",
        )
        val settingsRepo = makeSettingsRepo(AppSettings(cloudRoots = listOf(otherAccountRoot)))
        val vm = makeVm(provider, settingsRepo)

        vm.startDriveSetup()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.form.value.cloudSetup
        state.shouldBeInstanceOf<CloudSetupState.Done>()
        state.folderId shouldBe "folder-id"
        coVerify(exactly = 1) { provider.createRootFolder() }

        val transformSlot = slot<(AppSettings) -> AppSettings>()
        coVerify { settingsRepo.update(capture(transformSlot)) }
        val updated = transformSlot.captured(AppSettings(cloudRoots = listOf(otherAccountRoot)))
        updated.cloudRoots shouldBe listOf(
            otherAccountRoot,
            CloudAccountRoot(
                accountIdentifier = "user@test.com",
                rootFolderId = "folder-id",
                rootFolderName = "FolderVault_test",
            ),
        )
    }

    "startDriveSetup forwards the picked account to the authorizer" {
        val provider = makeProvider()
        val authorizer = makeAuthorizer(provider)
        val vm = makeVm(provider, authorizer = authorizer)

        vm.startDriveSetup("picked@test.com")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { authorizer.authorize("picked@test.com") }
    }

    "startDriveSetup without a picked account authorizes with null for a new config" {
        val provider = makeProvider()
        val authorizer = makeAuthorizer(provider)
        val vm = makeVm(provider, authorizer = authorizer)

        vm.startDriveSetup()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { authorizer.authorize(null) }
    }

    // ── INC-3: password of an existing encrypted config is immutable ──────────

    val encryptedConfig = BackupConfig(
        id = "cfg-1",
        displayName = "My documents",
        sourceTreeUri = "content://tree/docs",
        cloudProvider = "google_drive",
        cloudSubFolderId = "sub-id",
        cloudSubFolderName = "My documents_ab12cd",
        cloudAccountIdentifier = "user@test.com",
        schedule = BackupSchedule.DAILY,
        changedFilePolicy = ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP,
        encryptionEnabled = true,
        encryptedPasswordBlob = "OLD_BLOB",
        encryptionSaltBase64 = "OLD_SALT",
        retentionPolicy = RetentionPolicy.KeepAll,
        networkPolicy = NetworkPolicy.WIFI_ONLY,
        requiresCharging = false,
        createdAt = 1L,
        lastRunAt = null,
        lastRunStatus = BackupRunStatus.IDLE,
        filesUploaded = 0,
        filesSkipped = 0,
        filesFailed = 0,
        bytesUploaded = 0L,
        totalFilesDiscovered = 0,
        filesUploadedTotal = 0,
        lastRunCompletedNormally = false,
        isPaused = false,
    )

    fun makeEditVm(
        config: BackupConfig,
        encryptionRepo: IEncryptionRepository = mockk(relaxed = true),
    ): Pair<AddEditBackupViewModel, IBackupConfigRepository> {
        val configRepo = mockk<IBackupConfigRepository>(relaxed = true)
        every { configRepo.getById(any()) } returns flowOf(config)
        val settingsRepo = makeSettingsRepo(
            AppSettings(
                cloudRoots = listOf(
                    CloudAccountRoot(
                        accountIdentifier = config.cloudAccountIdentifier,
                        rootFolderId = "root-id",
                        rootFolderName = "FolderVault_test",
                    ),
                ),
            ),
        )
        val vm = AddEditBackupViewModel(
            configRepo = configRepo,
            scheduler = mockk<IBackupScheduler>(relaxed = true),
            authorizer = mockk(relaxed = true),
            encryptionRepo = encryptionRepo,
            cipher = mockk<IFvc1Cipher>(relaxed = true),
            settingsRepo = settingsRepo,
            releaseSafPermissionIfUnused = mockk(relaxed = true),
            existingConfigId = config.id,
        )
        return vm to configRepo
    }

    "editing an encrypted config locks the password fields" {
        val (vm, _) = makeEditVm(encryptedConfig)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.form.value.encryptionSettingsLocked shouldBe true
        vm.form.value.encryptionEnabled shouldBe true
    }

    "toggling encryption off is ignored on a locked config" {
        val (vm, _) = makeEditVm(encryptedConfig)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setEncryptionEnabled(false)

        vm.form.value.encryptionEnabled shouldBe true
    }

    "saving a locked config keeps the original password blob + salt and never re-encrypts" {
        val encryptionRepo = mockk<IEncryptionRepository>(relaxed = true)
        val (vm, configRepo) = makeEditVm(encryptedConfig, encryptionRepo)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        val saved = slot<BackupConfig>()
        coVerify { configRepo.save(capture(saved)) }
        saved.captured.encryptedPasswordBlob shouldBe "OLD_BLOB"
        saved.captured.encryptionSaltBase64 shouldBe "OLD_SALT"
        coVerify(exactly = 0) { encryptionRepo.encryptPassword(any()) }
    }
})
