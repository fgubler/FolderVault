package ch.abwesend.folderVault.view

import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupMessageRepository
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.view.viewmodel.BackupDetailViewModel
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

    fun makeConfig(id: String, isPaused: Boolean) = BackupConfig(
        id = id,
        displayName = "Test",
        sourceTreeUri = "",
        cloudProvider = "google_drive",
        cloudRootFolderId = "fid",
        cloudRootFolderName = "FolderVault_test",
        cloudAccountIdentifier = "user@test.com",
        schedule = BackupSchedule.DAILY,
        changedFilePolicy = ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP,
        encryptionEnabled = false,
        encryptedPasswordBlob = null,
        encryptionSaltBase64 = null,
        retentionPolicy = RetentionPolicy.KeepAll,
        networkPolicy = NetworkPolicy.WIFI_ONLY,
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

    "backUpNow does not call scheduler when config is paused" {
        val configId = "cfg-1"
        val pausedConfig = makeConfig(configId, isPaused = true)

        val configRepo = mockk<IBackupConfigRepository> {
            every { getById(configId) } returns flowOf(pausedConfig)
        }
        val messageRepo = mockk<IBackupMessageRepository> {
            every { getUndismissed(configId) } returns flowOf(emptyList())
            every { getUnreadCountBySeverity(configId, any()) } returns flowOf(0)
        }
        val scheduler = mockk<IBackupScheduler>()
        val encryptionRepo = mockk<IEncryptionRepository>()
        val settingsRepo = mockk<IAppSettingsRepository>()

        val vm = BackupDetailViewModel(configId, configRepo, messageRepo, scheduler, encryptionRepo, settingsRepo)

        // Subscribe to config to trigger WhileSubscribed and populate config.value
        val job = vm.config.launchIn(kotlinx.coroutines.CoroutineScope(testDispatcher))

        vm.backUpNow()

        verify(exactly = 0) { scheduler.scheduleOneTime(any()) }
        job.cancel()
    }

    "backUpNow calls scheduler when config is not paused" {
        val configId = "cfg-2"
        val activeConfig = makeConfig(configId, isPaused = false)

        val configRepo = mockk<IBackupConfigRepository> {
            every { getById(configId) } returns flowOf(activeConfig)
        }
        val messageRepo = mockk<IBackupMessageRepository> {
            every { getUndismissed(configId) } returns flowOf(emptyList())
            every { getUnreadCountBySeverity(configId, any()) } returns flowOf(0)
        }
        val scheduler = mockk<IBackupScheduler>(relaxed = true)
        val encryptionRepo = mockk<IEncryptionRepository>()
        val settingsRepo = mockk<IAppSettingsRepository>()

        val vm = BackupDetailViewModel(configId, configRepo, messageRepo, scheduler, encryptionRepo, settingsRepo)

        val job = vm.config.launchIn(kotlinx.coroutines.CoroutineScope(testDispatcher))

        vm.backUpNow()

        verify(exactly = 1) { scheduler.scheduleOneTime(configId) }
        job.cancel()
    }

    "backUpNow calls scheduler when config is not yet loaded (null)" {
        val configId = "cfg-3"

        val configRepo = mockk<IBackupConfigRepository> {
            every { getById(configId) } returns MutableStateFlow(null)
        }
        val messageRepo = mockk<IBackupMessageRepository> {
            every { getUndismissed(configId) } returns flowOf(emptyList())
            every { getUnreadCountBySeverity(configId, any()) } returns flowOf(0)
        }
        val scheduler = mockk<IBackupScheduler>(relaxed = true)
        val encryptionRepo = mockk<IEncryptionRepository>()
        val settingsRepo = mockk<IAppSettingsRepository>()

        val vm = BackupDetailViewModel(configId, configRepo, messageRepo, scheduler, encryptionRepo, settingsRepo)

        vm.backUpNow()

        verify(exactly = 1) { scheduler.scheduleOneTime(configId) }
    }
})
