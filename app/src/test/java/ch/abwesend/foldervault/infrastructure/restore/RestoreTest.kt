package ch.abwesend.foldervault.infrastructure.restore

import ch.abwesend.foldervault.domain.crypto.DecryptionError
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.infrastructure.crypto.Fvc1Cipher
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream

class RestoreTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val cipher = Fvc1Cipher()

    // ── Decrypt round-trip ────────────────────────────────────────────────────

    "decryptFileWithPassword round-trips a text payload" {
        val original = "Hello, FolderVault restore!".toByteArray()
        val password = "correct-horse-battery-staple"
        val salt = cipher.generateBackupSalt()
        val key = cipher.deriveKey(password, salt)

        val encrypted = ByteArrayOutputStream()
        cipher.encryptFile(key, salt, original.inputStream(), encrypted)

        val decrypted = ByteArrayOutputStream()
        val result = cipher.decryptFileWithPassword(
            password,
            encrypted.toByteArray().inputStream(),
            decrypted,
        )

        result.shouldBeInstanceOf<SuccessResult<Unit>>()
        decrypted.toByteArray() shouldBe original
    }

    "decryptFileWithPassword returns INVALID_PASSWORD for a wrong password" {
        val salt = cipher.generateBackupSalt()
        val key = cipher.deriveKey("correct-password", salt)

        val encrypted = ByteArrayOutputStream()
        cipher.encryptFile(key, salt, "Secret".toByteArray().inputStream(), encrypted)

        val result = cipher.decryptFileWithPassword(
            "wrong-password",
            encrypted.toByteArray().inputStream(),
            ByteArrayOutputStream(),
        )

        result.shouldBeInstanceOf<ErrorResult<DecryptionError>>()
        (result as ErrorResult<DecryptionError>).error shouldBe DecryptionError.INVALID_PASSWORD
    }

    "decryptFileWithPassword handles binary (non-text) content" {
        val original = ByteArray(256) { it.toByte() }
        val password = "binary-test-pw"
        val salt = cipher.generateBackupSalt()
        val key = cipher.deriveKey(password, salt)

        val encrypted = ByteArrayOutputStream()
        cipher.encryptFile(key, salt, original.inputStream(), encrypted)

        val decrypted = ByteArrayOutputStream()
        cipher.decryptFileWithPassword(password, encrypted.toByteArray().inputStream(), decrypted)

        decrypted.toByteArray() shouldBe original
    }

    // ── Path resolution (tree reconstruction) ────────────────────────────────

    "outputRelativePath strips .crypt suffix from encrypted files" {
        RestorePathResolver.outputRelativePath("docs/report.pdf.crypt", true) shouldBe "docs/report.pdf"
    }

    "outputRelativePath preserves nested structure" {
        RestorePathResolver.outputRelativePath("a/b/c/image.png.crypt", true) shouldBe "a/b/c/image.png"
    }

    "outputRelativePath keeps path unchanged for plain files" {
        RestorePathResolver.outputRelativePath(".foldervault-manifest.json", false) shouldBe
            ".foldervault-manifest.json"
    }

    // ── Collision policy ──────────────────────────────────────────────────────

    "resolvedName returns null for SKIP — caller must skip the file" {
        RestorePathResolver.resolvedName("report.pdf", RestoreCollisionPolicy.SKIP) shouldBe null
    }

    "resolvedName returns the original name for OVERWRITE" {
        RestorePathResolver.resolvedName("report.pdf", RestoreCollisionPolicy.OVERWRITE) shouldBe "report.pdf"
    }

    "resolvedName inserts _restored before extension for RENAME_WITH_SUFFIX" {
        RestorePathResolver.resolvedName("report.pdf", RestoreCollisionPolicy.RENAME_WITH_SUFFIX) shouldBe
            "report_restored.pdf"
    }

    "resolvedName appends _restored to extension-less name for RENAME_WITH_SUFFIX" {
        RestorePathResolver.resolvedName("README", RestoreCollisionPolicy.RENAME_WITH_SUFFIX) shouldBe
            "README_restored"
    }

    "resolvedName treats dotfiles (no real extension) as extension-less for RENAME_WITH_SUFFIX" {
        RestorePathResolver.resolvedName(".hidden", RestoreCollisionPolicy.RENAME_WITH_SUFFIX) shouldBe
            ".hidden_restored"
    }
})
