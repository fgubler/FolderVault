package ch.abwesend.foldervault.domain.storage

import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ReleaseSafPermissionIfUnusedUseCaseTest : StringSpec({

    fun config(id: String, sourceTreeUri: String) = BackupConfig(
        id = id,
        displayName = "Test",
        sourceTreeUri = sourceTreeUri,
        cloudProvider = "google_drive",
        cloudSubFolderId = "fid",
        cloudSubFolderName = "test_sub",
        cloudAccountIdentifier = "user@test.com",
        schedule = BackupSchedule.DAILY,
        changedFilePolicy = ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP,
        encryptionEnabled = false,
        encryptedPasswordBlob = null,
        encryptionSaltBase64 = null,
        retentionPolicy = RetentionPolicy.KeepAll,
        networkPolicy = NetworkPolicy.WIFI_ONLY,
        requiresCharging = false,
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
        isPaused = false,
    )

    /** Config repo fake whose only relevant behaviour is the current snapshot from [getAll]. */
    class FakeConfigRepo(private val configs: List<BackupConfig>) : IBackupConfigRepository {
        override fun getAll(): Flow<List<BackupConfig>> = flowOf(configs)
        override fun getById(id: String): Flow<BackupConfig?> = throw NotImplementedError()
        override suspend fun save(config: BackupConfig) = throw NotImplementedError()
        override suspend fun deleteById(id: String) = throw NotImplementedError()
        override suspend fun setPaused(id: String, paused: Boolean) = throw NotImplementedError()
    }

    /** Records the URIs it was asked to release so the test can assert exactly what happened. */
    class RecordingSafPermissionManager : ISafPermissionManager {
        val released = mutableListOf<String>()
        override fun releasePersistedPermission(treeUri: String) {
            released += treeUri
        }
    }

    "releases the grant when no remaining config uses the URI" {
        val uri = "content://tree/deleted"
        val safManager = RecordingSafPermissionManager()
        // The deleted config is excluded; only an unrelated config remains.
        val repo = FakeConfigRepo(listOf(config("other", "content://tree/other")))
        val useCase = ReleaseSafPermissionIfUnusedUseCase(repo, safManager)

        useCase(uri, excludingConfigId = "deleted")

        safManager.released shouldContainExactly listOf(uri)
    }

    "keeps the grant when another config still uses the same URI" {
        val uri = "content://tree/shared"
        val safManager = RecordingSafPermissionManager()
        val repo = FakeConfigRepo(listOf(config("survivor", uri)))
        val useCase = ReleaseSafPermissionIfUnusedUseCase(repo, safManager)

        useCase(uri, excludingConfigId = "deleted")

        safManager.released shouldContainExactly emptyList()
    }

    "excludes the config itself so its own stale reference does not block release" {
        val uri = "content://tree/self"
        val safManager = RecordingSafPermissionManager()
        // The config is still present in the snapshot (delete not yet propagated) but must be
        // excluded — otherwise its own reference would wrongly keep the grant alive.
        val repo = FakeConfigRepo(listOf(config("deleted", uri)))
        val useCase = ReleaseSafPermissionIfUnusedUseCase(repo, safManager)

        useCase(uri, excludingConfigId = "deleted")

        safManager.released shouldContainExactly listOf(uri)
    }

    "does nothing for a blank URI" {
        val safManager = RecordingSafPermissionManager()
        val repo = FakeConfigRepo(emptyList())
        val useCase = ReleaseSafPermissionIfUnusedUseCase(repo, safManager)

        useCase("", excludingConfigId = null)

        safManager.released shouldContainExactly emptyList()
    }
})
