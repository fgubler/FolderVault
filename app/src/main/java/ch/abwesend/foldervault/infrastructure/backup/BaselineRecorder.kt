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
            for (file in files) {
                if (control?.shouldStop() == true) {
                    summary.hitTimeBudget = true
                    break
                }
                recordFile(config.id, file)
                summary.filesSkipped++
            }
            if (!summary.hitTimeBudget) {
                backupConfigDao.updateBaselineCompleted(config.id, System.currentTimeMillis())
                log.info("Baseline recorded for config ${config.id}: ${summary.filesSkipped} files")
            }
        }
    }

    private suspend fun recordFile(configId: String, file: LocalFileInfo) {
        val alreadyIndexed = uploadedFileIndexDao.getCurrentVersion(configId, file.relativePath) != null
        if (!alreadyIndexed) {
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
        }
    }
}
