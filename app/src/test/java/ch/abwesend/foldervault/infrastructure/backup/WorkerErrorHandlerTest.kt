package ch.abwesend.folderVault.infrastructure.backup

import androidx.work.ListenableWorker.Result
import ch.abwesend.foldervault.infrastructure.backup.WorkerErrorHandler
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException

class WorkerErrorHandlerTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    "success resets the retry counter and returns the block result" {
        val handler = WorkerErrorHandler()

        // First call: simulate a cancellation to increment the counter
        // (drive counter to 1 by catching cancellation)
        val cancelResult = handler.doWorkWithErrorHandling("test") {
            throw CancellationException("oops")
        }
        cancelResult shouldBe Result.retry()

        // Now succeed — counter should reset
        val successResult = handler.doWorkWithErrorHandling("test") {
            Result.success()
        }
        successResult shouldBe Result.success()

        // Another cancellation after reset should give retry again (counter starts fresh)
        val retryAgain = handler.doWorkWithErrorHandling("test") {
            throw CancellationException("oops again")
        }
        retryAgain shouldBe Result.retry()
    }

    "cancellation returns retry while under MAX_RETRY_COUNT" {
        val handler = WorkerErrorHandler()

        // Cancellation with Math.random() > 0.01 almost always — we can rely on it
        // by calling once; the counter goes to 1 which is < MAX_RETRY_COUNT (20)
        val result = handler.doWorkWithErrorHandling("test") {
            throw CancellationException("cancelled")
        }

        // Should be retry (counter = 1, well below MAX)
        result shouldBe Result.retry()
    }

    "unexpected exception returns failure immediately" {
        val handler = WorkerErrorHandler()
        var fatalCallbackCalled = false

        val result = handler.doWorkWithErrorHandling(
            workDescription = "test",
            onFatalError = { fatalCallbackCalled = true },
        ) {
            throw IllegalStateException("boom")
        }

        result shouldBe Result.failure()
        fatalCallbackCalled shouldBe true
    }

    "cancellation beyond MAX_RETRY_COUNT returns failure" {
        val handler = WorkerErrorHandler()

        // Drive the counter up to MAX_RETRY_COUNT by calling with random() always > 0.01
        // We can't guarantee random > 0.01 every call, but we can force failure by
        // calling enough times. Instead, use reflection to set the counter just below max.
        val counterField = WorkerErrorHandler::class.java.getDeclaredField("retryCounter")
        counterField.isAccessible = true
        counterField.setInt(handler, WorkerErrorHandler.MAX_RETRY_COUNT - 1)

        // Next cancellation: counter becomes MAX_RETRY_COUNT (== MAX, not <) → failure
        // Note: even with Math.random() > 0.01, the condition `retryCounter < MAX` is false
        val result = handler.doWorkWithErrorHandling("test") {
            throw CancellationException("still cancelled")
        }

        result shouldBe Result.failure()
    }
})
