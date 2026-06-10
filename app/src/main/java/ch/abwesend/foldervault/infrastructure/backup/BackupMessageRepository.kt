package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.backup.BackupMessage
import ch.abwesend.foldervault.domain.backup.IBackupMessageRepository
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BackupMessageRepository(private val dao: BackupMessageDao) : IBackupMessageRepository {

    override fun getUndismissed(backupConfigId: String): Flow<List<BackupMessage>> =
        dao.getUndismissed(backupConfigId).map { list -> list.map { it.toDomain() } }

    override fun getUnreadCountBySeverity(
        backupConfigId: String,
        severities: List<MessageSeverity>,
    ): Flow<Int> = dao.getUnreadCountBySeverity(backupConfigId, severities)

    override suspend fun markRead(ids: List<Long>) {
        dao.markRead(ids, System.currentTimeMillis())
    }

    override suspend fun dismiss(ids: List<Long>) {
        dao.dismiss(ids)
    }

    override suspend fun dismissAllForConfig(backupConfigId: String) {
        dao.dismissAllForConfig(backupConfigId)
    }

    override suspend fun deleteAllForConfig(backupConfigId: String) {
        dao.deleteAllForConfig(backupConfigId)
    }

    private fun BackupMessageEntity.toDomain() = BackupMessage(
        id = id,
        backupConfigId = backupConfigId,
        runId = runId,
        timestamp = timestamp,
        severity = severity,
        type = type,
        messageText = messageText,
        formatArgs = formatArgs,
        relativePath = relativePath,
        count = count,
        readAt = readAt,
        dismissed = dismissed,
    )
}
