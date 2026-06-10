package ch.abwesend.foldervault.infrastructure.backup

import android.net.Uri

data class UploadTask(
    val relativePath: String,
    val documentUri: Uri,
    val localSize: Long,
    val localMtime: Long,
    val mode: UploadMode,
    val tier: UploadTier,
    val previousCloudFileId: String? = null,
)

enum class UploadMode { NEW, CHANGED_DUPLICATE, CHANGED_OVERWRITE }
enum class UploadTier { NORMAL, OVERSIZED }
