package ch.abwesend.foldervault.infrastructure.cloud.googledrive

import ch.abwesend.foldervault.domain.cloud.CloudAuthException
import ch.abwesend.foldervault.domain.cloud.CloudException
import ch.abwesend.foldervault.domain.cloud.CloudNotFoundException
import ch.abwesend.foldervault.domain.cloud.CloudPermanentException
import ch.abwesend.foldervault.domain.cloud.CloudQuotaExceededException
import ch.abwesend.foldervault.domain.cloud.CloudRateLimitException
import ch.abwesend.foldervault.domain.cloud.CloudTransientException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import java.io.IOException

internal object DriveErrorClassifier {

    fun classify(e: Throwable): CloudException {
        if (e is CloudException) return e
        if (e is GoogleJsonResponseException) {
            val reason = e.details?.errors?.firstOrNull()?.reason.orEmpty()
            return classifyByCodeAndReason(e.statusCode, reason, e)
        }
        if (e is IOException) return CloudTransientException(cause = e)
        return CloudTransientException(cause = e)
    }

    internal fun classifyByCodeAndReason(statusCode: Int, reason: String, cause: Throwable): CloudException =
        when {
            statusCode == HTTP_UNAUTHORIZED -> CloudAuthException(cause)
            statusCode == HTTP_NOT_FOUND -> CloudNotFoundException(cause = cause)
            statusCode == HTTP_TOO_MANY_REQUESTS ||
                reason.contains("rateLimitExceeded", ignoreCase = true) ||
                reason.contains("userRateLimitExceeded", ignoreCase = true) -> CloudRateLimitException(cause)
            reason.contains("storageQuotaExceeded", ignoreCase = true) -> CloudQuotaExceededException(cause)
            // 403 permission problems (missing scope, app not authorized, config-level forbidden) are
            // permanent and user-actionable, not transient — surface them as auth-lost so the run stops
            // and the user is prompted, instead of retrying a request that can never succeed (SEC-5).
            statusCode == HTTP_FORBIDDEN && isPermissionReason(reason) -> CloudAuthException(cause)
            statusCode in HTTP_SERVER_ERROR_RANGE -> CloudTransientException(cause = cause)
            // Any other 4xx (e.g. 400 badRequest, or a 403 with an unrecognised reason) is permanent:
            // retrying will not help, so fail fast rather than burning the backoff budget (SEC-5).
            statusCode in HTTP_CLIENT_ERROR_RANGE -> CloudPermanentException(cause = cause)
            else -> CloudTransientException(cause = cause)
        }

    private fun isPermissionReason(reason: String): Boolean =
        FORBIDDEN_PERMISSION_REASONS.any { it.equals(reason, ignoreCase = true) }

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private val HTTP_CLIENT_ERROR_RANGE = 400..499
    private val HTTP_SERVER_ERROR_RANGE = 500..599

    /** Drive 403 `reason` codes that mean "permanently not allowed", handled like a lost authorization. */
    private val FORBIDDEN_PERMISSION_REASONS = setOf(
        "insufficientPermissions",
        "appNotAuthorizedToFile",
        "forbidden",
        "accessNotConfigured",
    )
}
