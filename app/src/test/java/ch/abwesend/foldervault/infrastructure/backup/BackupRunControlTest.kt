package ch.abwesend.foldervault.infrastructure.backup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Pins down the cooperative-stop semantics of [BackupRunControl]: a run stops when (and only
 * when) its deadline has passed or a stop was explicitly requested, and live per-run progress
 * is exposed through [BackupRunControl.filesUploadedThisRun].
 */
class BackupRunControlTest : StringSpec({

    "shouldStop is false with no deadline and no stop request" {
        val control = BackupRunControl()
        control.shouldStop() shouldBe false
    }

    "shouldStop is false while the deadline lies in the future" {
        val control = BackupRunControl(deadline = Instant.now().plusSeconds(3600))
        control.shouldStop() shouldBe false
    }

    "shouldStop is true once the deadline has passed" {
        val control = BackupRunControl(deadline = Instant.now().minusSeconds(1))
        control.shouldStop() shouldBe true
    }

    "requestStop flips shouldStop even without a deadline" {
        val control = BackupRunControl()
        control.requestStop()
        control.shouldStop() shouldBe true
    }

    "requestStop flips shouldStop even with a future deadline" {
        val control = BackupRunControl(deadline = Instant.now().plusSeconds(3600))
        control.requestStop()
        control.shouldStop() shouldBe true
    }

    "requestStop is idempotent" {
        val control = BackupRunControl()
        control.requestStop()
        control.requestStop()
        control.shouldStop() shouldBe true
    }

    "progress flow starts at zero and reflects the latest reported count" {
        val control = BackupRunControl()
        control.filesUploadedThisRun.value shouldBe 0
        control.reportFileUploaded(1)
        control.filesUploadedThisRun.value shouldBe 1
        control.reportFileUploaded(42)
        control.filesUploadedThisRun.value shouldBe 42
    }

    "discovered-files flow starts at zero and reflects the reported total" {
        val control = BackupRunControl()
        control.filesDiscovered.value shouldBe 0
        control.reportFilesDiscovered(1337)
        control.filesDiscovered.value shouldBe 1337
    }
})
