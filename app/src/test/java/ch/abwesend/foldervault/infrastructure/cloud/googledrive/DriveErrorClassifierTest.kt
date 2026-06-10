package ch.abwesend.folderVault.infrastructure.cloud.googledrive

import ch.abwesend.foldervault.domain.cloud.CloudAuthException
import ch.abwesend.foldervault.domain.cloud.CloudNotFoundException
import ch.abwesend.foldervault.domain.cloud.CloudQuotaExceededException
import ch.abwesend.foldervault.domain.cloud.CloudRateLimitException
import ch.abwesend.foldervault.domain.cloud.CloudTransientException
import ch.abwesend.foldervault.infrastructure.cloud.googledrive.DriveErrorClassifier
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException

class DriveErrorClassifierTest : StringSpec({

    // ── Status-code driven ────────────────────────────────────────────────────

    "401 → CloudAuthException" {
        DriveErrorClassifier.classifyByCodeAndReason(401, "", RuntimeException())
            .shouldBeInstanceOf<CloudAuthException>()
    }

    "404 → CloudNotFoundException" {
        DriveErrorClassifier.classifyByCodeAndReason(404, "", RuntimeException())
            .shouldBeInstanceOf<CloudNotFoundException>()
    }

    "429 → CloudRateLimitException" {
        DriveErrorClassifier.classifyByCodeAndReason(429, "", RuntimeException())
            .shouldBeInstanceOf<CloudRateLimitException>()
    }

    "500 → CloudTransientException" {
        DriveErrorClassifier.classifyByCodeAndReason(500, "", RuntimeException())
            .shouldBeInstanceOf<CloudTransientException>()
    }

    "503 → CloudTransientException" {
        DriveErrorClassifier.classifyByCodeAndReason(503, "", RuntimeException())
            .shouldBeInstanceOf<CloudTransientException>()
    }

    "200 with unknown reason → CloudTransientException" {
        DriveErrorClassifier.classifyByCodeAndReason(200, "somethingElse", RuntimeException())
            .shouldBeInstanceOf<CloudTransientException>()
    }

    // ── Reason string driven ──────────────────────────────────────────────────

    "rateLimitExceeded reason → CloudRateLimitException" {
        DriveErrorClassifier.classifyByCodeAndReason(403, "rateLimitExceeded", RuntimeException())
            .shouldBeInstanceOf<CloudRateLimitException>()
    }

    "userRateLimitExceeded reason → CloudRateLimitException" {
        DriveErrorClassifier.classifyByCodeAndReason(403, "userRateLimitExceeded", RuntimeException())
            .shouldBeInstanceOf<CloudRateLimitException>()
    }

    "storageQuotaExceeded reason → CloudQuotaExceededException" {
        DriveErrorClassifier.classifyByCodeAndReason(403, "storageQuotaExceeded", RuntimeException())
            .shouldBeInstanceOf<CloudQuotaExceededException>()
    }

    "reason matching is case-insensitive" {
        DriveErrorClassifier.classifyByCodeAndReason(403, "StorageQuotaExceeded", RuntimeException())
            .shouldBeInstanceOf<CloudQuotaExceededException>()
    }

    // ── classify() passthrough and IO ─────────────────────────────────────────

    "classify passes through already-typed CloudException unchanged" {
        val original = CloudAuthException()
        DriveErrorClassifier.classify(original) shouldBeInstanceOf CloudAuthException::class
    }

    "classify wraps IOException as CloudTransientException" {
        DriveErrorClassifier.classify(IOException("network blip"))
            .shouldBeInstanceOf<CloudTransientException>()
    }

    "classify wraps generic RuntimeException as CloudTransientException" {
        DriveErrorClassifier.classify(RuntimeException("unexpected"))
            .shouldBeInstanceOf<CloudTransientException>()
    }
})
