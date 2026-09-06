package ch.abwesend.foldervault.domain.restore

/**
 * Starts the restore foreground service so a long folder restore survives the user leaving the
 * app. Deliberately separate from `IForegroundBackupLauncher`: a restore has no backup config, no
 * network policy and — unlike a backup — no WorkManager fallback, because it depends on
 * session-scoped picked tree uris and a password held only in memory.
 */
interface IForegroundRestoreLauncher {
    /**
     * Starts the service for the request already staged in the run coordinator.
     *
     * @return `true` when the service was started and now owns the run; `false` when the OS
     *   refused the foreground start (most commonly Android 15's dataSync time budget, shared
     *   with the backup service, being exhausted). The caller must then run the restore itself
     *   and tell the user to keep the app open.
     */
    fun start(): Boolean
}
