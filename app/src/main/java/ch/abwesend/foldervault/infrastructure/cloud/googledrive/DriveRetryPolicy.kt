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

    /**
     * Retries [block] on transient / rate-limit errors with exponential backoff + jitter.
     *
     * [verifyAlreadySucceeded] is invoked at the start of every retry attempt (never the first).
     * If it returns a non-null value, that value is returned immediately and [block] is not run
     * again. Use this to make non-idempotent operations (Drive's `files.create`) safe to retry:
     * if the first attempt actually succeeded server-side but the response was lost client-side,
     * the verify hook detects the artifact and reuses it instead of creating a duplicate.
     *
     * Exceptions thrown by [verifyAlreadySucceeded] are swallowed (CancellationException aside) —
     * a transient failure of the verify probe must not derail the retry of the real operation.
     */
    suspend fun <T> withRetry(
        verifyAlreadySucceeded: (suspend () -> T?)? = null,
        block: suspend () -> T,
    ): T {
        var attempt = 0
        var lastException: Exception? = null
        while (attempt <= MAX_RETRIES) {
            lastException = try {
                if (attempt > 0 && verifyAlreadySucceeded != null) {
                    val existing = runVerifySafely(verifyAlreadySucceeded)
                    if (existing != null) return existing
                }
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudTransientException) {
                e
            } catch (e: CloudRateLimitException) {
                e
            }
            attempt++
            if (attempt <= MAX_RETRIES) {
                val backoff = minOf(BASE_DELAY_MS * (1L shl attempt), MAX_DELAY_MS)
                delay(backoff + Random.nextLong(JITTER_MAX_MS))
            }
        }
        throw lastException!!
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> runVerifySafely(probe: suspend () -> T?): T? = try {
        probe()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
