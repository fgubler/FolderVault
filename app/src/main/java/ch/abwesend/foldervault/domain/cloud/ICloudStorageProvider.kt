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

    suspend fun uploadFile(
        parentId: String,
        remoteName: String,
        mimeType: String,
        content: UploadContent,
    ): BinaryResult<CloudFile, Exception>

    suspend fun readRootMetadata(rootFolderId: String, name: String): BinaryResult<ByteArray?, Exception>
    suspend fun writeRootMetadata(rootFolderId: String, name: String, bytes: ByteArray): BinaryResult<Unit, Exception>

    suspend fun deleteFile(fileId: String): BinaryResult<Unit, Exception>
}
