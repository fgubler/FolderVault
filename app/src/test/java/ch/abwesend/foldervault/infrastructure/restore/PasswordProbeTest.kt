package ch.abwesend.foldervault.infrastructure.restore

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Covers the decision half of the folder restore's password check: which files get probed, and
 * what their outcomes mean together. The regression this guards is a whole restore reporting
 * "wrong password" — and writing nothing — because the one file it happened to probe was damaged.
 */
class PasswordProbeTest : StringSpec({

    "selectCandidates probes the smallest files first, so verification never reads a huge file" {
        val files = listOf("big" to 900L, "small" to 10L, "medium" to 100L)

        PasswordProbe.selectCandidates(files) { it.second }.map { it.first } shouldBe
            listOf("small", "medium", "big")
    }

    "selectCandidates caps the number of probes" {
        val files = (1..20).map { "f$it" to it.toLong() }

        PasswordProbe.selectCandidates(files) { it.second } shouldBe files.take(PasswordProbe.MAX_PROBES)
    }

    "selectCandidates drops empty files, which can never carry a header" {
        val files = listOf("empty" to 0L, "good" to 50L, "alsoEmpty" to 0L)

        PasswordProbe.selectCandidates(files) { it.second }.map { it.first } shouldBe listOf("good")
    }

    "selectCandidates keeps everything when no file reports a size at all" {
        // length() returns 0 for "unknown", not only for "empty" — a provider that reports no
        // sizes must not leave the restore with nothing to probe.
        val files = listOf("a" to 0L, "b" to 0L)

        PasswordProbe.selectCandidates(files) { it.second } shouldBe files
    }

    "selectCandidates returns nothing when there is nothing encrypted" {
        PasswordProbe.selectCandidates(emptyList<Pair<String, Long>>()) { it.second } shouldBe emptyList()
    }

    "shouldProceed accepts as soon as one probe decrypts, whatever the others said" {
        PasswordProbe.shouldProceed(
            listOf(ProbeOutcome.WRONG_PASSWORD, ProbeOutcome.INCONCLUSIVE, ProbeOutcome.CORRECT_PASSWORD),
        ) shouldBe true
    }

    "shouldProceed rejects only when every usable probe failed its tag check" {
        PasswordProbe.shouldProceed(List(PasswordProbe.MAX_PROBES) { ProbeOutcome.WRONG_PASSWORD }) shouldBe false
    }

    "shouldProceed rejects a single tag failure when it was the only usable probe" {
        // The common, healthy case: one encrypted file in the folder and the password is wrong.
        PasswordProbe.shouldProceed(listOf(ProbeOutcome.WRONG_PASSWORD)) shouldBe false
    }

    "shouldProceed proceeds when every probe was unusable, rather than blaming the password" {
        // The regression case: damaged files must surface as per-file failures, not as a folder-
        // wide "wrong password" that restores nothing.
        PasswordProbe.shouldProceed(List(3) { ProbeOutcome.INCONCLUSIVE }) shouldBe true
    }

    "shouldProceed proceeds when there was nothing to probe" {
        PasswordProbe.shouldProceed(emptyList()) shouldBe true
    }

    "shouldProceed tolerates damaged files alongside a wrong password" {
        // Mixed evidence with no successful decrypt: the tag failures still decide.
        PasswordProbe.shouldProceed(
            listOf(ProbeOutcome.INCONCLUSIVE, ProbeOutcome.WRONG_PASSWORD, ProbeOutcome.INCONCLUSIVE),
        ) shouldBe false
    }
})
