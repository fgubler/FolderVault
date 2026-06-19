package ch.abwesend.foldervault.infrastructure.cloud.googledrive

import ch.abwesend.foldervault.domain.cloud.CloudEntry
import ch.abwesend.foldervault.domain.cloud.CloudFile
import ch.abwesend.foldervault.domain.cloud.CloudFolder
import ch.abwesend.foldervault.domain.cloud.CloudTransientException
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.cloud.UploadContent
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
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

        // Drive q-syntax requires `\` and `'` in string literals to be backslash-escaped.
        internal fun escapeDriveQueryLiteral(value: String): String =
            value.replace("\\", "\\\\").replace("'", "\\'")
    }

    // Classify Drive API exceptions into typed CloudException subclasses.
    private fun <T> driveCall(block: () -> T): T {
        return try {
            block()
        } catch (e: GoogleJsonResponseException) {
            throw DriveErrorClassifier.classify(e)
        } catch (e: IOException) {
            throw CloudTransientException(cause = e)
        }
    }

    // Classify + apply exponential-backoff retry for transient / rate-limit errors.
    private suspend fun <T> retryingDriveCall(block: () -> T): T =
        DriveRetryPolicy.withRetry { driveCall(block) }

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
                retryingDriveCall {
                    val name = "${ROOT_FOLDER_NAME_PREFIX}_${UUID.randomUUID()}"
                    val metadata = DriveFile().apply {
                        this.name = name
                        mimeType = FOLDER_MIME_TYPE
                    }
                    val folder = drive.files().create(metadata).setFields("id, name").execute()
                    logger.info("Created Drive root folder: ${folder.name} (${folder.id})")
                    CloudFolder(id = folder.id, name = folder.name)
                }
            }
        }

    override suspend fun hasFolderAccess(folderId: String): BinaryResult<Boolean, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                retryingDriveCall {
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
                retryingDriveCall {
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
                        logger.info("Created Drive child folder: ${created.name} (${created.id})")
                        CloudFolder(id = created.id, name = created.name)
                    }
                }
            }
        }

    override suspend fun listChildren(folderId: String): BinaryResult<List<CloudEntry>, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                retryingDriveCall {
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
                                if (file.mimeType == FOLDER_MIME_TYPE) add(CloudFolder(id, name))
                                else add(CloudFile(id, name))
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
    ): BinaryResult<CloudFile, Exception> = withContext(dispatchers.io) {
        runCatchingAsResult {
            retryingDriveCall {
                val metadata = DriveFile().apply {
                    name = remoteName
                    parents = listOf(parentId)
                }
                val streamContent = InputStreamContent(mimeType, content.inputStreamProvider())
                content.length?.let { streamContent.length = it }
                val uploaded = drive.files().create(metadata, streamContent)
                    .setFields("id, name").execute()
                logger.info("Uploaded file to Drive: ${uploaded.name} (${uploaded.id})")
                CloudFile(
                    id = uploaded.id ?: error("Drive did not return file id"),
                    name = uploaded.name ?: remoteName,
                )
            }
        }
    }

    override suspend fun readRootMetadata(
        rootFolderId: String,
        name: String,
    ): BinaryResult<ByteArray?, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                retryingDriveCall {
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
                retryingDriveCall {
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
                retryingDriveCall {
                    drive.files().delete(fileId).execute()
                    logger.info("Deleted Drive file: $fileId")
                }
            }
        }
}
