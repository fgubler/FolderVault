package ch.abwesend.foldervault.infrastructure.backup

import androidx.work.ListenableWorker.Result
import ch.abwesend.foldervault.domain.logging.logger

class WorkerErrorHandler {
    companion object {
        const val MAX_RETRY_COUNT = 20
    }

    suspend fun doWorkWithErrorHandling(
        workDescription: String,
        onFatalError: suspend (String) -> Unit = {},
        block: suspend () -> Result,
    ): Result = try {
        block()
    } catch (e: Exception) {
        logger.error("$workDescription failed with unexpected exception", e)
        onFatalError("$workDescription failed: ${e.message}")
        Result.failure()
    }
}
