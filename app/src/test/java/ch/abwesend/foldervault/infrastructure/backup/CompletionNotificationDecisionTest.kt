package ch.abwesend.foldervault.infrastructure.backup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Covers the pure decision logic of the per-run completion notification: only terminal run
 * results notify — retries (auth lost) and time-budget continuations stay silent. Cancelled
 * runs never produce a [RunResult] in the first place, so they need no mapping here.
 */
class CompletionNotificationDecisionTest : StringSpec({

    val runId = "run-1"

    "clean success maps to a SUCCESS notification" {
        val result = RunResult.Success(RunSummary(), runId)

        BackupNotificationManager.completionOutcomeOf(result) shouldBe BackupRunOutcome.SUCCESS
    }

    "success that hit the time budget stays silent (continuation run is scheduled)" {
        val summary = RunSummary().apply { hitTimeBudget = true }
        val result = RunResult.Success(summary, runId)

        BackupNotificationManager.completionOutcomeOf(result) shouldBe null
    }

    "auth-lost stays silent (WorkManager retries the run)" {
        val summary = RunSummary().apply { authLost = true }
        val result = RunResult.AuthLost(summary, runId)

        BackupNotificationManager.completionOutcomeOf(result) shouldBe null
    }

    "fatal error maps to a FAILURE notification" {
        val result = RunResult.FatalError(IllegalStateException("boom"), RunSummary(), runId)

        BackupNotificationManager.completionOutcomeOf(result) shouldBe BackupRunOutcome.FAILURE
    }

    "completion notification IDs never collide with problem notification IDs" {
        val configIds = listOf("config-1", "config-2", "some-uuid-value", "")

        configIds.forEach { configId ->
            val completionId = BackupNotificationManager.completionId(configId)
            val problemId = BackupNotificationManager.problemId(configId)

            completionId shouldNotBe problemId
            (completionId and 0x20000000) shouldBe 0x20000000
            (completionId and 0x10000000) shouldBe 0
        }
    }
})
