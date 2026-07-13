package ch.abwesend.foldervault.infrastructure.backup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-process registry of backup configs currently running inside the foreground service.
 *
 * WorkManager-run backups are visible to the UI through `getWorkInfosForUniqueWorkFlow`, but a
 * service run has no WorkInfo — this singleton is the equivalent signal, merged into
 * [BackupScheduler.observeIsRunning] so the UI cannot tell (and need not care) which host is
 * executing the run. Purely in-memory by design: a process death ends the service run anyway,
 * so there is no stale state to persist or sweep.
 */
class ForegroundRunState {
    private val runningConfigIds = MutableStateFlow<Set<String>>(emptySet())

    /** Marks a config as running in the foreground service. */
    fun markRunning(configId: String) {
        runningConfigIds.update { it + configId }
    }

    /** Marks a config's foreground-service run as finished. Safe to call when not running. */
    fun markStopped(configId: String) {
        runningConfigIds.update { it - configId }
    }

    /** Whether a foreground-service run for [configId] is currently executing. */
    fun isRunning(configId: String): Boolean = configId in runningConfigIds.value

    /** Observes whether a foreground-service run for [configId] is currently executing. */
    fun observeIsRunning(configId: String): Flow<Boolean> =
        runningConfigIds.map { configId in it }.distinctUntilChanged()
}
