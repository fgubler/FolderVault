package ch.abwesend.folderVault.infrastructure.cloud.googledrive

import ch.abwesend.foldervault.infrastructure.cloud.googledrive.GoogleDriveRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DriveQueryEscapingTest : StringSpec({

    "plain string is unchanged" {
        GoogleDriveRepository.escapeDriveQueryLiteral("Documents") shouldBe "Documents"
    }

    "single quote is backslash-escaped" {
        GoogleDriveRepository.escapeDriveQueryLiteral("O'Brien") shouldBe "O\\'Brien"
    }

    "backslash is doubled" {
        GoogleDriveRepository.escapeDriveQueryLiteral("a\\b") shouldBe "a\\\\b"
    }

    "backslash is escaped before the quote escape runs so '\\'' stays a literal backslash plus quote" {
        // Input: backslash + single quote
        // Expected: backslash-backslash + backslash-quote
        GoogleDriveRepository.escapeDriveQueryLiteral("\\'") shouldBe "\\\\\\'"
    }

    "empty string yields empty string" {
        GoogleDriveRepository.escapeDriveQueryLiteral("") shouldBe ""
    }
})
