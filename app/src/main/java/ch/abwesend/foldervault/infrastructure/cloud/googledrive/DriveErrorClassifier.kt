package ch.abwesend.foldervault.infrastructure.cloud.googledrive

import ch.abwesend.foldervault.domain.cloud.CloudAuthException
import ch.abwesend.foldervault.domain.cloud.CloudException
import ch.abwesend.foldervault.domain.cloud.CloudNetworkUnavailableException
import ch.abwesend.foldervault.domain.cloud.CloudNotFoundException
import ch.abwesend.foldervault.domain.cloud.CloudPermanentException
import ch.abwesend.foldervault.domain.cloud.CloudQuotaExceededException
import ch.abwesend.foldervault.domain.cloud.CloudRateLimitException
import ch.abwesend.foldervault.domain.cloud.CloudTransientException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.UnknownHostException

internal object DriveErrorClassifier {

    fun classify(e: Throwable): CloudException {
        if (e is CloudException) return e
        if (e is GoogleJsonResponseException) {
            val reason = e.details?.errors?.firstOrNull()?.reason.orEmpty()
            return classifyByCodeAndReason(e.statusCode, reason, e)
        }
        if (e is IOException) return classifyIoException(e)
        return CloudTransientException(cause = e)
    }

    /**
     * A no-connectivity [IOException] (DNS resolution failed, or the socket layer could not reach
     * any network) maps to [CloudNetworkUnavailableException] so the worker can ride WorkManager's
     * backoff and defer the failure notification; any other [IOException] stays a generic transient
     * error.
     */
    internal fun classifyIoException(e: IOException): CloudTransientException =
        if (isConnectivityError(e)) CloudNetworkUnavailableException(cause = e) else CloudTransientException(cause = e)

    /**
     * Whether [throwable] (or any of its causes) indicates the device had no usable network *at
     * all*, as opposed to a connection that existed and then failed.
     *
     * Deliberately matches the specific no-route/no-DNS types rather than their common base class
     * [SocketException]: a bare `SocketException` is also what the platform throws for a
     * *mid-transfer* failure on a perfectly healthy network ("Connection reset by peer", "Broken
     * pipe", "Software caused connection abort"), which is an everyday event on mobile. Treating
     * those as "no network" would be expensive: [CloudNetworkUnavailableException] aborts the
     * *entire* backup run (the uploader drains its remaining queue untouched) and defers the
     * user-facing failure notification for the whole retry cap — where the right handling is to
     * count the one file as failed and carry on with the rest. Such errors therefore stay generic
     * transient errors, which the retry policy already absorbs.
     *
     * [java.net.SocketTimeoutException] is likewise absent on purpose: a timeout means the network
     * was there and too slow, not missing.
     */
    internal fun isConnectivityError(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            val noNetwork = current is UnknownHostException || // DNS could not be reached at all
                current is ConnectException || // "Network is unreachable" / connection refused
                current is NoRouteToHostException ||
                current is PortUnreachableException
            if (noNetwork) return true
            current = current.cause
        }
        return false
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
