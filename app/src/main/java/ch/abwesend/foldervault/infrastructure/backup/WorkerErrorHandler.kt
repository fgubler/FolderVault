package ch.abwesend.foldervault.infrastructure.backup

import androidx.work.ListenableWorker.Result
import ch.abwesend.foldervault.domain.logging.logger
import kotlinx.coroutines.CancellationException

class WorkerErrorHandler {
    companion object {
        /**
         * Cap for conditions that resolve on their own given time — chiefly a concurrent run of the
         * same config holding the per-config lock. Retrying rides WorkManager's exponential backoff,
         * so a generous cap tolerates a long in-flight backup chain while still bounding a hung run.
         */
        const val MAX_RETRY_COUNT = 20

        /**
         * Cap for auth loss. Kept much lower than [MAX_RETRY_COUNT] because a lost/expired auth
         * grant needs user interaction to fix — a notification is already posted, so hammering the
         * auth + network stack with 20 backed-off retries is pointless. Five attempts (~15 min of
         * backoff) absorbs a transient token-refresh blip without wasting work or churning the API.
         */
        const val MAX_AUTH_RETRY_COUNT = 5
    }

    /**
     * Returns [Result.retry] while the worker still has attempts left, [Result.failure] once
     * [runAttemptCount] (see [androidx.work.ListenableWorker.getRunAttemptCount]) reaches
     * [maxRetryCount]. Retryable conditions (auth lost, concurrent run in flight) are expected to
     * resolve within a few attempts — a cap keeps a condition that never resolves (e.g. a hung
     * in-flight run holding the per-config lock, or auth that only the user can restore) from
     * retrying forever with growing backoff. Callers pass the cap that fits the condition:
     * [MAX_AUTH_RETRY_COUNT] for auth loss, [MAX_RETRY_COUNT] otherwise.
     */
    fun retryOrGiveUp(runAttemptCount: Int, maxRetryCount: Int = MAX_RETRY_COUNT): Result =
        if (runAttemptCount >= maxRetryCount) {
            logger.warning("Giving up after $runAttemptCount attempts (cap $maxRetryCount)")
            Result.failure()
        } else {
            Result.retry()
        }

    suspend fun doWorkWithErrorHandling(
        workDescription: String,
        onFatalError: suspend (String) -> Unit = {},
        block: suspend () -> Result,
    ): Result = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("$workDescription failed with unexpected exception", e)
        onFatalError("$workDescription failed: ${e.message}")
        Result.failure()
    }
}
