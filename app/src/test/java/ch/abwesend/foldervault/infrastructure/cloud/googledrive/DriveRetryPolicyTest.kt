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
            val result = DriveRetryPolicy.withRetry { calls++; "ok" }
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
                    throw IllegalStateException("bug")
                }
            }
            calls shouldBeExactly 1
        }
    }
})
