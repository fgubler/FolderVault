package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.backup.BackupRun
import ch.abwesend.foldervault.domain.backup.IBackupRunRepository
import ch.abwesend.foldervault.infrastructure.room.dao.BackupRunDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupRunEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BackupRunRepository(private val dao: BackupRunDao) : IBackupRunRepository {

    override fun observeByConfig(backupConfigId: String, limit: Int): Flow<List<BackupRun>> =
        dao.observeByConfig(backupConfigId, limit).map { list -> list.map { it.toDomain() } }

    private fun BackupRunEntity.toDomain() = BackupRun(
        id = id,
        backupConfigId = backupConfigId,
        runId = runId,
        startedAt = startedAt,
        completedAt = completedAt,
        status = status,
        filesUploaded = filesUploaded,
        filesSkipped = filesSkipped,
        filesFailed = filesFailed,
        bytesUploaded = bytesUploaded,
    )
}
