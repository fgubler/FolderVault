package ch.abwesend.foldervault.domain.backup

import kotlinx.serialization.Serializable

@Serializable
data class CloudManifest(
    val version: Int = 1,
    val generatedAt: String,
    val files: List<ManifestEntry>,
) {
    companion object {
        const val CLOUD_FILE_NAME = ".foldervault-manifest.json"
    }
}

@Serializable
data class ManifestEntry(
    val relativePath: String,
    val mtime: Long,
    val size: Long,
    val cloudFileId: String,
    val remoteName: String,
)
