package ch.abwesend.foldervault.domain

import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.CloudAccountRoot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class AppSettingsTest : StringSpec({

    val root1 = CloudAccountRoot(
        accountIdentifier = "one@test.com",
        rootFolderId = "root-1",
        rootFolderName = "FolderVault_one",
    )
    val root2 = CloudAccountRoot(
        accountIdentifier = "two@test.com",
        rootFolderId = "root-2",
        rootFolderName = "FolderVault_two",
    )

    "rootForAccount returns the matching root" {
        val settings = AppSettings(cloudRoots = listOf(root1, root2))
        settings.rootForAccount("two@test.com") shouldBe root2
    }

    "rootForAccount returns null when the account has no root" {
        val settings = AppSettings(cloudRoots = listOf(root1))
        settings.rootForAccount("unknown@test.com") shouldBe null
    }

    "rootForAccount returns null on empty settings" {
        AppSettings().rootForAccount("one@test.com") shouldBe null
    }

    "CloudAccountRoot list survives a JSON round-trip" {
        val roots = listOf(root1, root2)
        val json = Json.encodeToString(roots)
        Json.decodeFromString<List<CloudAccountRoot>>(json) shouldBe roots
    }
})
