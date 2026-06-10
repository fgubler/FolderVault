package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.MessageSeverity
import kotlinx.coroutines.flow.Flow

interface IBackupMessageRepository {
    fun getUndismissed(backupConfigId: String): Flow<List<BackupMessage>>
    fun getUnreadCountBySeverity(backupConfigId: String, severities: List<MessageSeverity>): Flow<Int>
    suspend fun markRead(ids: List<Long>)
    suspend fun dismiss(ids: List<Long>)
    suspend fun dismissAllForConfig(backupConfigId: String)
    suspend fun deleteAllForConfig(backupConfigId: String)
}
