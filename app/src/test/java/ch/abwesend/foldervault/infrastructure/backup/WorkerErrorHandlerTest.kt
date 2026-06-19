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
})
