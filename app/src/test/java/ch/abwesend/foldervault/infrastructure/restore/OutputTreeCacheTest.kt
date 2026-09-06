package ch.abwesend.foldervault.infrastructure.restore

import androidx.documentfile.provider.FakeDocumentFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class OutputTreeCacheTest : StringSpec({

    "a directory is listed once no matter how many lookups it serves" {
        // The O(N²) regression: DocumentFile.findFile lists every child on each call, so a flat
        // backup folder of N files used to cost N full listings — each one an IPC round-trip.
        val root = FakeDocumentFile("root", directory = true)
        repeat(3) { root.children.add(FakeDocumentFile("existing$it")) }
        val cache = OutputTreeCache(root)

        repeat(10) { cache.findChild(root, "existing1") }

        root.listCallCount shouldBe 1
    }

    "an existing child is found by its display name" {
        val root = FakeDocumentFile("root", directory = true)
        val existing = FakeDocumentFile("report.pdf")
        root.children.add(existing)
        val cache = OutputTreeCache(root)

        cache.findChild(root, "report.pdf") shouldBe existing
        cache.findChild(root, "other.pdf") shouldBe null
    }

    "a freshly created child is found again without re-listing the directory" {
        val root = FakeDocumentFile("root", directory = true)
        val cache = OutputTreeCache(root)

        val created = cache.createChild(root, "application/octet-stream", "report.pdf")
        val found = cache.findChild(root, "report.pdf")

        created shouldNotBe null
        found shouldBe created
        root.listCallCount shouldBe 1
    }

    "a forgotten child is no longer found, so a deleted output cannot be handed back" {
        val root = FakeDocumentFile("root", directory = true)
        val cache = OutputTreeCache(root)
        val created = cache.createChild(root, "application/octet-stream", "report.pdf")!!

        cache.forgetChild(root, cache.nameOf(created))

        cache.findChild(root, "report.pdf") shouldBe null
    }

    // The name has to be captured while the document still exists: DocumentFile.getName() is a
    // fresh provider query, so a deleted document answers null and an un-index attempted
    // afterwards silently removes nothing — which is exactly what used to happen in production
    // while the fake, then still answering with its name, reported success.
    "the name captured before a delete still un-indexes the child afterwards" {
        val root = FakeDocumentFile("root", directory = true)
        val cache = OutputTreeCache(root)
        val created = cache.createChild(root, "application/octet-stream", "report.pdf")!!

        val capturedName = cache.nameOf(created)
        created.delete()
        cache.forgetChild(root, capturedName)

        capturedName shouldBe "report.pdf"
        created.name shouldBe null
        cache.findChild(root, "report.pdf") shouldBe null
    }

    "reading the name only after the delete un-indexes nothing, which is why callers must not" {
        val root = FakeDocumentFile("root", directory = true)
        val cache = OutputTreeCache(root)
        val created = cache.createChild(root, "application/octet-stream", "report.pdf")!!

        created.delete()
        cache.forgetChild(root, cache.nameOf(created))

        cache.findChild(root, "report.pdf") shouldNotBe null
    }

    "resolveDirectory creates missing directories and reuses them on the next call" {
        val root = FakeDocumentFile("root", directory = true)
        val cache = OutputTreeCache(root)

        val first = cache.resolveDirectory(listOf("photos", "2024"))
        val second = cache.resolveDirectory(listOf("photos", "2024"))

        first shouldNotBe null
        second shouldBe first
        root.children.count { it.name == "photos" } shouldBe 1
    }

    "resolveDirectory reuses a directory that already exists in the tree" {
        val root = FakeDocumentFile("root", directory = true)
        val existing = FakeDocumentFile("photos", directory = true)
        root.children.add(existing)
        val cache = OutputTreeCache(root)

        cache.resolveDirectory(listOf("photos")) shouldBe existing
    }

    "resolveDirectory of the tree root itself is the root" {
        val root = FakeDocumentFile("root", directory = true)

        OutputTreeCache(root).resolveDirectory(emptyList()) shouldBe root
    }

    "resolveDirectory fails when a plain file blocks the directory name" {
        // Restoring `a/b.txt` into a folder that already holds a *file* called `a` cannot work;
        // it must count as a failure rather than silently writing somewhere else.
        val root = FakeDocumentFile("root", directory = true)
        root.children.add(FakeDocumentFile("photos"))
        val cache = OutputTreeCache(root)

        cache.resolveDirectory(listOf("photos", "2024")) shouldBe null
    }
})
