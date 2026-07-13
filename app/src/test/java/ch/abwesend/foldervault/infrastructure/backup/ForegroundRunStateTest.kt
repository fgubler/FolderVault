package ch.abwesend.foldervault.infrastructure.backup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Pins down the in-memory running-set semantics of [ForegroundRunState]: marking is per config,
 * stopping is idempotent, and the observed flow reflects the current membership.
 */
class ForegroundRunStateTest : StringSpec({

    "a config is not running initially" {
        val state = ForegroundRunState()
        state.isRunning("cfg-1") shouldBe false
    }

    "markRunning makes only that config running" {
        val state = ForegroundRunState()
        state.markRunning("cfg-1")
        state.isRunning("cfg-1") shouldBe true
        state.isRunning("cfg-2") shouldBe false
    }

    "markStopped removes only that config" {
        val state = ForegroundRunState()
        state.markRunning("cfg-1")
        state.markRunning("cfg-2")
        state.markStopped("cfg-1")
        state.isRunning("cfg-1") shouldBe false
        state.isRunning("cfg-2") shouldBe true
    }

    "markStopped without a prior markRunning is harmless" {
        val state = ForegroundRunState()
        state.markStopped("cfg-1")
        state.isRunning("cfg-1") shouldBe false
    }

    "observeIsRunning reflects the current state" {
        runTest {
            val state = ForegroundRunState()
            state.observeIsRunning("cfg-1").first() shouldBe false
            state.markRunning("cfg-1")
            state.observeIsRunning("cfg-1").first() shouldBe true
            state.markStopped("cfg-1")
            state.observeIsRunning("cfg-1").first() shouldBe false
        }
    }
})
