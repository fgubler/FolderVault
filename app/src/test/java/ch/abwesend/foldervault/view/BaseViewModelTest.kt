package ch.abwesend.foldervault.view

import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.logging.ILogger
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.view.viewmodel.BaseViewModel
import ch.abwesend.foldervault.view.viewmodel.UiText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class BaseViewModelTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val testDispatcher = UnconfinedTestDispatcher()
    lateinit var loggerMock: ILogger

    beforeTest {
        Dispatchers.setMain(testDispatcher)
        loggerMock = mockk(relaxed = true)
        LoggerProvider.configure { loggerMock }
    }
    afterTest { Dispatchers.resetMain() }

    class TestViewModel : BaseViewModel() {
        fun runSafeLaunch(block: suspend CoroutineScope.() -> Unit) = safeLaunch(block = block)
        fun runSafeLaunchWith(messageRes: Int, block: suspend CoroutineScope.() -> Unit) =
            safeLaunch(errorMessageRes = messageRes, block = block)
    }

    "safeLaunch surfaces an exception via unexpectedError" {
        val vm = TestViewModel()

        vm.runSafeLaunch { error("boom") }
        testDispatcher.scheduler.advanceUntilIdle()

        val surfaced = vm.unexpectedError.value
        surfaced.shouldBeInstanceOf<UiText.Resource>()
        surfaced.id shouldBe R.string.error_unexpected
    }

    "safeLaunch logs the throwable via logger.error" {
        val vm = TestViewModel()
        val cause = IllegalStateException("boom")

        vm.runSafeLaunch { throw cause }
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) { loggerMock.error(any(), cause) }
    }

    "safeLaunch uses the custom errorMessageRes when provided" {
        val vm = TestViewModel()

        vm.runSafeLaunchWith(R.string.error_auth_failed) { error("nope") }
        testDispatcher.scheduler.advanceUntilIdle()

        val surfaced = vm.unexpectedError.value
        surfaced.shouldBeInstanceOf<UiText.Resource>()
        surfaced.id shouldBe R.string.error_auth_failed
    }

    "safeLaunch does NOT swallow CancellationException as a generic error" {
        val vm = TestViewModel()

        // CancellationException must NOT be routed through unexpectedError / logger.error —
        // it propagates as normal coroutine cancellation. The job ends up cancelled, not failed.
        val job = vm.runSafeLaunch { throw CancellationException("cancel me") }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.unexpectedError.value shouldBe null
        verify(exactly = 0) { loggerMock.error(any(), any<Throwable>()) }
        job.isCancelled shouldBe true
    }

    "dismissUnexpectedError clears the surfaced error" {
        val vm = TestViewModel()
        vm.runSafeLaunch { error("boom") }
        testDispatcher.scheduler.advanceUntilIdle()
        vm.unexpectedError.value shouldNotBe null

        vm.dismissUnexpectedError()

        vm.unexpectedError.value shouldBe null
    }

    "successful safeLaunch does not surface an error" {
        val vm = TestViewModel()

        vm.runSafeLaunch { /* no-op */ }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.unexpectedError.value shouldBe null
        verify(exactly = 0) { loggerMock.error(any(), any<Throwable>()) }
    }
})
