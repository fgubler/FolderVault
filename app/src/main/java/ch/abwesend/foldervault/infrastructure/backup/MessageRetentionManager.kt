package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao

class MessageRetentionManager(private val dao: BackupMessageDao) {
    companion object {
        const val KEEP_COUNT = 200
        private const val INFO_WARNING_MAX_AGE_DAYS = 30L
        private const val MS_PER_DAY = 24L * 60 * 60 * 1000
    }

    suspend fun prune(configId: String) {
        val cutoff = System.currentTimeMillis() - INFO_WARNING_MAX_AGE_DAYS * MS_PER_DAY
        dao.pruneOldInfoWarning(configId, cutoff)
        dao.pruneOldestOverLimit(configId, KEEP_COUNT)
    }
}
