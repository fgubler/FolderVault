package ch.abwesend.foldervault.domain.restore

/**
 * Cooperative stop signal for a running restore, mirroring `BackupRunControl` for the backup
 * pipeline.
 *
 * A restore must **not** be stopped by cancelling its coroutine: `withContext` discards the
 * value a cancelled block returns and throws instead, so the engine could never report what it
 * had already restored — the old `RestoreResult.Cancelled` return was unreachable for exactly
 * that reason. The engine polls [shouldStop] instead and returns [RestoreResult.Cancelled]
 * normally.
 *
 * Where it is polled differs by flow. A folder restore checks at each file boundary and reports
 * the counts of what it already restored. A single file has no boundary, so the check rides the
 * source stream and fires every few megabytes; it reports zero counts, because a partly written
 * plaintext file is deleted rather than left to pass for a whole one.
 *
 * One instance is shared between the UI (which requests the stop) and whichever host executes the
 * run (the foreground service or the coordinator's own scope), so [stopRequested] is `@Volatile`.
 */
class RestoreRunControl {
    @Volatile
    private var stopRequested = false

    /** Requests a cooperative stop at the next file boundary. Idempotent. */
    fun requestStop() {
        stopRequested = true
    }

    /** True once [requestStop] was called. */
    fun shouldStop(): Boolean = stopRequested

    /** Clears the stop flag so the same instance can host the next run. */
    fun reset() {
        stopRequested = false
    }
}
