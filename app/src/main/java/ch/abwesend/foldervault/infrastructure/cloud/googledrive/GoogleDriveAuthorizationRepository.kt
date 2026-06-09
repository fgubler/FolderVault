package ch.abwesend.foldervault.infrastructure.cloud.googledrive

import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import ch.abwesend.foldervault.domain.cloud.CloudAuthResult
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.ifError
import ch.abwesend.foldervault.domain.result.runCatchingAsResult
import ch.abwesend.foldervault.domain.util.injectAnywhere
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class GoogleDriveAuthorizationRepository(private val context: Context) : ICloudAuthorizer {
    private val dispatchers: IDispatchers by injectAnywhere()

    companion object {
        private const val APP_NAME = "FolderVault"
    }

    override suspend fun authorize(): CloudAuthResult<ICloudStorageProvider> =
        withContext(dispatchers.io) {
            try {
                val result = requestAuthorization()
                if (result.hasResolution()) {
                    result.pendingIntent?.let { CloudAuthResult.ConsentRequired(it) } ?: CloudAuthResult.Error
                } else {
                    CloudAuthResult.Authorized(data = result.buildStorageProvider())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to request Drive authorization", e)
                CloudAuthResult.Error
            }
        }

    override suspend fun authorizeFromIntent(data: Intent?): BinaryResult<ICloudStorageProvider, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                Identity.getAuthorizationClient(context)
                    .getAuthorizationResultFromIntent(data)
                    .buildStorageProvider()
            }.ifError { logger.error("Failed to handle Drive authorization result", it) }
        }

    override suspend fun clearAuthorization(): BinaryResult<Unit, Exception> =
        withContext(dispatchers.io) {
            runCatchingAsResult {
                CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
                logger.debug("Drive authorization cleared")
            }.ifError { logger.error("Failed to clear Drive authorization", it) }
        }

    private suspend fun requestAuthorization(): AuthorizationResult = withContext(dispatchers.io) {
        val scopes = listOf(Scope(DriveScopes.DRIVE_FILE), Scope("email"))
        val request = AuthorizationRequest.builder().setRequestedScopes(scopes).build()
        Tasks.await(Identity.getAuthorizationClient(context).authorize(request))
    }

    private fun AuthorizationResult.buildStorageProvider(): ICloudStorageProvider {
        val token = accessToken ?: error("Authorization succeeded but no access token returned")
        val initializer = HttpRequestInitializer { req -> req.headers.authorization = "Bearer $token" }
        val drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer)
            .setApplicationName(APP_NAME).build()
        return GoogleDriveRepository(drive)
    }
}
