package ch.abwesend.foldervault.view

import android.app.PendingIntent
import android.content.Intent
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupMessageRepository
import ch.abwesend.foldervault.domain.cloud.CloudAuthResult
import ch.abwesend.foldervault.domain.cloud.CloudNotFoundException
import ch.abwesend.foldervault.domain.cloud.CloudTransientException
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.storage.ReleaseSafPermissionIfUnusedUseCase
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Tests for the delete branch of the detail ViewModel — local-only delete, the optional
 * Drive-folder delete (happy path, already-gone folder, failure→acknowledge, consent flow), and
 * the delete-while-running guard. The rest of the ViewModel is covered by
 * [BackupDetailViewModelTest]; shared fixtures live in `BackupDetailViewModelTestFixtures.kt`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupDetailViewModelDeleteTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val testDispatcher = UnconfinedTestDispatcher()

    beforeTest { Dispatchers.setMain(testDispatcher) }
    afterTest { Dispatchers.resetMain() }

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

    "deleteBackup releases the SAF permission after deleting the config (BUG-12)" {
        val configId = "cfg-del-saf"
        val treeUri = "content://tree/docs"
        val config = makeConfig(configId, isPaused = false).copy(sourceTreeUri = treeUri)
        val configRepo = mockk<IBackupConfigRepository>(relaxed = true) {
            every { getById(configId) } returns flowOf(config)
        }
        val releaseSaf = mockk<ReleaseSafPermissionIfUnusedUseCase>(relaxed = true)
        val (vm, _) = buildVm(
            configId,
            config,
            configRepo = configRepo,
            releaseSaf = releaseSaf,
        )
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.deleteBackup(alsoDeleteCloudFolder = false)

        // BUG-12: the config must be removed before its tree URI is handed back, and it is
        // additionally excluded from the in-use check so its own (now stale) URI can't keep the
        // persisted SAF grant alive.
        coVerifyOrder {
            configRepo.deleteById(configId)
            releaseSaf.invoke(treeUri, excludingConfigId = configId)
        }
        job.cancel()
    }

    "deleteBackup is refused while a backup is running" {
        val configId = "cfg-del-running"
        val (authorizer, provider) = cloudDeps()
        val configRepo = mockk<IBackupConfigRepository>(relaxed = true) {
            every { getById(configId) } returns flowOf(makeConfig(configId, isPaused = false))
        }
        val (vm, _) = buildVm(
            configId,
            makeConfig(configId, isPaused = false),
            isRunning = true,
            configRepo = configRepo,
            authorizer = authorizer,
        )
        val events = mutableListOf<DetailEvent>()
        val eventsJob = vm.events.onEach { events.add(it) }.launchIn(CoroutineScope(testDispatcher))
        // Deliberately NO collector on vm.isRunning: the guard must read the scheduler's live
        // state directly, not the stateIn cache (which stays at its initial `false` without a
        // subscriber — review F-14).
        val job = vm.config.launchIn(CoroutineScope(testDispatcher))

        vm.deleteBackup(alsoDeleteCloudFolder = false)
        vm.deleteBackup(alsoDeleteCloudFolder = true)

        // Neither the local record nor the Drive folder may be touched while a run is in flight.
        coVerify(exactly = 0) { configRepo.deleteById(any()) }
        coVerify(exactly = 0) { authorizer.authorize(any()) }
        coVerify(exactly = 0) { provider.deleteFile(any()) }
        vm.cloudDeleteState.value shouldBe CloudDeleteState.Idle
        // Each refusal is surfaced to the user instead of failing silently (review F-15).
        events shouldBe listOf(
            DetailEvent.DeleteRefusedWhileRunning,
            DetailEvent.DeleteRefusedWhileRunning,
        )
        eventsJob.cancel()
        job.cancel()
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
