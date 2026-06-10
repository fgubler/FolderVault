package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType

data class BackupMessage(
    val id: Long,
    val backupConfigId: String?,
    val runId: String?,
    val timestamp: Long,
    val severity: MessageSeverity,
    val type: MessageType,
    val messageText: String?,
    val formatArgs: List<String>,
    val relativePath: String?,
    val count: Int,
    val readAt: Long?,
    val dismissed: Boolean,
)
