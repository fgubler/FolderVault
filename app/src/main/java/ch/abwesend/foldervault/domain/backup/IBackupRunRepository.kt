package ch.abwesend.foldervault.domain.backup

import kotlinx.coroutines.flow.Flow

interface IBackupRunRepository {
    /** Most recent runs first, capped at [limit]. */
    fun observeByConfig(backupConfigId: String, limit: Int = DEFAULT_LIMIT): Flow<List<BackupRun>>

    companion object {
        const val DEFAULT_LIMIT: Int = 100
    }
}
