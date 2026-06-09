package ch.abwesend.foldervault.domain.cloud

import java.io.InputStream

data class UploadContent(
    val inputStreamProvider: () -> InputStream,
    val length: Long? = null,
)
