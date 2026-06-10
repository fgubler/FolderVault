package ch.abwesend.foldervault.domain.backup

import kotlinx.coroutines.flow.Flow

interface IBackupConfigRepository {
    fun getAll(): Flow<List<BackupConfig>>
    fun getById(id: String): Flow<BackupConfig?>
    suspend fun save(config: BackupConfig)
    suspend fun deleteById(id: String)
    suspend fun setPaused(id: String, paused: Boolean)
}
