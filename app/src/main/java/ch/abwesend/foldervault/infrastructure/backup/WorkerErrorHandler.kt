package ch.abwesend.foldervault.infrastructure.backup

import androidx.work.ListenableWorker.Result
import ch.abwesend.foldervault.domain.logging.logger
import kotlinx.coroutines.CancellationException

class WorkerErrorHandler {
    companion object {
        const val MAX_RETRY_COUNT = 20

        // Small probability (1%) of giving up early even when under MAX to prevent indefinite retry storms.
        private const val EARLY_GIVE_UP_PROBABILITY = 0.01
    }

    private var retryCounter = 0

    suspend fun doWorkWithErrorHandling(
        workDescription: String,
        onFatalError: suspend (String) -> Unit = {},
        block: suspend () -> Result,
    ): Result = try {
        block().also { retryCounter = 0 }
    } catch (e: CancellationException) {
        logger.debug("$workDescription cancelled: ${e.message}")
        retryCounter++
        if (retryCounter < MAX_RETRY_COUNT && Math.random() > EARLY_GIVE_UP_PROBABILITY) {
            logger.warning("$workDescription cancelled in attempt $retryCounter: retrying")
            Result.retry()
        } else {
            logger.warning("$workDescription failed due to cancellation after $retryCounter attempts")
            retryCounter = 0
            Result.failure()
        }
    } catch (e: Exception) {
        retryCounter = 0
        logger.error("$workDescription failed with unexpected exception", e)
        onFatalError("$workDescription failed: ${e.message}")
        Result.failure()
    }
}
