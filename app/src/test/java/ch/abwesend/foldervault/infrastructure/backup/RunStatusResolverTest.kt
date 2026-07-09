package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.model.BackupRunStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies [resolveRunStatus] — in particular that an inaccessible source folder fails the run
 * instead of masquerading as a silent, up-to-date success with zero files (BUG-4).
 */
class RunStatusResolverTest : StringSpec({

    "a clean run with no problems is UP_TO_DATE" {
        resolveRunStatus(RunSummary()) shouldBe BackupRunStatus.UP_TO_DATE
    }

    "an inaccessible source folder fails the run instead of reporting UP_TO_DATE" {
        val summary = RunSummary().apply { sourceFolderInaccessible = true }
        resolveRunStatus(summary) shouldBe BackupRunStatus.FAILED
    }

    "an inaccessible source folder fails even when no files were discovered or failed" {
        val summary = RunSummary().apply {
            sourceFolderInaccessible = true
            filesUploaded = 0
            filesFailed = 0
            totalFilesDiscovered = 0
        }
        resolveRunStatus(summary) shouldBe BackupRunStatus.FAILED
    }

    "auth loss takes precedence and fails the run" {
        val summary = RunSummary().apply {
            authLost = true
            sourceFolderInaccessible = true
        }
        resolveRunStatus(summary) shouldBe BackupRunStatus.FAILED
    }

    "hitting the time budget reports an in-progress initial sync" {
        val summary = RunSummary().apply { hitTimeBudget = true }
        resolveRunStatus(summary) shouldBe BackupRunStatus.INITIAL_SYNC_IN_PROGRESS
    }

    "an inaccessible source outranks the time budget" {
        val summary = RunSummary().apply {
            sourceFolderInaccessible = true
            hitTimeBudget = true
        }
        resolveRunStatus(summary) shouldBe BackupRunStatus.FAILED
    }

    "quota exceeded with nothing uploaded fails the run" {
        val summary = RunSummary().apply {
            quotaExceeded = true
            filesUploaded = 0
        }
        resolveRunStatus(summary) shouldBe BackupRunStatus.FAILED
    }

    "quota exceeded after some uploads completes with warnings" {
        val summary = RunSummary().apply {
            quotaExceeded = true
            filesUploaded = 3
        }
        resolveRunStatus(summary) shouldBe BackupRunStatus.COMPLETED_WITH_WARNINGS
    }

    "individual file failures complete with warnings" {
        val summary = RunSummary().apply { filesFailed = 2 }
        resolveRunStatus(summary) shouldBe BackupRunStatus.COMPLETED_WITH_WARNINGS
    }
})
