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
