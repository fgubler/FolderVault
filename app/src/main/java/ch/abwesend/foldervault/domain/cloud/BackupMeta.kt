package ch.abwesend.foldervault.domain.cloud

import kotlinx.serialization.Serializable

/**
 * Plaintext identity sidecar written once to the cloud root as [CLOUD_FILE_NAME].
 * Contains ONLY backup-level identity — never crypto params (those live in every FVC1 file header).
 * See spec §6.1 for the single-source-of-truth principle.
 */
@Serializable
data class BackupMeta(
    val version: Int = 1,
    val marker: String = APP_MARKER,
    val displayName: String,
    val createdAt: Long,
    val encrypted: Boolean,
) {
    companion object {
        const val APP_MARKER = "FolderVaultBackup"
        const val CLOUD_FILE_NAME = ".foldervault-meta.json"
    }
}
