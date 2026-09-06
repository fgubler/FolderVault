package ch.abwesend.foldervault.infrastructure.cloud.googledrive

import ch.abwesend.foldervault.domain.cloud.CloudNetworkUnavailableException
import ch.abwesend.foldervault.domain.cloud.CloudPermanentException
import ch.abwesend.foldervault.domain.cloud.CloudRateLimitException
import ch.abwesend.foldervault.domain.cloud.CloudTransientException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Covers how many attempts [DriveRetryPolicy] is willing to spend on a failure.
 *
 * `runTest` skips the backoff `delay`s, so these assert the attempt *counts* rather than wall
 * time — which is the part that matters: a "no usable network" failure must not burn the full
 * transient budget (~62 s per call) inside the foreground service's dataSync budget when waiting
 * for connectivity is WorkManager's job.
 */
class DriveRetryPolicyTest : StringSpec({

    "a successful call runs exactly once" {
        var attempts = 0
        var result: String? = null
        runTest {
            result = DriveRetryPolicy.withRetry(label = "test") {
                attempts++
                "ok"
            }
        }
        result shouldBe "ok"
        attempts shouldBe 1
    }

    "a transient failure is retried up to the full budget" {
        var attempts = 0
        runTest {
            shouldThrow<CloudTransientException> {
                DriveRetryPolicy.withRetry(label = "test") {
                    attempts++
                    throw CloudTransientException("boom")
                }
            }
        }
        attempts shouldBe DriveRetryPolicy.MAX_RETRIES + 1
    }

    "a rate-limit failure is retried up to the full budget" {
        var attempts = 0
        runTest {
            shouldThrow<CloudRateLimitException> {
                DriveRetryPolicy.withRetry(label = "test") {
                    attempts++
                    throw CloudRateLimitException()
                }
            }
        }
        attempts shouldBe DriveRetryPolicy.MAX_RETRIES + 1
    }

    "a missing network gets only the small network budget, not the transient one" {
        var attempts = 0
        runTest {
            shouldThrow<CloudNetworkUnavailableException> {
                DriveRetryPolicy.withRetry(label = "test") {
                    attempts++
                    throw CloudNetworkUnavailableException()
                }
            }
        }
        attempts shouldBe DriveRetryPolicy.MAX_NETWORK_UNAVAILABLE_RETRIES + 1
        // The point of the whole exercise: far fewer attempts than an ordinary transient error.
        (attempts < DriveRetryPolicy.MAX_RETRIES + 1) shouldBe true
    }

    "a missing network that clears on the retry still succeeds" {
        var attempts = 0
        var result: String? = null
        runTest {
            result = DriveRetryPolicy.withRetry(label = "test") {
                attempts++
                if (attempts == 1) throw CloudNetworkUnavailableException()
                "recovered"
            }
        }
        result shouldBe "recovered"
        attempts shouldBe 2
    }

    // The cap is decided per attempt from the *current* failure, so a run that starts out merely
    // transient and then loses the network entirely stops early rather than finishing the long
    // budget it began with.
    "losing the network mid-retry shortens the remaining budget" {
        var attempts = 0
        runTest {
            shouldThrow<CloudNetworkUnavailableException> {
                DriveRetryPolicy.withRetry(label = "test") {
                    attempts++
                    if (attempts == 1) throw CloudTransientException("blip")
                    throw CloudNetworkUnavailableException()
                }
            }
        }
        attempts shouldBe 2
    }

    "a permanent failure is not retried at all" {
        var attempts = 0
        runTest {
            shouldThrow<CloudPermanentException> {
                DriveRetryPolicy.withRetry(label = "test") {
                    attempts++
                    throw CloudPermanentException("nope")
                }
            }
        }
        attempts shouldBe 1
    }

    "the verify hook short-circuits a retry when the first attempt actually succeeded" {
        var attempts = 0
        var result: String? = null
        runTest {
            result = DriveRetryPolicy.withRetry(
                label = "test",
                verifyAlreadySucceeded = { "found-server-side" },
            ) {
                attempts++
                throw CloudTransientException("lost response")
            }
        }
        result shouldBe "found-server-side"
        attempts shouldBe 1
    }
})
