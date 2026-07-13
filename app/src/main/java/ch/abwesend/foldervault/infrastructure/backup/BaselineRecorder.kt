package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.UploadedFileIndexEntity

/**
 * Runs the baseline pass of a `syncLaterChangesOnly` config: records every file currently in
 * the source tree as a baseline row in UploadedFileIndex — nothing is uploaded, so the pass
 * needs no cloud provider (and therefore works offline). Once
 * [BackupConfigDao.updateBaselineCompleted] marks the walk complete, subsequent runs use the
 * normal upload pipeline, whose change detection treats baseline rows as "never uploaded"
 * (see [ChangeDetector]).
 *
 * Interruption-safe: a cooperative stop leaves `baselineCompletedAt` NULL so the next run
 * resumes the pass; already-indexed paths are skipped rather than re-recorded, preserving the
 * originally captured mtime/size — a file modified between the interrupted pass and the resume
 * is then correctly detected as changed on the first incremental run.
 *
 * Only files that existed when the config was created are baselined: `recordFile` uses the
 * config's `createdAt` as the cutoff and leaves any file modified after it unindexed. The
 * resume gap of an interrupted pass can span days, so a file *added* during that gap would
 * otherwise be baselined on resume and silently excluded from backup forever — instead it stays
 * unindexed and the first incremental run uploads it as NEW. Files with an unusable mtime are
 * always baselined, staying conservative toward the "pre-existing files are never uploaded"
 * promise.
 */
internal class BaselineRecorder(
    private val fileScanner: ILocalFileScanner,
    private val uploadedFileIndexDao: UploadedFileIndexDao,
    private val backupConfigDao: BackupConfigDao,
) {
    private val log get() = logger

    suspend fun recordBaseline(config: BackupConfigEntity, summary: RunSummary, control: BackupRunControl?) {
        val files = fileScanner.scan(config, summary)
        summary.totalFilesDiscovered = files.size
        control?.reportFilesDiscovered(files.size)

        if (!summary.sourceFolderInaccessible) {
            // Files present when the config was created define the baseline; anything modified or
            // added afterwards (a resume gap of an interrupted pass can span days) is left for the
            // first incremental run to upload instead of being wrongly baselined.
            val cutoff = config.createdAt
            for (file in files) {
                if (control?.shouldStop() == true) {
                    summary.hitTimeBudget = true
                    break
                }
                if (recordFile(config.id, file, cutoff)) {
                    summary.filesSkipped++
                }
            }
            if (!summary.hitTimeBudget) {
                backupConfigDao.updateBaselineCompleted(config.id, System.currentTimeMillis())
                log.info("Baseline recorded for config ${config.id}: ${summary.filesSkipped} files")
            }
        }
    }

    /**
     * Records [file] as a baseline row unless it was added or modified after [cutoff] (the
     * config's creation instant). A file with a usable mtime newer than [cutoff] — typically one
     * added during the resume gap of an interrupted pass — is deliberately left unindexed so the
     * first incremental run uploads it as NEW, rather than being silently excluded from backup
     * forever. Files with an unusable mtime are always baselined (conservative toward the
     * "pre-existing files are never uploaded" promise). Paths already indexed by an earlier pass
     * keep their originally captured metadata.
     *
     * @return true when the file is covered by the baseline (recorded now or already indexed);
     *   false when it is deliberately left for the incremental upload.
     */
    private suspend fun recordFile(configId: String, file: LocalFileInfo, cutoff: Long): Boolean {
        val alreadyIndexed = uploadedFileIndexDao.getCurrentVersion(configId, file.relativePath) != null
        val mtime = file.mtime
        val addedAfterCutoff = mtime != null && mtime > cutoff
        return when {
            alreadyIndexed -> true
            addedAfterCutoff -> false
            else -> {
                uploadedFileIndexDao.upsertCurrentVersion(
                    UploadedFileIndexEntity(
                        backupConfigId = configId,
                        relativePath = file.relativePath,
                        localLastModified = file.mtime ?: 0L,
                        localSize = file.size,
                        cloudFileId = "",
                        remoteName = "",
                        uploadedAt = System.currentTimeMillis(),
                        isCurrentVersion = true,
                        isBaseline = true,
                    )
                )
                true
            }
        }
    }
}
