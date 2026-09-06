package ch.abwesend.foldervault.domain.cloud

sealed class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause)

class CloudAuthException(cause: Throwable? = null) :
    CloudException("Cloud auth failed or token expired", cause)

class CloudRateLimitException(cause: Throwable? = null) :
    CloudException("Cloud API rate limit exceeded", cause)

class CloudQuotaExceededException(cause: Throwable? = null) :
    CloudException("Cloud storage quota exceeded", cause)

open class CloudTransientException(message: String = "Transient cloud error", cause: Throwable? = null) :
    CloudException(message, cause)

/**
 * A transient failure caused by the device having no usable network connection at all — most
 * commonly a DNS-resolution failure (`UnknownHostException`) when a scheduled backup fires before
 * connectivity is actually up (e.g. the phone just woke in the morning). A specialisation of
 * [CloudTransientException] so the existing retry machinery still applies, but distinguishable so
 * the worker can ride WorkManager's backoff and defer the user-facing "upload failed" notification
 * until connectivity genuinely does not return.
 */
class CloudNetworkUnavailableException(cause: Throwable? = null) :
    CloudTransientException("No usable network connection", cause)

class CloudNotFoundException(message: String = "Cloud resource not found", cause: Throwable? = null) :
    CloudException(message, cause)

/**
 * A permanent, non-retryable cloud failure (e.g. HTTP 400 `badRequest`, or another 4xx that is
 * neither auth, not-found, rate-limit nor quota). Unlike [CloudTransientException] it is NOT caught
 * by `DriveRetryPolicy`, so it fails fast instead of burning three backoff-spaced retries on a
 * request that can never succeed as-is (SEC-5).
 */
class CloudPermanentException(message: String = "Permanent cloud error", cause: Throwable? = null) :
    CloudException(message, cause)
