package ch.abwesend.foldervault.infrastructure.backup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pins down the handover decision of [ForegroundHandoverPolicy]: every stop except an explicit
 * user stop hands the remaining work over to a WorkManager continuation.
 */
class ForegroundHandoverPolicyTest : StringSpec({

    "own time budget (no explicit stop reason) schedules a continuation" {
        ForegroundHandoverPolicy.shouldScheduleContinuation(reason = null) shouldBe true
    }

    "network-policy violation schedules a continuation" {
        ForegroundHandoverPolicy.shouldScheduleContinuation(
            ForegroundStopReason.NETWORK_POLICY_VIOLATED
        ) shouldBe true
    }

    "OS timeout schedules a continuation" {
        ForegroundHandoverPolicy.shouldScheduleContinuation(ForegroundStopReason.OS_TIMEOUT) shouldBe true
    }

    "explicit user stop does NOT schedule a continuation" {
        ForegroundHandoverPolicy.shouldScheduleContinuation(ForegroundStopReason.USER_REQUESTED) shouldBe false
    }
})
