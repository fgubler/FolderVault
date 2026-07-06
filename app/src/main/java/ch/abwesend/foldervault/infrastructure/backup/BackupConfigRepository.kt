package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.EncryptionParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BackupConfigRepository(private val dao: BackupConfigDao) : IBackupConfigRepository {

    companion object {
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val KDF_ITERATIONS = 310_000
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }

    override fun getAll(): Flow<List<BackupConfig>> = dao.getAll().map { list ->
        list.map { it.toDomain() }
    }

    override fun getById(id: String): Flow<BackupConfig?> =
        dao.getById(id).map { it?.toDomain() }

    override suspend fun save(config: BackupConfig) {
        dao.upsert(config.toEntity())
    }

    override suspend fun deleteById(id: String) {
        dao.deleteById(id)
    }

    override suspend fun setPaused(id: String, paused: Boolean) {
        dao.updatePaused(id, paused)
    }

    private fun BackupConfigEntity.toDomain() = BackupConfig(
        id = id,
        displayName = displayName,
        sourceTreeUri = sourceTreeUri,
        cloudProvider = cloudProvider,
        cloudSubFolderId = cloudSubFolderId,
        cloudSubFolderName = cloudSubFolderName,
        cloudAccountIdentifier = cloudAccountIdentifier,
        schedule = schedule,
        changedFilePolicy = changedFilePolicy,
        encryptionEnabled = encryptionEnabled,
        encryptedPasswordBlob = encryptedPasswordBlob,
        encryptionSaltBase64 = encryptionParams?.salt,
        retentionPolicy = retentionPolicy,
        networkPolicy = networkPolicy,
        requiresCharging = requiresCharging,
        createdAt = createdAt,
        lastRunAt = lastRunAt,
        lastRunStatus = lastRunStatus,
        filesUploaded = filesUploaded,
        filesSkipped = filesSkipped,
        filesFailed = filesFailed,
        bytesUploaded = bytesUploaded,
        totalFilesDiscovered = totalFilesDiscovered,
        filesUploadedTotal = filesUploadedTotal,
        lastRunCompletedNormally = lastRunCompletedNormally,
        isPaused = isPaused,
    )

    private fun BackupConfig.toEntity() = BackupConfigEntity(
        id = id,
        displayName = displayName,
        sourceTreeUri = sourceTreeUri,
        cloudProvider = cloudProvider,
        cloudSubFolderId = cloudSubFolderId,
        cloudSubFolderName = cloudSubFolderName,
        cloudAccountIdentifier = cloudAccountIdentifier,
        schedule = schedule,
        changedFilePolicy = changedFilePolicy,
        encryptionEnabled = encryptionEnabled,
        encryptedPasswordBlob = encryptedPasswordBlob,
        encryptionParams = encryptionSaltBase64?.let {
            EncryptionParams(
                kdfAlgorithm = KDF_ALGORITHM,
                kdfIterations = KDF_ITERATIONS,
                salt = it,
                cipherTransformation = CIPHER_TRANSFORMATION,
                gcmTagBits = GCM_TAG_BITS,
            )
        },
        retentionPolicy = retentionPolicy,
        networkPolicy = networkPolicy,
        requiresCharging = requiresCharging,
        createdAt = createdAt,
        lastRunAt = lastRunAt,
        lastRunStatus = lastRunStatus,
        filesUploaded = filesUploaded,
        filesSkipped = filesSkipped,
        filesFailed = filesFailed,
        bytesUploaded = bytesUploaded,
        totalFilesDiscovered = totalFilesDiscovered,
        filesUploadedTotal = filesUploadedTotal,
        lastRunCompletedNormally = lastRunCompletedNormally,
        isPaused = isPaused,
    )
}
