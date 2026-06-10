package ch.abwesend.foldervault.infrastructure.cloud.googledrive

import ch.abwesend.foldervault.domain.cloud.CloudRateLimitException
import ch.abwesend.foldervault.domain.cloud.CloudTransientException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random

internal object DriveRetryPolicy {
    const val MAX_RETRIES = 3
    private const val BASE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 32_000L
    private const val JITTER_MAX_MS = 1_000L

    suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        var lastException: Exception? = null
        while (attempt <= MAX_RETRIES) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudTransientException) {
                lastException = e
            } catch (e: CloudRateLimitException) {
                lastException = e
            }
            attempt++
            if (attempt <= MAX_RETRIES) {
                val backoff = minOf(BASE_DELAY_MS * (1L shl attempt), MAX_DELAY_MS)
                delay(backoff + Random.nextLong(JITTER_MAX_MS))
            }
        }
        throw lastException!!
    }
}
