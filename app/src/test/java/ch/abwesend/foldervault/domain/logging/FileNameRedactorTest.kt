package ch.abwesend.folderVault.domain.logging

import ch.abwesend.foldervault.domain.logging.FileNameRedactor
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
})
