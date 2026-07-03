package ch.abwesend.foldervault.view

import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.logging.ILogExporter
import ch.abwesend.foldervault.domain.logging.ILogger
import ch.abwesend.foldervault.domain.logging.ITelemetryToggle
import ch.abwesend.foldervault.domain.logging.LoggerProvider
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.domain.system.BackgroundRestrictionStatus
import ch.abwesend.foldervault.domain.system.IBackgroundRestrictionChecker
import ch.abwesend.foldervault.view.viewmodel.SettingsViewModel
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val testDispatcher = UnconfinedTestDispatcher()

    beforeTest {
        Dispatchers.setMain(testDispatcher)
        LoggerProvider.configure { mockk<ILogger>(relaxed = true) }
    }
    afterTest { Dispatchers.resetMain() }

    class FakeTelemetryToggle : ITelemetryToggle {
        var lastEnabled: Boolean? = null
        var callCount: Int = 0
        override fun setEnabled(enabled: Boolean) {
            lastEnabled = enabled
            callCount++
        }
    }

    fun makeSettings() = MutableStateFlow(AppSettings())
    fun makeRepo(settings: MutableStateFlow<AppSettings>): IAppSettingsRepository = mockk {
        every { this@mockk.settings } returns settings
        coEvery { update(any()) } coAnswers {
            settings.value = firstArg<(AppSettings) -> AppSettings>()(settings.value)
        }
    }
    val dispatchers = object : IDispatchers {
        override val default = testDispatcher
        override val io = testDispatcher
        override val main = testDispatcher
        override val mainImmediate = testDispatcher
    }
    val logFiles = mockk<ILogExporter>(relaxed = true)

    fun makeRestrictionChecker(
        ignoringBatteryOptimizations: Boolean = false,
        backgroundDataRestricted: Boolean = false,
    ): IBackgroundRestrictionChecker = mockk {
        every { isIgnoringBatteryOptimizations() } returns ignoringBatteryOptimizations
        every { isBackgroundDataRestricted() } returns backgroundDataRestricted
    }

    "setAnonymousErrorReports(true) calls telemetry toggle with true before persisting" {
        val toggle = FakeTelemetryToggle()
        val settings = makeSettings()
        val vm = SettingsViewModel(makeRepo(settings), toggle, logFiles, makeRestrictionChecker(), dispatchers)

        vm.setAnonymousErrorReports(true)
        testDispatcher.scheduler.advanceUntilIdle()

        toggle.callCount shouldBe 1
        toggle.lastEnabled shouldBe true
        settings.value.anonymousErrorReports shouldBe true
    }

    "setAnonymousErrorReports(false) calls telemetry toggle with false" {
        val toggle = FakeTelemetryToggle()
        val settings = makeSettings()
        val vm = SettingsViewModel(makeRepo(settings), toggle, logFiles, makeRestrictionChecker(), dispatchers)

        vm.setAnonymousErrorReports(false)
        testDispatcher.scheduler.advanceUntilIdle()

        toggle.lastEnabled shouldBe false
        settings.value.anonymousErrorReports shouldBe false
    }

    "backgroundRestrictions starts with the all-clear default before the first refresh" {
        val checker = makeRestrictionChecker(ignoringBatteryOptimizations = true, backgroundDataRestricted = true)
        val vm = SettingsViewModel(makeRepo(makeSettings()), FakeTelemetryToggle(), logFiles, checker, dispatchers)

        vm.backgroundRestrictions.value shouldBe BackgroundRestrictionStatus()
    }

    "refreshBackgroundRestrictions exposes the current checker state" {
        val checker = makeRestrictionChecker(ignoringBatteryOptimizations = true, backgroundDataRestricted = true)
        val vm = SettingsViewModel(makeRepo(makeSettings()), FakeTelemetryToggle(), logFiles, checker, dispatchers)

        vm.refreshBackgroundRestrictions()

        vm.backgroundRestrictions.value shouldBe BackgroundRestrictionStatus(
            ignoringBatteryOptimizations = true,
            backgroundDataRestricted = true,
        )
    }

    "refreshBackgroundRestrictions picks up changes made in the system settings" {
        val checker = mockk<IBackgroundRestrictionChecker> {
            every { isIgnoringBatteryOptimizations() } returnsMany listOf(false, true)
            every { isBackgroundDataRestricted() } returns false
        }
        val vm = SettingsViewModel(makeRepo(makeSettings()), FakeTelemetryToggle(), logFiles, checker, dispatchers)

        vm.refreshBackgroundRestrictions()
        vm.backgroundRestrictions.value.ignoringBatteryOptimizations shouldBe false

        vm.refreshBackgroundRestrictions()
        vm.backgroundRestrictions.value.ignoringBatteryOptimizations shouldBe true
    }
})
