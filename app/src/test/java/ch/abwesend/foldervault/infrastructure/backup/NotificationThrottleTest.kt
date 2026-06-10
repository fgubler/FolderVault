package ch.abwesend.folderVault.infrastructure.backup

import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.infrastructure.backup.BackupNotificationManager
import ch.abwesend.foldervault.infrastructure.room.entity.NotificationThrottleStateEntity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class NotificationThrottleTest : StringSpec({

    val configId = "config-1"
    val window = BackupNotificationManager.THROTTLE_WINDOW_MS

    "no prior throttle state — should notify" {
        val result = BackupNotificationManager.shouldNotify(state = null, nowMs = 1_000_000L)
        result shouldBe true
    }

    "throttle state within window — should not notify" {
        val lastNotified = 1_000_000L
        val now = lastNotified + window - 1
        val state = throttleState(configId, MessageType.AUTH_LOST, lastNotifiedAt = lastNotified)
        val result = BackupNotificationManager.shouldNotify(state, nowMs = now)
        result shouldBe false
    }

    "throttle state exactly at window boundary — should notify" {
        val lastNotified = 1_000_000L
        val now = lastNotified + window
        val state = throttleState(configId, MessageType.AUTH_LOST, lastNotifiedAt = lastNotified)
        val result = BackupNotificationManager.shouldNotify(state, nowMs = now)
        result shouldBe true
    }

    "throttle state beyond window — should notify again" {
        val lastNotified = 1_000_000L
        val now = lastNotified + window + 1
        val state = throttleState(configId, MessageType.QUOTA_EXCEEDED, lastNotifiedAt = lastNotified)
        val result = BackupNotificationManager.shouldNotify(state, nowMs = now)
        result shouldBe true
    }
})

private fun throttleState(
    configId: String,
    type: MessageType,
    lastNotifiedAt: Long,
) = NotificationThrottleStateEntity(
    backupConfigId = configId,
    messageType = type,
    lastNotifiedAt = lastNotifiedAt,
    lastRunId = null,
)
