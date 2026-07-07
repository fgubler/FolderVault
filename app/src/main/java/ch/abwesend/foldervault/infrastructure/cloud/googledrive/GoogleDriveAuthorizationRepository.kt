package ch.abwesend.foldervault.infrastructure.cloud.googledrive

import android.accounts.Account
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

    override suspend fun authorize(accountName: String?): CloudAuthResult<ICloudStorageProvider> =
        withContext(dispatchers.io) {
            try {
                val result = requestAuthorization(accountName)
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

    private suspend fun requestAuthorization(accountName: String?): AuthorizationResult =
        withContext(dispatchers.io) {
            val request = buildAuthorizationRequest(accountName)
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

/**
 * Builds the Drive authorization request, targeting [accountName] when given so the grant is
 * resolved for exactly that Google account instead of whichever one the platform picks.
 * Top-level (instead of private in the repository) to be testable without a GMS client.
 */
internal fun buildAuthorizationRequest(accountName: String?): AuthorizationRequest {
    val scopes = listOf(Scope(DriveScopes.DRIVE_FILE), Scope("email"))
    val builder = AuthorizationRequest.builder().setRequestedScopes(scopes)
    accountName?.let { builder.setAccount(Account(it, GOOGLE_ACCOUNT_TYPE)) }
    return builder.build()
}

internal const val GOOGLE_ACCOUNT_TYPE = "com.google"
