package ch.abwesend.foldervault.infrastructure.backup

import androidx.work.ListenableWorker.Result
import ch.abwesend.foldervault.domain.logging.logger
import kotlinx.coroutines.CancellationException

class WorkerErrorHandler {
    companion object {
        const val MAX_RETRY_COUNT = 20
    }

    /**
     * Returns [Result.retry] while the worker still has attempts left, [Result.failure] once
     * [runAttemptCount] (see [androidx.work.ListenableWorker.getRunAttemptCount]) reaches
     * [MAX_RETRY_COUNT]. Retryable conditions (auth lost, concurrent run in flight) are expected
     * to resolve within a few attempts — a cap keeps a condition that never resolves (e.g. a hung
     * in-flight run holding the per-config lock) from retrying forever with growing backoff.
     */
    fun retryOrGiveUp(runAttemptCount: Int): Result =
        if (runAttemptCount >= MAX_RETRY_COUNT) {
            logger.warning("Giving up after $runAttemptCount attempts")
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
