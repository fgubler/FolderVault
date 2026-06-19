package ch.abwesend.folderVault.domain.backup

import ch.abwesend.foldervault.domain.backup.SubFolderNameBuilder
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith

class SubFolderNameBuilderTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    val sampleUri = "content://com.android.externalstorage.documents/tree/primary%3APhotos"

    "ASCII display name produces clean name with 6-char hex suffix" {
        val result = SubFolderNameBuilder.buildName("Photos", sampleUri)
        result shouldStartWith "Photos_"
        result shouldMatch Regex("Photos_[0-9a-f]{6}")
    }

    "Same (displayName, treeUri) pair returns the same name (deterministic)" {
        val a = SubFolderNameBuilder.buildName("Photos", sampleUri)
        val b = SubFolderNameBuilder.buildName("Photos", sampleUri)
        a shouldBe b
    }

    "Different treeUri produces different hash suffix" {
        val a = SubFolderNameBuilder.buildName("Photos", sampleUri)
        val b = SubFolderNameBuilder.buildName(
            "Photos",
            "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera",
        )
        a shouldNotBe b
    }

    "Forward slashes in display name are replaced with underscores" {
        val result = SubFolderNameBuilder.buildName("Photos/2026", sampleUri)
        result shouldStartWith "Photos_2026_"
    }

    "Whitespace is collapsed and trimmed" {
        val result = SubFolderNameBuilder.buildName("  My   Photos  ", sampleUri)
        result shouldStartWith "My Photos_"
    }

    "Control characters are stripped" {
        val result = SubFolderNameBuilder.buildName("PhotosAlbum", sampleUri)
        result shouldStartWith "PhotosAlbum_"
    }

    "Empty display name falls back to 'backup'" {
        val result = SubFolderNameBuilder.buildName("", sampleUri)
        result shouldStartWith "backup_"
    }

    "Whitespace-only display name falls back to 'backup'" {
        val result = SubFolderNameBuilder.buildName("   \t  \n  ", sampleUri)
        result shouldStartWith "backup_"
    }

    "Unicode display names survive intact" {
        val result = SubFolderNameBuilder.buildName("Föötögräphß", sampleUri)
        result shouldStartWith "Föötögräphß_"
    }

    "Very long display name is truncated to 80 chars before hash" {
        val longName = "A".repeat(200)
        val result = SubFolderNameBuilder.buildName(longName, sampleUri)
        result.length shouldBe 87
        result shouldMatch Regex("A{80}_[0-9a-f]{6}")
    }
})
