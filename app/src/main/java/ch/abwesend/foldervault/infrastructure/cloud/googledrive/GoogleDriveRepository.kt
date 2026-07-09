package ch.abwesend.foldervault.infrastructure.cloud.googledrive

import ch.abwesend.foldervault.domain.cloud.CloudEntry
import ch.abwesend.foldervault.domain.cloud.CloudFile
import ch.abwesend.foldervault.domain.cloud.CloudFolder
import ch.abwesend.foldervault.domain.cloud.CloudTransientException
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.cloud.UploadContent
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.logging.FileNameRedactor
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.runCatchingAsResult
import ch.abwesend.foldervault.domain.util.injectAnywhere
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.InputStreamContent
import com.google.api.services.drive.Drive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID
import com.google.api.services.drive.model.File as DriveFile

class GoogleDriveRepository(private val drive: Drive) : ICloudStorageProvider {
    private val dispatchers: IDispatchers by injectAnywhere()

    companion object {
        private const val ROOT_FOLDER_NAME_PREFIX = "FolderVault"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

        /** Drive q-syntax requires `\` and `'` in string literals to be backslash-escaped. */
        internal fun escapeDriveQueryLiteral(value: String): String =
            value.replace("\\", "\\\\").replace("'", "\\'")
    }

    /** Classifies Drive API exceptions into typed [CloudTransientException] / [DriveErrorClassifier] subclasses. */
    private fun <T> driveCall(block: () -> T): T = try {
        block()
    } catch (e: GoogleJsonResponseException) {
        throw DriveErrorClassifier.classify(e)
    } catch (e: IOException) {
        throw CloudTransientException(cause = e)
    }

    /** Classifies and applies exponential-backoff retry for transient / rate-limit errors. */
    private suspend fun <T> retryingDriveCall(label: String = "Drive call", block: () -> T): T =
        DriveRetryPolicy.withRetry(label = label) { driveCall(block) }

    override suspend fun getAccountIdentifier(): BinaryResult<String, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                driveCall {
                    drive.about().get().setFields("user").execute().user.emailAddress
                }
            }
        }

    override suspend fun createRootFolder(): BinaryResult<CloudFolder, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                // Generate the name ONCE, outside the retry block: if a retry happens we want
                // the verify probe to look for the same name the first attempt used. Generating
                // a fresh UUID per attempt would orphan the server-side folder created by the
                // (apparently failed but actually successful) prior attempt.
                val name = "${ROOT_FOLDER_NAME_PREFIX}_${UUID.randomUUID()}"
                DriveRetryPolicy.withRetry(
                    label = "createRootFolder",
                    verifyAlreadySucceeded = { findRootFolderByGeneratedName(name) },
                ) {
                    driveCall {
                        val metadata = DriveFile().apply {
                            this.name = name
                            mimeType = FOLDER_MIME_TYPE
                        }
                        val folder = drive.files().create(metadata).setFields("id, name").execute()
                        val safeName = FileNameRedactor.redact(folder.name.orEmpty())
                        logger.info("Created Drive root folder: $safeName (${folder.id})")
                        CloudFolder(id = folder.id, name = folder.name)
                    }
                }
            }
        }

    /** Idempotency probe for [createRootFolder] — finds a folder created by a prior retry attempt. */
    private fun findRootFolderByGeneratedName(name: String): CloudFolder? {
        val escapedName = escapeDriveQueryLiteral(name)
        val query = "name = '$escapedName' and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
        val match = drive.files().list()
            .setQ(query)
            .setFields("files(id, name)")
            .setSpaces("drive")
            .execute()
            .files
            .orEmpty()
            .firstOrNull() ?: return null
        return CloudFolder(id = match.id, name = match.name ?: name)
    }

    override suspend fun hasFolderAccess(folderId: String): BinaryResult<Boolean, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                retryingDriveCall("hasFolderAccess") {
                    drive.files().get(folderId)
                        .setFields("id, trashed, capabilities")
                        .execute()
                        ?.let { it.trashed != true && it.capabilities?.canEdit == true } ?: false
                }
            }
        }

    override suspend fun getOrCreateChildFolder(
        parentId: String,
        name: String,
    ): BinaryResult<CloudFolder, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                retryingDriveCall("getOrCreateChildFolder($name)") {
                    val escapedParentId = escapeDriveQueryLiteral(parentId)
                    val escapedName = escapeDriveQueryLiteral(name)
                    val query = "'$escapedParentId' in parents and name = '$escapedName' " +
                        "and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
                    val allMatches = buildList {
                        var pageToken: String? = null
                        do {
                            val page = drive.files().list()
                                .setQ(query)
                                .setFields("nextPageToken, files(id, name, createdTime)")
                                .setSpaces("drive")
                                .apply { if (pageToken != null) this.pageToken = pageToken }
                                .execute()
                            addAll(page.files.orEmpty())
                            pageToken = page.nextPageToken
                        } while (pageToken != null)
                    }

                    // Drive can accumulate duplicates from interrupted runs.
                    // Pick deterministic winner: oldest by createdTime, tie-broken by smallest id.
                    val winner = allMatches
                        .sortedWith(compareBy({ it.createdTime?.value ?: Long.MAX_VALUE }, { it.id }))
                        .firstOrNull()

                    if (winner != null) {
                        CloudFolder(id = winner.id, name = winner.name)
                    } else {
                        val metadata = DriveFile().apply {
                            this.name = name
                            mimeType = FOLDER_MIME_TYPE
                            parents = listOf(parentId)
                        }
                        val created = drive.files().create(metadata).setFields("id, name").execute()
                        val safeName = FileNameRedactor.redact(created.name.orEmpty())
                        logger.info("Created Drive child folder: $safeName (${created.id})")
                        CloudFolder(id = created.id, name = created.name)
                    }
                }
            }
        }

    override suspend fun listChildren(folderId: String): BinaryResult<List<CloudEntry>, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                retryingDriveCall("listChildren") {
                    val escapedFolderId = escapeDriveQueryLiteral(folderId)
                    val query = "'$escapedFolderId' in parents and trashed = false"
                    buildList {
                        var pageToken: String? = null
                        do {
                            val result = drive.files().list()
                                .setQ(query)
                                .setFields("nextPageToken, files(id, name, mimeType)")
                                .setSpaces("drive")
                                .apply { if (pageToken != null) this.pageToken = pageToken }
                                .execute()
                            result.files.orEmpty().forEach { file ->
                                val id = file.id ?: return@forEach
                                val name = file.name ?: return@forEach
                                if (file.mimeType == FOLDER_MIME_TYPE) {
                                    add(CloudFolder(id, name))
                                } else {
                                    add(CloudFile(id, name))
                                }
                            }
                            pageToken = result.nextPageToken
                        } while (pageToken != null)
                    }
                }
            }
        }

    override suspend fun uploadFile(
        parentId: String,
        remoteName: String,
        mimeType: String,
        content: UploadContent,
        excludeIds: Set<String>,
    ): BinaryResult<CloudFile, Exception> = withContext(dispatchers.io) {
        runCatchingAsResult {
            DriveRetryPolicy.withRetry(
                label = "uploadFile($remoteName)",
                verifyAlreadySucceeded = { findUploadedFileByName(parentId, remoteName, excludeIds) },
            ) {
                driveCall {
                    val metadata = DriveFile().apply {
                        name = remoteName
                        parents = listOf(parentId)
                    }
                    val streamContent = InputStreamContent(mimeType, content.inputStreamProvider())
                    content.length?.let { streamContent.length = it }
                    val uploaded = drive.files().create(metadata, streamContent)
                        .setFields("id, name").execute()
                    val safeName = FileNameRedactor.redact(uploaded.name.orEmpty())
                    logger.info("Uploaded file to Drive: $safeName (${uploaded.id})")
                    CloudFile(
                        id = uploaded.id ?: error("Drive did not return file id"),
                        name = uploaded.name ?: remoteName,
                    )
                }
            }
        }
    }

    /**
     * Idempotency probe for [uploadFile]: returns a non-null [CloudFile] when a non-folder child
     * of [parentId] already exists with name [remoteName] (after excluding [excludeIds]).
     * Used to reuse a server-side artifact when a transient error fired AFTER Drive committed
     * the first upload — otherwise the retry would create a duplicate.
     */
    private fun findUploadedFileByName(
        parentId: String,
        remoteName: String,
        excludeIds: Set<String>,
    ): CloudFile? {
        val escapedParentId = escapeDriveQueryLiteral(parentId)
        val escapedName = escapeDriveQueryLiteral(remoteName)
        val query = "'$escapedParentId' in parents and name = '$escapedName' " +
            "and mimeType != '$FOLDER_MIME_TYPE' and trashed = false"
        val candidates = drive.files().list()
            .setQ(query)
            .setFields("files(id, name, createdTime)")
            .setSpaces("drive")
            .execute()
            .files
            .orEmpty()
            .filter { it.id !in excludeIds }
        val winner = candidates.maxByOrNull { it.createdTime?.value ?: 0L } ?: return null
        if (candidates.size > 1) {
            val safeName = FileNameRedactor.redact(remoteName)
            logger.warning(
                "uploadFile retry found ${candidates.size} candidate files for '$safeName' in " +
                    "parent $parentId — picking newest (${winner.id}); older entries left in place."
            )
        }
        return CloudFile(id = winner.id, name = winner.name ?: remoteName)
    }

    override suspend fun readRootMetadata(
        rootFolderId: String,
        name: String,
    ): BinaryResult<ByteArray?, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                retryingDriveCall("readRootMetadata($name)") {
                    val escapedRootFolderId = escapeDriveQueryLiteral(rootFolderId)
                    val escapedName = escapeDriveQueryLiteral(name)
                    val query = "'$escapedRootFolderId' in parents and name = '$escapedName' and trashed = false"
                    val file = drive.files().list()
                        .setQ(query).setFields("files(id)").setSpaces("drive")
                        .execute().files.orEmpty().firstOrNull()
                        ?: return@retryingDriveCall null
                    drive.files().get(file.id).executeMediaAsInputStream().use { it.readBytes() }
                }
            }
        }

    override suspend fun writeRootMetadata(
        rootFolderId: String,
        name: String,
        bytes: ByteArray,
    ): BinaryResult<Unit, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                retryingDriveCall("writeRootMetadata($name)") {
                    val escapedRootFolderId = escapeDriveQueryLiteral(rootFolderId)
                    val escapedName = escapeDriveQueryLiteral(name)
                    val query = "'$escapedRootFolderId' in parents and name = '$escapedName' and trashed = false"
                    val existing = drive.files().list()
                        .setQ(query).setFields("files(id)").setSpaces("drive")
                        .execute().files.orEmpty().firstOrNull()
                    val streamContent = InputStreamContent("application/json", ByteArrayInputStream(bytes))
                    streamContent.length = bytes.size.toLong()
                    if (existing != null) {
                        drive.files().update(existing.id, DriveFile(), streamContent).execute()
                    } else {
                        val metadata = DriveFile().apply {
                            this.name = name
                            parents = listOf(rootFolderId)
                        }
                        drive.files().create(metadata, streamContent).execute()
                    }
                    Unit
                }
            }
        }

    override suspend fun deleteFile(fileId: String): BinaryResult<Unit, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                retryingDriveCall("deleteFile") {
                    drive.files().delete(fileId).execute()
                    logger.info("Deleted Drive file: $fileId")
                }
            }
        }
}
