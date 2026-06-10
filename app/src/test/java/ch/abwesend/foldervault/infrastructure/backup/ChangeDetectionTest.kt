package ch.abwesend.folderVault.infrastructure.backup

import ch.abwesend.foldervault.infrastructure.backup.ChangeDetector
import ch.abwesend.foldervault.infrastructure.backup.ChangeDetector.Decision
import ch.abwesend.foldervault.infrastructure.room.entity.UploadedFileIndexEntity
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ChangeDetectionTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    fun makeIndexed(mtime: Long = 1_000_000L, size: Long = 512L) = UploadedFileIndexEntity(
        id = 1L,
        backupConfigId = "cfg-1",
        relativePath = "docs/file.txt",
        localLastModified = mtime,
        localSize = size,
        cloudFileId = "cloud-abc",
        remoteName = "file.txt.crypt",
        uploadedAt = 999_000L,
        isCurrentVersion = true,
    )

    // ── No index row ──────────────────────────────────────────────────────────

    "no index row → NEW regardless of mtime" {
        ChangeDetector.decide(localMtime = 1_000L, localSize = 512L, indexed = null) shouldBe Decision.NEW
    }

    "no index row and null mtime → NEW" {
        ChangeDetector.decide(localMtime = null, localSize = 0L, indexed = null) shouldBe Decision.NEW
    }

    // ── Usable mtime ──────────────────────────────────────────────────────────

    "usable mtime differs from indexed → CHANGED" {
        val indexed = makeIndexed(mtime = 1_000_000L, size = 512L)
        ChangeDetector.decide(localMtime = 2_000_000L, localSize = 512L, indexed = indexed) shouldBe Decision.CHANGED
    }

    "usable mtime same but size differs → CHANGED" {
        val indexed = makeIndexed(mtime = 1_000_000L, size = 512L)
        ChangeDetector.decide(localMtime = 1_000_000L, localSize = 1024L, indexed = indexed) shouldBe Decision.CHANGED
    }

    "usable mtime same and size same → UNCHANGED" {
        val indexed = makeIndexed(mtime = 1_000_000L, size = 512L)
        ChangeDetector.decide(localMtime = 1_000_000L, localSize = 512L, indexed = indexed) shouldBe Decision.UNCHANGED
    }

    // ── Unreliable / unavailable mtime ────────────────────────────────────────

    "null mtime and size differs from indexed → CHANGED" {
        val indexed = makeIndexed(mtime = 1_000_000L, size = 512L)
        ChangeDetector.decide(localMtime = null, localSize = 1024L, indexed = indexed) shouldBe Decision.CHANGED
    }

    "zero mtime and size differs from indexed → CHANGED" {
        val indexed = makeIndexed(mtime = 1_000_000L, size = 512L)
        ChangeDetector.decide(localMtime = 0L, localSize = 1024L, indexed = indexed) shouldBe Decision.CHANGED
    }

    "null mtime and same size → CHECK_CLOUD" {
        val indexed = makeIndexed(mtime = 1_000_000L, size = 512L)
        ChangeDetector.decide(localMtime = null, localSize = 512L, indexed = indexed) shouldBe Decision.CHECK_CLOUD
    }

    "zero mtime and same size → CHECK_CLOUD" {
        val indexed = makeIndexed(mtime = 1_000_000L, size = 512L)
        ChangeDetector.decide(localMtime = 0L, localSize = 512L, indexed = indexed) shouldBe Decision.CHECK_CLOUD
    }
})
