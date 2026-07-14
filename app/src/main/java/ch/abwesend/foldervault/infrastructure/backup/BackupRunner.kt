package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.backup.CloudManifest
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.backup.ManifestEntry
import ch.abwesend.foldervault.domain.cloud.CloudAuthException
import ch.abwesend.foldervault.domain.cloud.CloudAuthResult
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.result.ifError
import ch.abwesend.foldervault.domain.result.runCatchingAsResult
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupRunDao
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupRunEntity
import ch.abwesend.foldervault.infrastructure.room.entity.UploadedFileIndexEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKey

sealed class RunResult {
    /**
     * A run that actually executed and committed a run row — only these results carry a real
     * [runId] and [summary]. Kept as an intermediate type so callers must prove (via a type
     * check the compiler enforces) that a run happened before touching either member.
     */
    sealed class Completed : RunResult() {
        abstract val runId: String
        abstract val summary: RunSummary
    }

    data class Success(override val summary: RunSummary, override val runId: String) : Completed()
    data class AuthLost(override val summary: RunSummary, override val runId: String) : Completed()
    data class FatalError(
        val error: Exception,
        override val summary: RunSummary,
        override val runId: String,
    ) : Completed()

    /**
     * Another run of the same config was already executing, so this one did nothing — no run row
     * was created, hence no runId or summary exist. The caller should retry once the in-flight
     * run has finished (e.g. via [androidx.work.ListenableWorker.Result.retry]) rather than
     * block: waiting inside a worker would burn its OS execution window while its deadline keeps
     * ticking.
     */
    data object SkippedConcurrentRun : RunResult()
}

/**
 * Maps the terminal [summary] flags to the persisted [BackupRunStatus]. Extracted as a pure
 * function so the outcome of each combination — in particular that an inaccessible source folder
 * fails the run rather than reporting a silent, up-to-date success (BUG-4) — is unit-testable
 * without the Android/SAF machinery a full run needs.
 *
 * Order matters: the first matching branch wins, from most to least severe.
 */
internal fun resolveRunStatus(summary: RunSummary): BackupRunStatus = when {
    summary.authLost -> BackupRunStatus.FAILED
    summary.sourceFolderInaccessible -> BackupRunStatus.FAILED
    summary.hitTimeBudget -> BackupRunStatus.INITIAL_SYNC_IN_PROGRESS
    summary.quotaExceeded && summary.filesUploaded == 0 -> BackupRunStatus.FAILED
    summary.quotaExceeded || summary.filesFailed > 0 -> BackupRunStatus.COMPLETED_WITH_WARNINGS
    else -> BackupRunStatus.UP_TO_DATE
}

/**
 * How a run's terminal state changes the cross-run progress counters (`totalFilesDiscovered` /
 * `filesUploadedTotal`) that [ch.abwesend.foldervault.domain.backup.StartManualBackupUseCase]
 * reads to keep routing an incomplete initial sync to the foreground service.
 */
internal enum class CrossRunProgress {
    /**
     * The run made progress but did not finish the sync: a cooperative time-budget stop
     * ([RunSummary.hitTimeBudget]) or a hard cancellation ([BackupRunStatus.CANCELLED]). Keep the
     * discovered total and accumulate the uploaded count so the sync resumes on the right host.
     */
    PERSIST,

    /** The run completed normally — the sync is done, so the counters clear to zero. */
    RESET,

    /** A failed / quota-exceeded run — leave whatever the last progressing run recorded. */
    UNCHANGED,
}

/**
 * Decides the [CrossRunProgress] transition for a finished run. Extracted as a pure function so the
 * [BackupRunStatus.CANCELLED] case in particular — a hard cancellation (e.g. the OS killing a
 * background worker mid-upload) must remember its progress, otherwise the next run falls back to
 * WorkManager's short windows instead of the foreground service — is unit-testable without the full
 * run pipeline. Order matters: [CrossRunProgress.PERSIST] is checked before [CrossRunProgress.RESET]
 * because a cancelled run never completes normally anyway.
 */
internal fun resolveCrossRunProgress(
    status: BackupRunStatus,
    summary: RunSummary,
    completedNormally: Boolean,
): CrossRunProgress = when {
    summary.hitTimeBudget || status == BackupRunStatus.CANCELLED -> CrossRunProgress.PERSIST
    completedNormally -> CrossRunProgress.RESET
    else -> CrossRunProgress.UNCHANGED
}

/**
 * Maps the current index rows to cloud-manifest entries. Baseline rows are excluded — they
 * describe files that were deliberately never uploaded, so nothing exists in the cloud for them.
 */
internal fun buildManifestEntries(rows: List<UploadedFileIndexEntity>): List<ManifestEntry> =
    rows.filterNot { it.isBaseline }.map { entry ->
        ManifestEntry(
            relativePath = entry.relativePath,
            mtime = entry.localLastModified,
            size = entry.localSize,
            cloudFileId = entry.cloudFileId,
            remoteName = entry.remoteName,
        )
    }

class BackupRunner internal constructor(
    private val context: Context,
    private val authorizer: ICloudAuthorizer,
    private val cipher: IFvc1Cipher,
    private val encryptionRepository: IEncryptionRepository,
    private val backupConfigDao: BackupConfigDao,
    private val uploadedFileIndexDao: UploadedFileIndexDao,
    private val backupMessageDao: BackupMessageDao,
    private val backupRunDao: BackupRunDao,
    private val settingsRepository: IAppSettingsRepository,
    private val dispatchers: IDispatchers,
    private val scheduler: IBackupScheduler,
    /**
     * The SAF source-tree walker, shared by the upload analyzer and the baseline recorder.
     * Injectable so the baseline pass can be driven with a fake in a run-level test without the
     * Android/SAF machinery; production uses the default [LocalFileScanner].
     */
    private val fileScanner: ILocalFileScanner = LocalFileScanner(context, dispatchers),
) {
    private val log get() = logger

    /** Serializes runs of the same config — see [PerConfigRunLock] for the full rationale. */
    private val runLock = PerConfigRunLock()

    /**
     * Runs a backup for [configId], serialized per config: only one run per config executes at a
     * time (see [PerConfigRunLock]). When another run of the same config is already executing,
     * this returns [RunResult.SkippedConcurrentRun] immediately instead of waiting — the caller
     * retries later with a fresh deadline and execution window.
     */
    suspend fun runBackup(configId: String, control: BackupRunControl? = null): RunResult =
        runLock.withLockOrElse(
            key = configId,
            onBusy = {
                log.info("Backup for config $configId is already running — skipping this concurrent run")
                RunResult.SkippedConcurrentRun
            },
        ) {
            runBackupExclusive(configId, control)
        }

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    private suspend fun runBackupExclusive(configId: String, control: BackupRunControl?): RunResult {
        val runId = UUID.randomUUID().toString()
        val config = backupConfigDao.getByIdOnce(configId)
            ?: return RunResult.FatalError(
                IllegalStateException("Backup config $configId not found"),
                RunSummary(),
                runId,
            )

        val summary = RunSummary()
        val startedAt = System.currentTimeMillis()
        backupRunDao.insert(
            BackupRunEntity(
                backupConfigId = config.id,
                runId = runId,
                startedAt = startedAt,
                completedAt = null,
                status = BackupRunStatus.RUNNING,
                filesUploaded = 0,
                filesSkipped = 0,
                filesFailed = 0,
                bytesUploaded = 0L,
            )
        )

        // Baseline pass of a "only sync changes from now on" config: record the source tree in
        // the index instead of uploading. Branches before authorization deliberately — the pass
        // is DB-only, so it completes even offline, keeping the creation-to-baseline gap
        // minimal (files added while auth is broken must not get wrongly baselined).
        if (config.syncLaterChangesOnly && config.baselineCompletedAt == null) {
            return runBaselinePass(config, runId, summary, control)
        }

        // Clean up stale staging dirs from previous interrupted runs.
        // Cleanup failures must not abort the run — they only mean cache disk fills up slowly.
        val stagingRoot = File(context.cacheDir, "encrypt-staging")
        val stagingManager = StagingDirManager(stagingRoot)
        runCatchingAsResult { stagingManager.cleanupOldDirs() }
            .ifError { log.warning("Failed to clean up old staging dirs for run $runId", it) }
        val stagingDir = stagingManager.createRunDir(runId)

        // Authorize — silent only (no UI interaction from a worker), targeting the account this
        // config was created with so multi-account setups don't cross-upload.
        val authResult = authorizer.authorize(config.cloudAccountIdentifier)
        if (authResult !is CloudAuthResult.Authorized) {
            summary.authLost = true
            commitRunStats(config.id, runId, BackupRunStatus.FAILED, summary, completedNormally = false)
            return RunResult.AuthLost(summary, runId)
        }
        val cloudProvider: ICloudStorageProvider = authResult.data

        val appSettings = settingsRepository.settings.first()
        val fileSizeLimitBytes = appSettings.defaultFileSizeLimitBytes

        val folderCache = FolderPathCache(cloudProvider)
        val uploader = BackupUploader(
            context = context,
            cipher = cipher,
            authorizer = authorizer,
            uploadedFileIndexDao = uploadedFileIndexDao,
            backupMessageDao = backupMessageDao,
            dispatchers = dispatchers,
            cloudProvider = cloudProvider,
        )

        val analyzer = FileSystemAnalyzer(
            context = context,
            fileScanner = fileScanner,
            uploadedFileIndexDao = uploadedFileIndexDao,
            backupMessageDao = backupMessageDao,
            cloudProvider = cloudProvider,
        )

        try {
            // Derive the per-run encryption key once (PBKDF2 is too slow to run per-file)
            val encryptionKey = deriveEncryptionKey(config, summary)
            if (encryptionKey == null) {
                backupMessageDao.coalesceInsert(
                    BackupMessageEntity(
                        backupConfigId = config.id,
                        runId = runId,
                        timestamp = System.currentTimeMillis(),
                        severity = MessageSeverity.ERROR,
                        type = MessageType.ENCRYPTION_FAILED,
                        messageText = context.getString(R.string.msg_encryption_failed),
                        formatArgs = emptyList(),
                        relativePath = null,
                        readAt = null,
                    )
                )
                commitRunStats(config.id, runId, BackupRunStatus.FAILED, summary, false)
                return RunResult.FatalError(IllegalStateException("Failed to decrypt backup password"), summary, runId)
            }
            val (derivedKey, backupSalt) = encryptionKey

            runPipeline(
                config, analyzer, uploader, fileSizeLimitBytes,
                runId, stagingDir, folderCache, derivedKey, backupSalt, summary, control,
            )
            val retention = RetentionManager(uploadedFileIndexDao, cloudProvider)
            val cleanRun = !summary.authLost && !summary.quotaExceeded &&
                !summary.hitTimeBudget && !summary.sourceFolderInaccessible
            if (cleanRun) {
                retention.applyRetention(config)
            }
            // Reap orphaned cloud files left behind by transient deleteFile failures during
            // CHANGED_OVERWRITE uploads. Independent of retention policy — runs whenever the
            // cloud connection is still usable. Skipped on an inaccessible source: that run
            // failed before discovering anything, so it must not mutate the cloud or rewrite the
            // manifest from a stale, unverified index.
            if (!summary.authLost && !summary.sourceFolderInaccessible) {
                retention.reapPendingDeletions(config.id)
                writeManifest(configId, cloudProvider)
            }
        } catch (e: CancellationException) {
            // Worker cancellation (timeout, constraints lost, user action) is expected — honor
            // structured concurrency and propagate, so it isn't recorded as a Crashlytics fatal.
            // The DB writes must run in a NonCancellable scope: this coroutine is already
            // cancelled, so plain suspending DAO calls would themselves throw CancellationException
            // and the RUNNING row would stay stuck forever.
            log.warning("BackupRunner cancelled for config $configId")
            withContext(NonCancellable) {
                commitRunStats(
                    configId = config.id,
                    runId = runId,
                    status = BackupRunStatus.CANCELLED,
                    summary = summary,
                    completedNormally = false,
                )
                // Re-fetch the config: pausing mid-run is a common cause of this very
                // cancellation, and the entity loaded at run start would not see the new
                // pause flag (the trigger skips paused configs).
                backupConfigDao.getByIdOnce(config.id)?.let { currentConfig ->
                    ChargingFallbackTrigger.maybeSchedule(
                        config = currentConfig,
                        backupRunDao = backupRunDao,
                        scheduler = scheduler,
                        backupMessageDao = backupMessageDao,
                        runId = runId,
                    )
                }
            }
            throw e
        } catch (e: Exception) {
            log.error("BackupRunner encountered a fatal error for config $configId", e)
            commitRunStats(
                configId = config.id,
                runId = runId,
                status = BackupRunStatus.FAILED,
                summary = summary,
                completedNormally = false,
            )
            return RunResult.FatalError(e, summary, runId)
        } finally {
            MessageRetentionManager(backupMessageDao).prune(config.id)
            // Drop this run's staging dir promptly. Encrypted runs stage ciphertext here (unencrypted
            // runs stream directly and stage nothing — see SEC-1); cleanupOldDirs stays as the safety
            // net for hard kills that skip this finally.
            runCatchingAsResult { stagingDir.deleteRecursively() }
                .ifError { log.warning("Failed to delete staging dir for run $runId", it) }
        }

        val status = resolveRunStatus(summary)
        val completedNormally = !summary.authLost && !summary.quotaExceeded &&
            !summary.hitTimeBudget && !summary.sourceFolderInaccessible
        if (summary.sourceFolderInaccessible) {
            emitRunMessage(config.id, runId, MessageSeverity.ERROR, MessageType.FOLDER_UNREADABLE)
        }
        if (completedNormally && config.lastRunStatus == BackupRunStatus.INITIAL_SYNC_IN_PROGRESS) {
            emitRunMessage(config.id, runId, MessageSeverity.INFO, MessageType.INITIAL_SYNC_COMPLETE)
        }
        commitRunStats(
            configId = config.id,
            runId = runId,
            status = status,
            summary = summary,
            completedNormally = completedNormally,
        )

        return when {
            summary.authLost -> RunResult.AuthLost(summary, runId)
            // An inaccessible source folder is a fatal condition for this run: report FAILURE (so
            // the completion notification and worker result both reflect failure, and WorkManager
            // does not retry a condition only the user can fix) rather than a misleading Success.
            summary.sourceFolderInaccessible -> RunResult.FatalError(
                IllegalStateException("Source folder is inaccessible for config $configId"),
                summary,
                runId,
            )
            else -> RunResult.Success(summary, runId)
        }
    }

    /**
     * Runs the baseline pass of a `syncLaterChangesOnly` config instead of the upload pipeline:
     * no cloud provider, no staging, no encryption — just the tree walk recorded into the index
     * (see [BaselineRecorder]). Run bookkeeping mirrors the normal path: a stopped pass resolves
     * to INITIAL_SYNC_IN_PROGRESS (so the resume routes back to the foreground service), an
     * inaccessible source fails the run, and a clean pass reports the recorded files as skipped.
     */
    private suspend fun runBaselinePass(
        config: BackupConfigEntity,
        runId: String,
        summary: RunSummary,
        control: BackupRunControl?,
    ): RunResult {
        val recorder = BaselineRecorder(
            fileScanner = fileScanner,
            uploadedFileIndexDao = uploadedFileIndexDao,
            backupConfigDao = backupConfigDao,
        )
        try {
            recorder.recordBaseline(config, summary, control)
        } catch (e: CancellationException) {
            // Mirrors the upload path: the DB writes must run NonCancellable, otherwise the
            // RUNNING row would stay stuck forever (this coroutine is already cancelled).
            // Unlike the upload path this deliberately does NOT consult ChargingFallbackTrigger:
            // a baseline pass is a DB-only tree walk (no network, no charge-hungry uploads), so a
            // charging-only fallback run would buy nothing — the next ordinary run resumes it.
            log.warning("Baseline pass cancelled for config ${config.id}")
            withContext(NonCancellable) {
                commitRunStats(config.id, runId, BackupRunStatus.CANCELLED, summary, completedNormally = false)
            }
            throw e
        } catch (e: Exception) {
            log.error("Baseline pass failed for config ${config.id}", e)
            commitRunStats(config.id, runId, BackupRunStatus.FAILED, summary, completedNormally = false)
            return RunResult.FatalError(e, summary, runId)
        } finally {
            MessageRetentionManager(backupMessageDao).prune(config.id)
        }

        val status = resolveRunStatus(summary)
        val completedNormally = !summary.hitTimeBudget && !summary.sourceFolderInaccessible
        if (summary.sourceFolderInaccessible) {
            emitRunMessage(config.id, runId, MessageSeverity.ERROR, MessageType.FOLDER_UNREADABLE)
        }
        if (completedNormally) {
            emitRunMessage(config.id, runId, MessageSeverity.INFO, MessageType.BASELINE_RECORDED)
        }
        commitRunStats(config.id, runId, status, summary, completedNormally)

        return if (summary.sourceFolderInaccessible) {
            RunResult.FatalError(
                IllegalStateException("Source folder is inaccessible for config ${config.id}"),
                summary,
                runId,
            )
        } else {
            RunResult.Success(summary, runId)
        }
    }

    private suspend fun emitRunMessage(
        configId: String,
        runId: String,
        severity: MessageSeverity,
        type: MessageType,
    ) {
        backupMessageDao.coalesceInsert(
            BackupMessageEntity(
                backupConfigId = configId,
                runId = runId,
                timestamp = System.currentTimeMillis(),
                severity = severity,
                type = type,
                messageText = context.getString(type.labelResId),
                formatArgs = emptyList(),
                relativePath = null,
                readAt = null,
            )
        )
    }

    private suspend fun writeManifest(configId: String, cloudProvider: ICloudStorageProvider) {
        val config = backupConfigDao.getByIdOnce(configId) ?: return
        val currentVersions = uploadedFileIndexDao.getCurrentVersionList(configId)
        val entries = buildManifestEntries(currentVersions)
        val manifest = CloudManifest(
            generatedAt = Instant.now().toString(),
            files = entries,
        )
        val json = Json.encodeToString(manifest)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val writeResult = writeRootMetadataWithReAuth(
            cloudProvider,
            config.cloudSubFolderId,
            CloudManifest.CLOUD_FILE_NAME,
            bytes,
            config.cloudAccountIdentifier,
        )
        if (writeResult !is SuccessResult) {
            log.warning("Failed to write cloud manifest for config $configId")
        }
    }

    /**
     * Writes root metadata, silently re-authorizing once on [CloudAuthException].
     *
     * Long-running pipelines can outlive the access token. The provider's retry policy only
     * covers transient/rate-limit errors, not auth — so on auth failure we re-authorize via
     * [authorizer] and re-issue the write through the refreshed provider, matching the
     * pattern in `BackupUploader.handleAuthError`.
     */
    private suspend fun writeRootMetadataWithReAuth(
        cloudProvider: ICloudStorageProvider,
        rootFolderId: String,
        name: String,
        bytes: ByteArray,
        accountIdentifier: String,
    ): BinaryResult<Unit, Exception> {
        val first = cloudProvider.writeRootMetadata(rootFolderId, name, bytes)
        val authExpired = first is ErrorResult && first.error is CloudAuthException
        return if (authExpired) {
            val reAuth = authorizer.authorize(accountIdentifier)
            if (reAuth is CloudAuthResult.Authorized) {
                reAuth.data.writeRootMetadata(rootFolderId, name, bytes)
            } else {
                first
            }
        } else {
            first
        }
    }

    @Suppress("LongParameterList")
    private suspend fun runPipeline(
        config: BackupConfigEntity,
        analyzer: FileSystemAnalyzer,
        uploader: BackupUploader,
        fileSizeLimitBytes: Long,
        runId: String,
        stagingDir: File,
        folderCache: FolderPathCache,
        derivedKey: SecretKey?,
        backupSalt: ByteArray?,
        summary: RunSummary,
        control: BackupRunControl? = null,
    ) {
        val normalChannel = Channel<UploadTask>(capacity = 64)
        val oversizedChannel = Channel<UploadTask>(capacity = 8)
        coroutineScope {
            // Producer: walk the file system and enqueue tasks. The analyzer records the
            // discovered total onto [summary] itself as soon as the scan completes, so the count
            // survives a hard cancellation mid-upload (the code below would otherwise never run).
            launch {
                try {
                    analyzer.analyze(
                        config,
                        normalChannel,
                        oversizedChannel,
                        fileSizeLimitBytes,
                        runId,
                        folderCache,
                        summary,
                        control,
                    )
                } finally {
                    normalChannel.close()
                    oversizedChannel.close()
                }
            }
            // Consumer: drain normal-tier first, then oversized.
            // Both channels are always fully drained (even when stopped) to prevent the
            // producer from blocking on a full channel and hanging the coroutineScope.
            launch {
                uploader.processChannel(
                    config, normalChannel, runId, stagingDir, folderCache,
                    derivedKey, backupSalt, summary, control,
                )
                uploader.processChannel(
                    config, oversizedChannel, runId, stagingDir, folderCache,
                    derivedKey, backupSalt, summary, control,
                )
            }
        }
    }

    /**
     * Returns (derivedKey, salt) when encryption is enabled and the password can be decrypted;
     * (null, null) when encryption is disabled; or null if decryption failed (signals error).
     */
    private suspend fun deriveEncryptionKey(
        config: BackupConfigEntity,
        summary: RunSummary,
    ): Pair<SecretKey?, ByteArray?>? {
        if (!config.encryptionEnabled || config.encryptedPasswordBlob == null) return Pair(null, null)
        val decryptResult = encryptionRepository.decryptPassword(config.encryptedPasswordBlob)
        if (decryptResult !is SuccessResult) {
            summary.filesFailed++ // flag as failed so stats are non-zero
            return null
        }
        val password = decryptResult.value
        val salt = config.encryptionParams?.let {
            Base64.getDecoder().decode(it.salt)
        } ?: cipher.generateBackupSalt()
        return Pair(cipher.deriveKey(password, salt), salt)
    }

    private suspend fun commitRunStats(
        configId: String,
        runId: String,
        status: BackupRunStatus,
        summary: RunSummary,
        completedNormally: Boolean,
    ) {
        val now = System.currentTimeMillis()
        backupConfigDao.updateRunStats(
            id = configId,
            lastRunAt = now,
            lastRunStatus = status,
            filesUploaded = summary.filesUploaded,
            filesSkipped = summary.filesSkipped,
            filesFailed = summary.filesFailed,
            bytesUploaded = summary.bytesUploaded,
            lastRunCompletedNormally = completedNormally,
        )
        backupRunDao.markComplete(
            runId = runId,
            completedAt = now,
            status = status,
            filesUploaded = summary.filesUploaded,
            filesSkipped = summary.filesSkipped,
            filesFailed = summary.filesFailed,
            bytesUploaded = summary.bytesUploaded,
        )
        backupRunDao.pruneOld(configId)
        when (resolveCrossRunProgress(status, summary, completedNormally)) {
            CrossRunProgress.PERSIST -> {
                val prev = backupConfigDao.getByIdOnce(configId)
                val prevTotal = prev?.filesUploadedTotal ?: 0
                backupConfigDao.updateCrossRunProgress(
                    id = configId,
                    totalFilesDiscovered = summary.totalFilesDiscovered,
                    filesUploadedTotal = prevTotal + summary.filesUploaded,
                )
            }
            CrossRunProgress.RESET -> backupConfigDao.updateCrossRunProgress(
                id = configId,
                totalFilesDiscovered = 0,
                filesUploadedTotal = 0,
            )
            CrossRunProgress.UNCHANGED -> Unit // failed/quota run — leave cross-run counters as-is
        }
    }
}
