package ch.abwesend.foldervault.infrastructure.backup

import androidx.work.ListenableWorker.Result
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException

class WorkerErrorHandlerTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    "success returns the block result" {
        val handler = WorkerErrorHandler()

        val result = handler.doWorkWithErrorHandling("test") {
            Result.success()
        }

        result shouldBe Result.success()
    }

    "CancellationException is rethrown and not swallowed" {
        val handler = WorkerErrorHandler()

        var rethrown = false
        @Suppress("SwallowedException")
        try {
            handler.doWorkWithErrorHandling("test") {
                throw CancellationException("cancelled by coroutine")
            }
        } catch (e: CancellationException) {
            rethrown = true
        }

        rethrown shouldBe true
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

    "retryOrGiveUp retries while attempts remain" {
        val handler = WorkerErrorHandler()

        handler.retryOrGiveUp(runAttemptCount = 0) shouldBe Result.retry()
        handler.retryOrGiveUp(runAttemptCount = WorkerErrorHandler.MAX_RETRY_COUNT - 1) shouldBe Result.retry()
    }

    "retryOrGiveUp fails once the retry cap is reached" {
        val handler = WorkerErrorHandler()

        handler.retryOrGiveUp(runAttemptCount = WorkerErrorHandler.MAX_RETRY_COUNT) shouldBe Result.failure()
        handler.retryOrGiveUp(runAttemptCount = WorkerErrorHandler.MAX_RETRY_COUNT + 1) shouldBe Result.failure()
    }

    "retryOrGiveUp honors a lower auth cap for auth loss" {
        val handler = WorkerErrorHandler()
        val authCap = WorkerErrorHandler.MAX_AUTH_RETRY_COUNT

        handler.retryOrGiveUp(runAttemptCount = authCap - 1, maxRetryCount = authCap) shouldBe Result.retry()
        handler.retryOrGiveUp(runAttemptCount = authCap, maxRetryCount = authCap) shouldBe Result.failure()
    }

    "the auth retry cap is lower than the general cap" {
        (WorkerErrorHandler.MAX_AUTH_RETRY_COUNT < WorkerErrorHandler.MAX_RETRY_COUNT) shouldBe true
    }
})
