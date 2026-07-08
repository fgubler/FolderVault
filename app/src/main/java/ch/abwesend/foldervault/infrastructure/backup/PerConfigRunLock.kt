package ch.abwesend.foldervault.infrastructure.backup

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide, per-key locks that serialize backup runs of the same config. Since one-time and
 * periodic work live under distinct WorkManager unique-work names, WorkManager no longer prevents
 * a manual "back up now" from executing concurrently with a periodic run of the same config. Both
 * workers run in this app process against the same [BackupRunner] singleton, so a process-wide
 * [Mutex] per configId restores the original "never run the same backup twice at once" guarantee.
 *
 * A second caller does not wait for the lock — blocking would silently consume the second
 * worker's OS execution window while its deadline keeps ticking; it gets [withLockOrElse]'s
 * `onBusy` result immediately instead.
 */
internal class PerConfigRunLock {

    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Runs [block] while holding the lock for [key], or returns [onBusy]'s result immediately
     * when another caller currently holds that lock. The lock is released on normal completion,
     * cancellation, and error alike (the `finally` unlocks even when [block] throws).
     */
    suspend fun <T> withLockOrElse(key: String, onBusy: () -> T, block: suspend () -> T): T {
        val lock = locks.computeIfAbsent(key) { Mutex() }
        return if (lock.tryLock()) {
            try {
                block()
            } finally {
                lock.unlock()
            }
        } else {
            onBusy()
        }
    }
}
