package ch.abwesend.foldervault.infrastructure.cloud.googledrive

import ch.abwesend.foldervault.domain.cloud.CloudAuthException
import ch.abwesend.foldervault.domain.cloud.CloudNetworkUnavailableException
import ch.abwesend.foldervault.domain.cloud.CloudNotFoundException
import ch.abwesend.foldervault.domain.cloud.CloudPermanentException
import ch.abwesend.foldervault.domain.cloud.CloudQuotaExceededException
import ch.abwesend.foldervault.domain.cloud.CloudRateLimitException
import ch.abwesend.foldervault.domain.cloud.CloudTransientException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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

    // ── 403 permission handling (SEC-5) ───────────────────────────────────────

    listOf(
        "insufficientPermissions",
        "appNotAuthorizedToFile",
        "forbidden",
        "accessNotConfigured",
    ).forEach { reason ->
        "403 $reason → CloudAuthException" {
            DriveErrorClassifier.classifyByCodeAndReason(403, reason, RuntimeException())
                .shouldBeInstanceOf<CloudAuthException>()
        }
    }

    "403 permission reason matching is case-insensitive" {
        DriveErrorClassifier.classifyByCodeAndReason(403, "InsufficientPermissions", RuntimeException())
            .shouldBeInstanceOf<CloudAuthException>()
    }

    "403 with an unrecognised reason → CloudPermanentException (not retried)" {
        DriveErrorClassifier.classifyByCodeAndReason(403, "somethingUnknown", RuntimeException())
            .shouldBeInstanceOf<CloudPermanentException>()
    }

    // ── Other 4xx are permanent, not transient (SEC-5) ────────────────────────

    "400 badRequest → CloudPermanentException" {
        DriveErrorClassifier.classifyByCodeAndReason(400, "badRequest", RuntimeException())
            .shouldBeInstanceOf<CloudPermanentException>()
    }

    "405 → CloudPermanentException" {
        DriveErrorClassifier.classifyByCodeAndReason(405, "", RuntimeException())
            .shouldBeInstanceOf<CloudPermanentException>()
    }

    "409 conflict → CloudPermanentException" {
        DriveErrorClassifier.classifyByCodeAndReason(409, "conflict", RuntimeException())
            .shouldBeInstanceOf<CloudPermanentException>()
    }

    // ── classify() passthrough and IO ─────────────────────────────────────────

    "classify passes through already-typed CloudException unchanged" {
        val original = CloudAuthException()
        DriveErrorClassifier.classify(original).shouldBeInstanceOf<CloudAuthException>()
    }

    "classify wraps IOException as CloudTransientException" {
        DriveErrorClassifier.classify(IOException("network blip"))
            .shouldBeInstanceOf<CloudTransientException>()
    }

    "classify wraps UnknownHostException as CloudNetworkUnavailableException" {
        DriveErrorClassifier.classify(UnknownHostException("Unable to resolve host www.googleapis.com"))
            .shouldBeInstanceOf<CloudNetworkUnavailableException>()
    }

    "classify wraps ConnectException as CloudNetworkUnavailableException" {
        DriveErrorClassifier.classify(ConnectException("failed to connect (Network is unreachable)"))
            .shouldBeInstanceOf<CloudNetworkUnavailableException>()
    }

    "classify wraps NoRouteToHostException as CloudNetworkUnavailableException" {
        DriveErrorClassifier.classify(NoRouteToHostException("No route to host"))
            .shouldBeInstanceOf<CloudNetworkUnavailableException>()
    }

    // A bare SocketException is what the platform throws for a mid-transfer failure on a network
    // that is working — treating it as "no network" would abort the whole run and skip every
    // remaining file, where the right handling is to fail this one file and carry on.
    "classify keeps a mid-transfer SocketException as a generic transient error" {
        val classified = DriveErrorClassifier.classify(SocketException("Connection reset by peer"))
        classified.shouldBeInstanceOf<CloudTransientException>()
        (classified is CloudNetworkUnavailableException) shouldBe false
    }

    "classify keeps a SocketTimeoutException as a generic transient error" {
        val classified = DriveErrorClassifier.classify(SocketTimeoutException("timeout"))
        classified.shouldBeInstanceOf<CloudTransientException>()
        (classified is CloudNetworkUnavailableException) shouldBe false
    }

    "classify detects a nested UnknownHostException cause as network-unavailable" {
        val nested = IOException("wrapper", UnknownHostException("no address"))
        DriveErrorClassifier.classify(nested)
            .shouldBeInstanceOf<CloudNetworkUnavailableException>()
    }

    "classifyIoException keeps a plain IOException as a generic transient error" {
        val classified = DriveErrorClassifier.classifyIoException(IOException("stream closed"))
        classified.shouldBeInstanceOf<CloudTransientException>()
        (classified is CloudNetworkUnavailableException) shouldBe false
    }

    "classify wraps generic RuntimeException as CloudTransientException" {
        DriveErrorClassifier.classify(RuntimeException("unexpected"))
            .shouldBeInstanceOf<CloudTransientException>()
    }
})
