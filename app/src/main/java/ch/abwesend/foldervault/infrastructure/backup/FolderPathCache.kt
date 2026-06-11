package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult

/**
 * Within a single run, caches relativeFolderPath → cloudFolderId to avoid redundant
 * get-or-create calls when many files share the same remote subfolder.
 */
class FolderPathCache(private val provider: ICloudStorageProvider) {
    private val cache = mutableMapOf<String, String>()

    suspend fun ensurePath(rootFolderId: String, relativeFolderPath: String): BinaryResult<String, Exception> {
        if (relativeFolderPath.isEmpty()) return SuccessResult(rootFolderId)
        cache[relativeFolderPath]?.let { return SuccessResult(it) }

        val segments = relativeFolderPath.split('/')
        var currentId = rootFolderId
        val builder = StringBuilder()

        for (segment in segments) {
            if (builder.isNotEmpty()) builder.append('/')
            builder.append(segment)
            val key = builder.toString()
            val cached = cache[key]
            if (cached != null) {
                currentId = cached
            } else {
                val result = provider.getOrCreateChildFolder(currentId, segment)
                if (result is ErrorResult) return result
                currentId = (result as SuccessResult).value.id
                cache[key] = currentId
            }
        }
        return SuccessResult(currentId)
    }
}
