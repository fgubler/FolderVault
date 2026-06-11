package ch.abwesend.foldervault.domain.restore

data class RestoreProgress(
    val total: Int,
    val processed: Int,
    val failed: Int,
    val currentFileName: String,
)
