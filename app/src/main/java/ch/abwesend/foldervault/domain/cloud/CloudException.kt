package ch.abwesend.foldervault.domain.cloud

sealed class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause)

class CloudAuthException(cause: Throwable? = null) :
    CloudException("Cloud auth failed or token expired", cause)

class CloudRateLimitException(cause: Throwable? = null) :
    CloudException("Cloud API rate limit exceeded", cause)

class CloudQuotaExceededException(cause: Throwable? = null) :
    CloudException("Cloud storage quota exceeded", cause)

class CloudTransientException(message: String = "Transient cloud error", cause: Throwable? = null) :
    CloudException(message, cause)

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
