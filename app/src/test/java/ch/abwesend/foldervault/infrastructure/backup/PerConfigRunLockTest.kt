package ch.abwesend.foldervault.infrastructure.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Pins down the "never run the same backup twice at once" guarantee of [PerConfigRunLock]:
 * a concurrent call for the same key skips (returns the busy result) instead of waiting, and
 * the lock is released again after normal completion, an exception, and cancellation alike.
 */
class PerConfigRunLockTest : StringSpec({

    isolationMode = IsolationMode.InstancePerTest

    "second concurrent call for the same key returns the busy result without running the block" {
        runTest {
            val lock = PerConfigRunLock()
            val firstIsRunning = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()

            val first = async {
                lock.withLockOrElse("cfg-1", onBusy = { "busy" }) {
                    firstIsRunning.complete(Unit)
                    releaseFirst.await()
                    "ran"
                }
            }
            firstIsRunning.await()

            var secondBlockExecuted = false
            val second = lock.withLockOrElse("cfg-1", onBusy = { "busy" }) {
                secondBlockExecuted = true
                "ran"
            }

            second shouldBe "busy"
            secondBlockExecuted shouldBe false
            releaseFirst.complete(Unit)
            first.await() shouldBe "ran"
        }
    }

    "different keys do not block each other" {
        runTest {
            val lock = PerConfigRunLock()
            val firstIsRunning = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()

            val first = async {
                lock.withLockOrElse("cfg-1", onBusy = { "busy" }) {
                    firstIsRunning.complete(Unit)
                    releaseFirst.await()
                    "ran"
                }
            }
            firstIsRunning.await()

            lock.withLockOrElse("cfg-2", onBusy = { "busy" }) { "ran" } shouldBe "ran"

            releaseFirst.complete(Unit)
            first.await() shouldBe "ran"
        }
    }

    "the lock is released after normal completion" {
        runTest {
            val lock = PerConfigRunLock()
            lock.withLockOrElse("cfg-1", onBusy = { "busy" }) { "first" } shouldBe "first"
            lock.withLockOrElse("cfg-1", onBusy = { "busy" }) { "second" } shouldBe "second"
        }
    }

    "a throwing block releases the lock for the next call" {
        runTest {
            val lock = PerConfigRunLock()

            shouldThrow<IllegalStateException> {
                lock.withLockOrElse("cfg-1", onBusy = { "busy" }) {
                    throw IllegalStateException("boom")
                }
            }

            lock.withLockOrElse("cfg-1", onBusy = { "busy" }) { "ran" } shouldBe "ran"
        }
    }

    "a cancelled block releases the lock for the next call" {
        runTest {
            val lock = PerConfigRunLock()
            val firstIsRunning = CompletableDeferred<Unit>()

            val job = launch {
                lock.withLockOrElse("cfg-1", onBusy = { "busy" }) {
                    firstIsRunning.complete(Unit)
                    awaitCancellation()
                }
            }
            firstIsRunning.await()
            job.cancelAndJoin()

            lock.withLockOrElse("cfg-1", onBusy = { "busy" }) { "ran" } shouldBe "ran"
        }
    }
})
