package ch.abwesend.foldervault.domain.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupMeta(
    val version: Int = 1,
    val marker: String = "FolderVaultBackup",
    val displayName: String,
    val createdAt: String,
    val encrypted: Boolean,
) {
    companion object {
        const val CLOUD_FILE_NAME = ".foldervault-meta.json"
    }
}
