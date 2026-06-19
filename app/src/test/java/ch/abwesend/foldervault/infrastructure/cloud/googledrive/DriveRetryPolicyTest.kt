@file:Suppress("NoUnusedImports") // runTest is needed: DriveRetryPolicy.withRetry uses delay()

package ch.abwesend.folderVault.infrastructure.cloud.googledrive

import ch.abwesend.foldervault.domain.cloud.CloudAuthException
import ch.abwesend.foldervault.domain.cloud.CloudRateLimitException
import ch.abwesend.foldervault.domain.cloud.CloudTransientException
import ch.abwesend.foldervault.infrastructure.cloud.googledrive.DriveRetryPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class DriveRetryPolicyTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    "succeeds immediately on first attempt" {
        runTest {
            var calls = 0
            val result = DriveRetryPolicy.withRetry {
                calls++
                "ok"
            }
            result shouldBe "ok"
            calls shouldBeExactly 1
        }
    }

    "retries on CloudTransientException and succeeds on second attempt" {
        runTest {
            var calls = 0
            val result = DriveRetryPolicy.withRetry {
                calls++
                if (calls == 1) throw CloudTransientException()
                "recovered"
            }
            result shouldBe "recovered"
            calls shouldBeExactly 2
        }
    }

    "retries on CloudRateLimitException and succeeds eventually" {
        runTest {
            var calls = 0
            val result = DriveRetryPolicy.withRetry {
                calls++
                if (calls < 3) throw CloudRateLimitException()
                "done"
            }
            result shouldBe "done"
            calls shouldBeExactly 3
        }
    }

    "exhausts all retries and rethrows CloudTransientException" {
        runTest {
            var calls = 0
            val thrown = shouldThrow<CloudTransientException> {
                DriveRetryPolicy.withRetry {
                    calls++
                    throw CloudTransientException("always fails")
                }
            }
            calls shouldBeExactly DriveRetryPolicy.MAX_RETRIES + 1
            thrown.message shouldBe "always fails"
        }
    }

    "does not retry on CloudAuthException — propagates immediately" {
        runTest {
            var calls = 0
            shouldThrow<CloudAuthException> {
                DriveRetryPolicy.withRetry {
                    calls++
                    throw CloudAuthException()
                }
            }
            calls shouldBeExactly 1
        }
    }

    "does not retry on arbitrary exceptions — propagates immediately" {
        runTest {
            var calls = 0
            shouldThrow<IllegalStateException> {
                DriveRetryPolicy.withRetry {
                    calls++
                    error("bug")
                }
            }
            calls shouldBeExactly 1
        }
    }

    "verifyAlreadySucceeded is NOT invoked on the first attempt" {
        runTest {
            var verifyCalls = 0
            DriveRetryPolicy.withRetry(
                verifyAlreadySucceeded = {
                    verifyCalls++
                    null
                },
            ) { "ok" }
            verifyCalls shouldBeExactly 0
        }
    }

    "verifyAlreadySucceeded returning non-null on retry short-circuits the block" {
        runTest {
            var blockCalls = 0
            var verifyCalls = 0
            val result = DriveRetryPolicy.withRetry(
                verifyAlreadySucceeded = {
                    verifyCalls++
                    "from-verify"
                },
            ) {
                blockCalls++
                if (blockCalls == 1) throw CloudTransientException()
                "from-block-unreached"
            }
            result shouldBe "from-verify"
            blockCalls shouldBeExactly 1
            verifyCalls shouldBeExactly 1
        }
    }

    "verifyAlreadySucceeded returning null lets the retry proceed" {
        runTest {
            var blockCalls = 0
            val result = DriveRetryPolicy.withRetry(verifyAlreadySucceeded = { null }) {
                blockCalls++
                if (blockCalls == 1) throw CloudTransientException()
                "recovered"
            }
            result shouldBe "recovered"
            blockCalls shouldBeExactly 2
        }
    }

    "verifyAlreadySucceeded throwing does not derail the retry" {
        runTest {
            var blockCalls = 0
            val result = DriveRetryPolicy.withRetry(
                verifyAlreadySucceeded = { error("probe broke") },
            ) {
                blockCalls++
                if (blockCalls == 1) throw CloudTransientException()
                "recovered"
            }
            result shouldBe "recovered"
            blockCalls shouldBeExactly 2
        }
    }
})
