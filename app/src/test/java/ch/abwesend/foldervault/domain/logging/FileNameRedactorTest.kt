package ch.abwesend.foldervault.domain.logging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class FileNameRedactorTest : StringSpec({
    // ── redact ────────────────────────────────────────────────────────────────

    "redact keeps first char and last extension for normal file" {
        FileNameRedactor.redact("report.pdf") shouldBe "r***.pdf"
    }

    "redact preserves case of first char" {
        FileNameRedactor.redact("IMG_2024.jpg") shouldBe "I***.jpg"
    }

    "redact handles dotfile" {
        FileNameRedactor.redact(".env") shouldBe ".***"
    }

    "redact handles file with no extension" {
        FileNameRedactor.redact("notes") shouldBe "n***"
    }

    "redact handles multi-dot name keeping only last extension" {
        FileNameRedactor.redact("archive.tar.gz") shouldBe "a***.gz"
    }

    "redact returns empty string for empty input" {
        FileNameRedactor.redact("") shouldBe ""
    }

    "redact handles single-char name with extension" {
        FileNameRedactor.redact("a.txt") shouldBe "a***.txt"
    }

    "redact handles lone dot" {
        FileNameRedactor.redact(".") shouldBe "."
    }

    "redact handles dotfile with sub-extension" {
        FileNameRedactor.redact(".gitignore") shouldBe ".***"
    }

    // ── redactPath ────────────────────────────────────────────────────────────

    "redactPath redacts each segment independently" {
        FileNameRedactor.redactPath("docs/images/photo.jpg") shouldBe "d***/i***/p***.jpg"
    }

    "redactPath returns empty string for empty input" {
        FileNameRedactor.redactPath("") shouldBe ""
    }

    "redactPath preserves leading slash" {
        FileNameRedactor.redactPath("/data/user") shouldBe "/d***/u***"
    }

    "redactPath handles single segment" {
        FileNameRedactor.redactPath("file.txt") shouldBe "f***.txt"
    }

    "redactPath handles dotfiles in path" {
        FileNameRedactor.redactPath("project/.env") shouldBe "p***/.***"
    }

    // ── redactPathsIn ───────────────────────────────────────────────────────────

    "redactPathsIn leaves a slash-free sentence completely untouched" {
        val message = "Backup run for config 1234 lost auth; retrying"
        FileNameRedactor.redactPathsIn(message) shouldBe message
    }

    "redactPathsIn redacts only the path token and keeps the surrounding words" {
        FileNameRedactor.redactPathsIn("Failed to prepare local file for photos/2024/img.jpg") shouldBe
            "Failed to prepare local file for p***/2***/i***.jpg"
    }

    "redactPathsIn preserves trailing punctuation after a path token" {
        FileNameRedactor.redactPathsIn("Upload failed for photos/img.jpg: timeout") shouldBe
            "Upload failed for p***/i***.jpg: timeout"
    }

    "redactPathsIn does not mangle fully-qualified class names (no slash)" {
        val message = "com.google.api.client.http.HttpResponseException: 404 Not Found"
        FileNameRedactor.redactPathsIn(message) shouldBe message
    }

    "redactPathsIn redacts an absolute filesystem path" {
        FileNameRedactor.redactPathsIn("Permission denied: /storage/emulated/0/DCIM/priv.jpg") shouldBe
            "Permission denied: /s***/e***/0***/D***/p***.jpg"
    }

    "redactPathsIn returns empty string for empty input" {
        FileNameRedactor.redactPathsIn("") shouldBe ""
    }
})
