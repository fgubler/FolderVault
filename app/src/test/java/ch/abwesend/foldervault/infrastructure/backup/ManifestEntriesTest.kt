package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.infrastructure.room.entity.UploadedFileIndexEntity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private fun indexRow(path: String, cloudFileId: String, isBaseline: Boolean) = UploadedFileIndexEntity(
    id = 0L,
    backupConfigId = "cfg",
    relativePath = path,
    localLastModified = 11L,
    localSize = 22L,
    cloudFileId = cloudFileId,
    remoteName = if (isBaseline) "" else "$path.crypt",
    uploadedAt = 33L,
    isCurrentVersion = true,
    isBaseline = isBaseline,
)

class ManifestEntriesTest : StringSpec({

    "baseline rows are excluded from the manifest" {
        val rows = listOf(
            indexRow("uploaded.txt", cloudFileId = "cloud-1", isBaseline = false),
            indexRow("pre-existing.txt", cloudFileId = "", isBaseline = true),
        )

        val entries = buildManifestEntries(rows)

        entries.map { it.relativePath } shouldBe listOf("uploaded.txt")
    }

    "uploaded rows are mapped with all their metadata" {
        val entries = buildManifestEntries(listOf(indexRow("a.txt", cloudFileId = "cloud-a", isBaseline = false)))

        val entry = entries.single()
        entry.relativePath shouldBe "a.txt"
        entry.mtime shouldBe 11L
        entry.size shouldBe 22L
        entry.cloudFileId shouldBe "cloud-a"
        entry.remoteName shouldBe "a.txt.crypt"
    }

    "only-baseline index yields an empty manifest" {
        buildManifestEntries(listOf(indexRow("x", cloudFileId = "", isBaseline = true))) shouldBe emptyList()
    }
})
