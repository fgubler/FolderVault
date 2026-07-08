package ch.abwesend.foldervault.infrastructure.backup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * Guards the WorkManager unique-work naming that keeps periodic, one-time, and charging-fallback
 * runs in separate namespaces. WorkManager shares one unique-name namespace across periodic and
 * one-time work, so a one-time REPLACE enqueue sharing the periodic name would silently cancel
 * the config's periodic schedule — the regression this fix prevents.
 */
class BackupWorkNameTest : StringSpec({

    val configId = "config-42"

    "the three unique-work names are distinct for the same config" {
        val names = listOf(
            BackupWorker.workName(configId),
            BackupWorker.oneTimeWorkName(configId),
            BackupWorker.chargingFallbackWorkName(configId),
        )

        names.toSet() shouldHaveSize names.size
    }

    "each name embeds the config id under its own prefix" {
        BackupWorker.workName(configId) shouldBe "${BackupWorker.WORK_NAME_PREFIX}$configId"
        BackupWorker.oneTimeWorkName(configId) shouldBe "${BackupWorker.ONE_TIME_WORK_NAME_PREFIX}$configId"
        BackupWorker.chargingFallbackWorkName(configId) shouldBe
            "${BackupWorker.CHARGING_FALLBACK_WORK_NAME_PREFIX}$configId"
    }

    "distinct config ids never collide within the same name kind" {
        BackupWorker.oneTimeWorkName("a") shouldStartWith BackupWorker.ONE_TIME_WORK_NAME_PREFIX
        (BackupWorker.oneTimeWorkName("a") == BackupWorker.oneTimeWorkName("b")) shouldBe false
    }
})
