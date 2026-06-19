package ch.abwesend.foldervault.domain.cloud

import ch.abwesend.foldervault.domain.result.BinaryResult

interface ICloudStorageProvider {
    suspend fun getAccountIdentifier(): BinaryResult<String, Exception>

    suspend fun createRootFolder(): BinaryResult<CloudFolder, Exception>

    suspend fun hasFolderAccess(folderId: String): BinaryResult<Boolean, Exception>

    suspend fun getOrCreateChildFolder(parentId: String, name: String): BinaryResult<CloudFolder, Exception>

    /**
     * Lists immediate children. Must page on nextPageToken (Drive returns ≤100 by default).
     * In v1 used only for retention-driven cleanup; "browse existing folder" is deferred to v1.1.
     */
    suspend fun listChildren(folderId: String): BinaryResult<List<CloudEntry>, Exception>

    /**
     * Uploads a file to [parentId] as [remoteName].
     *
     * [excludeIds] is the set of cloud file IDs that already exist in the parent under the same
     * [remoteName] and must be ignored when checking for "did a prior attempt succeed?" on retry.
     * For CHANGED_OVERWRITE this is `setOf(previousCloudFileId)` so the prior version isn't
     * mistaken for the just-uploaded duplicate. For first-time uploads pass `emptySet()`.
     */
    suspend fun uploadFile(
        parentId: String,
        remoteName: String,
        mimeType: String,
        content: UploadContent,
        excludeIds: Set<String> = emptySet(),
    ): BinaryResult<CloudFile, Exception>

    suspend fun readRootMetadata(rootFolderId: String, name: String): BinaryResult<ByteArray?, Exception>
    suspend fun writeRootMetadata(rootFolderId: String, name: String, bytes: ByteArray): BinaryResult<Unit, Exception>

    suspend fun deleteFile(fileId: String): BinaryResult<Unit, Exception>
}
