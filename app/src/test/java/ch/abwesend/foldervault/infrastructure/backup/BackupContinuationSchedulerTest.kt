package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.mockk.mockk
import io.mockk.verify

/**
 * Unit tests for the time-budget continuation decision: a charging-only fallback run must keep
 * its charging constraint (and dedicated unique name) across continuations, while every other
 * run continues as a plain one-time run carrying the config's own charging preference.
 */
class BackupContinuationSchedulerTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    "a charging-fallback continuation re-enqueues as a fallback with REPLACE" {
        val scheduler = mockk<IBackupScheduler>(relaxed = true)

        BackupContinuationScheduler.scheduleContinuation(
            scheduler = scheduler,
            configId = "cfg-1",
            networkPolicy = NetworkPolicy.WIFI_ONLY,
            requiresCharging = false,
            isChargingFallback = true,
        )

        verify(exactly = 1) {
            scheduler.scheduleChargingFallback("cfg-1", NetworkPolicy.WIFI_ONLY, replaceExisting = true)
        }
        verify(exactly = 0) { scheduler.scheduleOneTime(any(), any(), any()) }
    }

    "a normal continuation re-enqueues as one-time work, keeping the config's charging preference" {
        val scheduler = mockk<IBackupScheduler>(relaxed = true)

        BackupContinuationScheduler.scheduleContinuation(
            scheduler = scheduler,
            configId = "cfg-2",
            networkPolicy = NetworkPolicy.ANY,
            requiresCharging = true,
            isChargingFallback = false,
        )

        verify(exactly = 1) {
            scheduler.scheduleOneTime("cfg-2", NetworkPolicy.ANY, requiresCharging = true)
        }
        verify(exactly = 0) { scheduler.scheduleChargingFallback(any(), any(), any()) }
    }
})
