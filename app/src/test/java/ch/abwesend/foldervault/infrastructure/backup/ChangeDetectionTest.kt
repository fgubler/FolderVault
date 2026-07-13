package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.infrastructure.backup.ChangeDetector.Decision
import ch.abwesend.foldervault.infrastructure.room.entity.UploadedFileIndexEntity
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ChangeDetectionTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    fun makeIndexed(mtime: Long = 1_000_000L, size: Long = 512L, isBaseline: Boolean = false) =
        UploadedFileIndexEntity(
            id = 1L,
            backupConfigId = "cfg-1",
            relativePath = "docs/file.txt",
            localLastModified = mtime,
            localSize = size,
            cloudFileId = if (isBaseline) "" else "cloud-abc",
            remoteName = if (isBaseline) "" else "file.txt.crypt",
            uploadedAt = 999_000L,
            isCurrentVersion = true,
            isBaseline = isBaseline,
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

    // ── Baseline rows (never uploaded — must never yield CHANGED or CHECK_CLOUD) ──

    "baseline row with same mtime and size → UNCHANGED" {
        val indexed = makeIndexed(mtime = 1_000_000L, size = 512L, isBaseline = true)
        ChangeDetector.decide(localMtime = 1_000_000L, localSize = 512L, indexed = indexed) shouldBe
            Decision.UNCHANGED
    }

    "baseline row with differing mtime → NEW (first upload, not a change)" {
        val indexed = makeIndexed(mtime = 1_000_000L, size = 512L, isBaseline = true)
        ChangeDetector.decide(localMtime = 2_000_000L, localSize = 512L, indexed = indexed) shouldBe
            Decision.NEW
    }

    "baseline row with same mtime but differing size → NEW" {
        val indexed = makeIndexed(mtime = 1_000_000L, size = 512L, isBaseline = true)
        ChangeDetector.decide(localMtime = 1_000_000L, localSize = 1024L, indexed = indexed) shouldBe
            Decision.NEW
    }

    "baseline row with unusable mtime and same size → UNCHANGED (no cloud object to check)" {
        val indexed = makeIndexed(mtime = 1_000_000L, size = 512L, isBaseline = true)
        ChangeDetector.decide(localMtime = null, localSize = 512L, indexed = indexed) shouldBe
            Decision.UNCHANGED
        ChangeDetector.decide(localMtime = 0L, localSize = 512L, indexed = indexed) shouldBe
            Decision.UNCHANGED
    }

    "baseline row with unusable mtime and differing size → NEW" {
        val indexed = makeIndexed(mtime = 1_000_000L, size = 512L, isBaseline = true)
        ChangeDetector.decide(localMtime = null, localSize = 1024L, indexed = indexed) shouldBe
            Decision.NEW
    }
})
