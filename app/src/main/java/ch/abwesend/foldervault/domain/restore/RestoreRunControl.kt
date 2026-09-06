package ch.abwesend.foldervault.domain.restore

/**
 * Cooperative stop signal for a running folder restore, mirroring `BackupRunControl` for the
 * backup pipeline.
 *
 * A restore must **not** be stopped by cancelling its coroutine: `withContext` discards the
 * value a cancelled block returns and throws instead, so the engine could never report what it
 * had already restored — the old `RestoreResult.Cancelled` return was unreachable for exactly
 * that reason. The engine polls [shouldStop] at each file boundary instead and returns
 * [RestoreResult.Cancelled] normally, carrying the partial counts.
 *
 * One instance is shared between the UI (which requests the stop) and whichever host executes
 * the run (foreground service or ViewModel scope), so [stopRequested] is `@Volatile`.
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
