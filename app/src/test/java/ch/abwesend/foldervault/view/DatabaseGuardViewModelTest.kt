package ch.abwesend.foldervault.view

import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.database.IDatabaseRecoveryService
import ch.abwesend.foldervault.domain.logging.ILogExporter
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.view.viewmodel.DatabaseGuardState
import ch.abwesend.foldervault.view.viewmodel.DatabaseGuardViewModel
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Uses hand-written fakes instead of MockK: the recovery service is a pure domain interface,
 * and a fake keeps the test runnable as a plain JVM test in restricted (sandboxed) environments
 * where MockK's runtime agent cannot attach.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseGuardViewModelTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val testDispatcher = UnconfinedTestDispatcher()

    beforeTest { Dispatchers.setMain(testDispatcher) }
    afterTest { Dispatchers.resetMain() }

    val dispatchers = object : IDispatchers {
        override val default = testDispatcher
        override val io = testDispatcher
        override val main = testDispatcher
        override val mainImmediate = testDispatcher
    }
    fun makeLogExporter(result: Boolean = true) = object : ILogExporter {
        override fun exportTodayLog(destinationUri: String): Boolean = result
    }

    fun makeViewModel(
        service: IDatabaseRecoveryService,
        logExporter: ILogExporter = makeLogExporter(),
    ) = DatabaseGuardViewModel(service, logExporter, dispatchers)

    "state becomes Healthy when the database opens" {
        val vm = makeViewModel(FakeDatabaseRecoveryService(verifyResults = listOf(SuccessResult(Unit))))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.state.value shouldBe DatabaseGuardState.Healthy
    }

    "state becomes Error when the database cannot be opened" {
        val vm = makeViewModel(
            FakeDatabaseRecoveryService(verifyResults = listOf(ErrorResult(IllegalStateException("boom")))),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.state.value shouldBe DatabaseGuardState.Error
    }

    "verifyDatabase re-check recovers to Healthy after an initial Error" {
        val service = FakeDatabaseRecoveryService(
            verifyResults = listOf(
                ErrorResult(IllegalStateException("boom")),
                SuccessResult(Unit),
            ),
        )
        val vm = makeViewModel(service)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.state.value shouldBe DatabaseGuardState.Error

        vm.verifyDatabase()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.state.value shouldBe DatabaseGuardState.Healthy
    }

    "successful resetDatabase leads to Healthy" {
        val service = FakeDatabaseRecoveryService(
            verifyResults = listOf(ErrorResult(IllegalStateException("boom"))),
            resetResult = SuccessResult(Unit),
        )
        val vm = makeViewModel(service)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.resetDatabase()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.state.value shouldBe DatabaseGuardState.Healthy
        vm.userMessage.value.shouldBeNull()
    }

    "failed resetDatabase stays on Error and informs the user" {
        val service = FakeDatabaseRecoveryService(
            verifyResults = listOf(ErrorResult(IllegalStateException("boom"))),
            resetResult = ErrorResult(IllegalStateException("still broken")),
        )
        val vm = makeViewModel(service)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.resetDatabase()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.state.value shouldBe DatabaseGuardState.Error
        vm.userMessage.value.shouldNotBeNull()
    }

    "successful log export exposes a success result" {
        val vm = makeViewModel(FakeDatabaseRecoveryService(), makeLogExporter(result = true))

        vm.exportTodayLogFile("content://logs/today.log")
        testDispatcher.scheduler.advanceUntilIdle()

        vm.exportResult.value shouldBe true
    }

    "failed log export exposes a failure result" {
        val vm = makeViewModel(FakeDatabaseRecoveryService(), makeLogExporter(result = false))

        vm.exportTodayLogFile("content://logs/today.log")
        testDispatcher.scheduler.advanceUntilIdle()

        vm.exportResult.value shouldBe false
    }

    "dismissExportResult clears the export result" {
        val vm = makeViewModel(FakeDatabaseRecoveryService(), makeLogExporter(result = true))
        vm.exportTodayLogFile("content://logs/today.log")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.exportResult.value.shouldNotBeNull()

        vm.dismissExportResult()

        vm.exportResult.value.shouldBeNull()
    }

    "dismissUserMessage clears the user message" {
        val service = FakeDatabaseRecoveryService(
            verifyResults = listOf(ErrorResult(IllegalStateException("boom"))),
            resetResult = ErrorResult(IllegalStateException("still broken")),
        )
        val vm = makeViewModel(service)
        vm.resetDatabase()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.userMessage.value.shouldNotBeNull()

        vm.dismissUserMessage()

        vm.userMessage.value.shouldBeNull()
    }
})

/**
 * Scripted stand-in for the recovery service: [verifyDatabaseHealth] plays back
 * [verifyResults] in order (repeating the last entry once exhausted), [resetDatabase]
 * always answers [resetResult].
 */
private class FakeDatabaseRecoveryService(
    verifyResults: List<BinaryResult<Unit, Exception>> = listOf(SuccessResult(Unit)),
    private val resetResult: BinaryResult<Unit, Exception> = SuccessResult(Unit),
) : IDatabaseRecoveryService {

    private val verifyQueue = verifyResults.toMutableList()

    override suspend fun verifyDatabaseHealth(): BinaryResult<Unit, Exception> =
        if (verifyQueue.size > 1) verifyQueue.removeAt(0) else verifyQueue.first()

    override suspend fun resetDatabase(): BinaryResult<Unit, Exception> = resetResult
}
