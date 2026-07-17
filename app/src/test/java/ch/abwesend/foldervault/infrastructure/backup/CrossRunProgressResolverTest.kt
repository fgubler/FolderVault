package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.model.BackupRunStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies [resolveCrossRunProgress] — in particular that a hard-cancelled run PERSISTs its
 * discovered/uploaded progress (rather than leaving the counters unchanged), so an initial sync
 * interrupted by the OS killing a background worker still routes its next run to the foreground
 * service instead of crawling in WorkManager's short windows.
 */
class CrossRunProgressResolverTest : StringSpec({

    "a time-budget stop persists progress" {
        val summary = RunSummary().apply { hitTimeBudget = true }
        resolveCrossRunProgress(
            BackupRunStatus.INITIAL_SYNC_IN_PROGRESS,
            summary,
            completedNormally = false,
        ) shouldBe CrossRunProgress.PERSIST
    }

    "a hard-cancelled run persists progress instead of discarding it" {
        resolveCrossRunProgress(
            BackupRunStatus.CANCELLED,
            RunSummary(),
            completedNormally = false,
        ) shouldBe CrossRunProgress.PERSIST
    }

    "a run that completed normally resets the counters" {
        resolveCrossRunProgress(
            BackupRunStatus.UP_TO_DATE,
            RunSummary(),
            completedNormally = true,
        ) shouldBe CrossRunProgress.RESET
    }

    "a failed run leaves the counters unchanged" {
        val summary = RunSummary().apply { authLost = true }
        resolveCrossRunProgress(
            BackupRunStatus.FAILED,
            summary,
            completedNormally = false,
        ) shouldBe CrossRunProgress.UNCHANGED
    }

    "a quota-exceeded run leaves the counters unchanged" {
        val summary = RunSummary().apply {
            quotaExceeded = true
            filesUploaded = 3
        }
        resolveCrossRunProgress(
            BackupRunStatus.COMPLETED_WITH_WARNINGS,
            summary,
            completedNormally = false,
        ) shouldBe CrossRunProgress.UNCHANGED
    }
})
