package ch.abwesend.foldervault.infrastructure.backup

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import java.time.Instant

class RemoteNameBuilderTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    // ── NEW mode ──────────────────────────────────────────────────────────────

    "NEW mode, not encrypted → returns original name unchanged" {
        val result = RemoteNameBuilder.buildName("photo.jpg", UploadMode.NEW, encrypted = false)
        result shouldBe "photo.jpg"
    }

    "NEW mode, encrypted → appends .crypt" {
        val result = RemoteNameBuilder.buildName("photo.jpg", UploadMode.NEW, encrypted = true)
        result shouldBe "photo.jpg.crypt"
    }

    // ── CHANGED_OVERWRITE mode ────────────────────────────────────────────────

    "CHANGED_OVERWRITE, encrypted → returns original name with .crypt" {
        val result = RemoteNameBuilder.buildName("report.pdf", UploadMode.CHANGED_OVERWRITE, encrypted = true)
        result shouldBe "report.pdf.crypt"
    }

    "CHANGED_OVERWRITE, not encrypted → returns original name" {
        val result = RemoteNameBuilder.buildName("report.pdf", UploadMode.CHANGED_OVERWRITE, encrypted = false)
        result shouldBe "report.pdf"
    }

    // ── CHANGED_DUPLICATE mode ────────────────────────────────────────────────

    "CHANGED_DUPLICATE with extension embeds timestamp between stem and extension" {
        val fixedInstant = Instant.parse("2026-03-15T10:30:45Z")
        val result = RemoteNameBuilder.buildTimestampedName("photo.jpg", fixedInstant)
        result shouldBe "photo__2026-03-15T10-30-45Z.jpg"
    }

    "CHANGED_DUPLICATE without extension appends timestamp after name" {
        val fixedInstant = Instant.parse("2026-03-15T10:30:45Z")
        val result = RemoteNameBuilder.buildTimestampedName("Makefile", fixedInstant)
        result shouldBe "Makefile__2026-03-15T10-30-45Z"
    }

    "CHANGED_DUPLICATE, encrypted → duplicate name gets .crypt appended" {
        val result = RemoteNameBuilder.buildName("archive.zip", UploadMode.CHANGED_DUPLICATE, encrypted = true)
        // The exact timestamp will vary, but the structure should match:
        // archive__<timestamp>.zip.crypt
        result shouldStartWith "archive__"
        result shouldEndWith ".zip.crypt"
    }

    "CHANGED_DUPLICATE, not encrypted → duplicate name has no .crypt" {
        val result = RemoteNameBuilder.buildName("notes.txt", UploadMode.CHANGED_DUPLICATE, encrypted = false)
        result shouldStartWith "notes__"
        result shouldEndWith ".txt"
    }

    "CHANGED_DUPLICATE timestamp format uses dashes not colons in time part" {
        val fixedInstant = Instant.parse("2026-06-10T14:05:09Z")
        val result = RemoteNameBuilder.buildTimestampedName("video.mp4", fixedInstant)
        result shouldContain "14-05-09"
        result shouldBe "video__2026-06-10T14-05-09Z.mp4"
    }
})
