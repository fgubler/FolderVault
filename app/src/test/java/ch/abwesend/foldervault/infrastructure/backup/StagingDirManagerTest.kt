package ch.abwesend.foldervault.infrastructure.backup

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class StagingDirManagerTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun tempRoot(): File = createTempDir("staging-test-").also { it.deleteOnExit() }

    // ── createRunDir ──────────────────────────────────────────────────────────

    "createRunDir creates a directory with the correct date suffix" {
        val root = tempRoot()
        val manager = StagingDirManager(root)
        val today = LocalDate.now().format(dateFormatter)
        val runId = "run-abc123"

        val dir = manager.createRunDir(runId)

        dir.exists() shouldBe true
        dir.isDirectory shouldBe true
        dir.name shouldBe "${runId}_$today"
    }

    "createRunDir returns a child of stagingRoot" {
        val root = tempRoot()
        val manager = StagingDirManager(root)

        val dir = manager.createRunDir("some-run")

        dir.parentFile?.canonicalPath shouldBe root.canonicalPath
    }

    // ── cleanupOldDirs ────────────────────────────────────────────────────────

    "cleanupOldDirs removes directories whose date is 2 or more days old" {
        val root = tempRoot()
        val manager = StagingDirManager(root)

        // Create a dir with a date 3 days ago → should be removed
        val oldDate = LocalDate.now().minusDays(3).format(dateFormatter)
        val oldDir = File(root, "run-old_$oldDate").also { it.mkdirs() }

        manager.cleanupOldDirs()

        oldDir.exists() shouldBe false
    }

    "cleanupOldDirs keeps directories whose date is exactly 1 day old" {
        val root = tempRoot()
        val manager = StagingDirManager(root)

        val recentDate = LocalDate.now().minusDays(1).format(dateFormatter)
        val recentDir = File(root, "run-recent_$recentDate").also { it.mkdirs() }

        manager.cleanupOldDirs()

        recentDir.exists() shouldBe true
    }

    "cleanupOldDirs keeps today's directory" {
        val root = tempRoot()
        val manager = StagingDirManager(root)

        val dir = manager.createRunDir("today-run")

        manager.cleanupOldDirs()

        dir.exists() shouldBe true
    }

    "cleanupOldDirs ignores entries without a valid date suffix" {
        val root = tempRoot()
        val manager = StagingDirManager(root)

        // A directory with no parseable date — should survive cleanup
        val strangeDir = File(root, "no-date-dir").also { it.mkdirs() }

        manager.cleanupOldDirs()

        strangeDir.exists() shouldBe true
    }
})
