package ch.abwesend.foldervault.view.viewmodel

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.BackupMessage
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupMessageRepository
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.backup.StartManualBackupUseCase
import ch.abwesend.foldervault.domain.cloud.CloudAuthResult
import ch.abwesend.foldervault.domain.cloud.CloudNotFoundException
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.network.INetworkConnectivityChecker
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.domain.storage.ReleaseSafPermissionIfUnusedUseCase
import ch.abwesend.foldervault.domain.system.IChargingStateChecker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

sealed interface DetailEvent {
    data object Deleted : DetailEvent

    /**
     * A confirmed delete was refused because a backup run is active. Only reachable through the
     * race where a run starts after the confirm dialog opened (the delete action is disabled while
     * a run is visible) — the screen shows a transient notice so the refusal is not silent.
     */
    data object DeleteRefusedWhileRunning : DetailEvent
}

/**
 * Drives the optional "also delete the cloud folder" branch of a config deletion. The plain local
 * delete (the default, cloud folder kept) never leaves [Idle] — it is done in one shot. Deleting
 * the Drive folder as well is asynchronous (authorize → delete → local delete) and moves through
 * these states so the screen can show progress, run a re-consent flow, and surface a failure.
 */
sealed interface CloudDeleteState {
    /** No cloud-folder deletion in flight. */
    data object Idle : CloudDeleteState

    /** Authorizing and deleting the Drive folder — the screen shows a blocking progress dialog. */
    data object InProgress : CloudDeleteState

    /** Silent authorization needs user consent; the screen launches [pendingIntent] to obtain it. */
    data class ConsentRequired(val pendingIntent: PendingIntent) : CloudDeleteState

    /**
     * The Drive folder could not be deleted (network error, or the user declined re-consent). The
     * screen warns the user; acknowledging it still deletes the config locally
     * ([acknowledgeFolderDeleteFailure]), so the primary intent — removing the config — is honored.
     */
    data object FolderDeleteFailed : CloudDeleteState
}

class BackupDetailViewModel(
    private val configId: String,
    private val configRepo: IBackupConfigRepository,
    private val messageRepo: IBackupMessageRepository,
    private val scheduler: IBackupScheduler,
    private val startManualBackup: StartManualBackupUseCase,
    private val authorizer: ICloudAuthorizer,
    private val encryptionRepo: IEncryptionRepository,
    private val settingsRepo: IAppSettingsRepository,
    private val connectivityChecker: INetworkConnectivityChecker,
    private val chargingChecker: IChargingStateChecker,
    private val releaseSafPermissionIfUnused: ReleaseSafPermissionIfUnusedUseCase,
    autoStartBackup: Boolean = false,
) : BaseViewModel() {

    val config: StateFlow<BackupConfig?> = configRepo.getById(configId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<BackupMessage>> = messageRepo.getUndismissed(configId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val errorCount: StateFlow<Int> = messageRepo.getUnreadCountBySeverity(
        configId,
        listOf(MessageSeverity.ERROR, MessageSeverity.CRITICAL),
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val isRunning: StateFlow<Boolean> = scheduler.observeIsRunning(configId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Whether an interrupted sync will eventually be picked up again without user action — i.e.
     * whether the config's *effective* schedule (its own, or the global default when it
     * delegates) is anything but manual-only. Drives the wording of the interrupted-sync
     * banner: promising "continues automatically" on a manual-only config would leave the user
     * waiting for a run that needs a tap.
     */
    val continuesAutomatically: StateFlow<Boolean> = combine(
        config.filterNotNull(),
        settingsRepo.settings,
    ) { current, settings ->
        val effectiveSchedule = if (current.schedule == BackupSchedule.USE_GLOBAL_DEFAULT) {
            settings.defaultSchedule
        } else {
            current.schedule
        }
        effectiveSchedule != BackupSchedule.MANUAL_ONLY
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _passwordCheckResult = MutableStateFlow<Boolean?>(null)
    val passwordCheckResult: StateFlow<Boolean?> = _passwordCheckResult.asStateFlow()

    private val _events = MutableSharedFlow<DetailEvent>()
    val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

    private val _cloudDeleteState = MutableStateFlow<CloudDeleteState>(CloudDeleteState.Idle)
    val cloudDeleteState: StateFlow<CloudDeleteState> = _cloudDeleteState.asStateFlow()

    /**
     * Emits `true` when the user tapped "Back up now" on a Wi-Fi-only config while the device
     * is on a metered network — the screen shows a confirmation dialog so the user can either
     * cancel or allow this single run on mobile data.
     */
    private val _showMeteredOverridePrompt = MutableStateFlow(false)
    val showMeteredOverridePrompt: StateFlow<Boolean> = _showMeteredOverridePrompt.asStateFlow()

    /**
     * Emits `true` when the user asked to back up a charging-only config while the device is not
     * plugged in — the screen shows a prompt so the user can either start this one run anyway or
     * cancel. Surfaced only after the (optional) metered-network prompt is resolved, so the two
     * dialogs never stack.
     */
    private val _showChargingOverridePrompt = MutableStateFlow(false)
    val showChargingOverridePrompt: StateFlow<Boolean> = _showChargingOverridePrompt.asStateFlow()

    /**
     * The network policy chosen for the run currently awaiting the charging decision. Captured
     * when the charging prompt opens (it is `ANY` if the user just overrode the Wi-Fi prompt,
     * otherwise the config's own policy), re-applied once that prompt is confirmed, and reset to
     * `null` when the prompt resolves either way — non-null exactly while the prompt is open, so
     * a path that confirms without having opened the prompt fails loudly instead of silently
     * running with a stale policy.
     */
    private var pendingNetworkPolicy: NetworkPolicy? = null

    init {
        // Auto-start of the initial upload right after creating the config (the nav graph opens
        // this screen with the flag). Guarded on IDLE so a re-created screen (process death,
        // config change) cannot re-trigger once the first run has committed a result.
        // NOTE: this block must stay BELOW every property that backUpNow() touches (config,
        // isRunning, the prompt flows, pendingNetworkPolicy) — on an eager dispatcher the
        // coroutine can run during construction, before later-declared fields are initialized.
        if (autoStartBackup) {
            safeLaunch {
                val current = config.filterNotNull().first()
                if (current.lastRunStatus == BackupRunStatus.IDLE) {
                    backUpNow()
                }
            }
        }
    }

    /**
     * Triggered by the "Back up now" button. If the config is Wi-Fi-only and the device is
     * currently on a metered (non-Wi-Fi) network, exposes a confirmation prompt instead of
     * scheduling immediately. Otherwise it hands off to the charging check, which either enqueues
     * the run right away or prompts if the config is charging-only and the device is unplugged.
     */
    fun backUpNow() {
        val current = config.value
        if (current != null && !current.isPaused && !isRunning.value) {
            val needsWifi = current.networkPolicy == NetworkPolicy.WIFI_ONLY
            if (needsWifi && !connectivityChecker.isOnUnmeteredNetwork()) {
                _showMeteredOverridePrompt.value = true
            } else {
                scheduleOrPromptCharging(current.networkPolicy)
            }
        }
    }

    /**
     * Confirms the Wi-Fi warning — proceeds with no Wi-Fi requirement for this one run, then runs
     * the charging check (which may in turn open the charging prompt).
     */
    fun confirmMeteredOverride() {
        _showMeteredOverridePrompt.value = false
        val current = config.value
        if (current != null && !current.isPaused && !isRunning.value) {
            scheduleOrPromptCharging(NetworkPolicy.ANY)
        }
    }

    /** Dismisses the Wi-Fi warning without starting a backup. */
    fun dismissMeteredOverride() {
        _showMeteredOverridePrompt.value = false
    }

    /**
     * Second gate after the network check. If the config is charging-only and the device is not
     * currently charging, opens the charging prompt (remembering [networkPolicy] for the follow-up
     * action) instead of enqueueing a run that would silently wait. Otherwise schedules the run
     * immediately with the config's own charging requirement.
     */
    private fun scheduleOrPromptCharging(networkPolicy: NetworkPolicy) {
        val current = config.value
        if (current != null) {
            if (current.requiresCharging && !chargingChecker.isCharging()) {
                pendingNetworkPolicy = networkPolicy
                _showChargingOverridePrompt.value = true
            } else {
                startManualBackup.start(current, networkPolicy, current.requiresCharging)
            }
        }
    }

    /** Confirms the charging warning — schedules the run without the charging requirement this once. */
    fun confirmChargingOverride() {
        _showChargingOverridePrompt.value = false
        val current = config.value
        val networkPolicy = pendingNetworkPolicy
        pendingNetworkPolicy = null
        if (networkPolicy == null) {
            logger.error("Charging override confirmed but no prompt captured a network policy")
        } else if (current != null && !current.isPaused && !isRunning.value) {
            startManualBackup.start(current, networkPolicy, requiresCharging = false)
        }
    }

    /** Dismisses the charging warning without starting a backup. */
    fun dismissChargingOverride() {
        _showChargingOverridePrompt.value = false
        pendingNetworkPolicy = null
    }

    fun togglePause() = safeLaunch {
        val current = config.value ?: return@safeLaunch
        val newPaused = !current.isPaused
        // The pause flag must be persisted BEFORE touching the scheduler: cancelling an in-flight
        // worker makes it re-fetch the config to decide whether a charging-only fallback may be
        // scheduled, and it must observe the new flag — cancelling first would race that re-fetch
        // and could enqueue a fallback (plus its info message) for a config the user just paused.
        configRepo.setPaused(configId, newPaused)
        if (newPaused) {
            scheduler.cancel(configId)
        } else {
            val globalDefault = settingsRepo.settings.first().defaultSchedule
            scheduler.schedulePeriodicIfNeeded(
                configId = configId,
                schedule = current.schedule,
                networkPolicy = current.networkPolicy,
                requiresCharging = current.requiresCharging,
                globalDefault = globalDefault,
            )
        }
    }

    fun checkPassword(candidate: String) = safeLaunch {
        val blob = config.value?.encryptedPasswordBlob
        if (blob == null) {
            _passwordCheckResult.value = false
            return@safeLaunch
        }
        val result = encryptionRepo.decryptPassword(blob)
        _passwordCheckResult.value = (result as? SuccessResult)?.value == candidate
    }

    fun clearPasswordCheckResult() {
        _passwordCheckResult.value = null
    }

    fun markRead(ids: List<Long>) = safeLaunch { messageRepo.markRead(ids) }

    fun dismiss(ids: List<Long>) = safeLaunch { messageRepo.dismiss(ids) }

    fun dismissAll() = safeLaunch { messageRepo.dismissAllForConfig(configId) }

    /**
     * Deletes the config. When [alsoDeleteCloudFolder] is false (the default the UI leads toward),
     * only local state is removed and the Drive folder is kept. When true, the config's Drive
     * sub-folder is deleted first — authorizing for the config's account, prompting for re-consent
     * if the silent grant has lapsed — and only then is the config removed locally. A cloud-delete
     * failure does not block the local delete: the user is warned and the config is still removed
     * once they acknowledge (see [CloudDeleteState]).
     *
     * Deletion is refused while a backup is running (either host): the UI disables the delete action
     * for that window, and this guard additionally covers the race where a run starts between the
     * confirm dialog opening and this call — deleting the config, or its Drive folder, mid-upload
     * would fail or orphan the in-flight run. A refused delete emits
     * [DetailEvent.DeleteRefusedWhileRunning] so the screen can tell the user why nothing happened.
     * The guard reads the scheduler's live state rather than [isRunning] — that `WhileSubscribed`
     * cache is only fresh while the screen collects it.
     */
    fun deleteBackup(alsoDeleteCloudFolder: Boolean) = safeLaunch {
        val current = config.value
        if (scheduler.observeIsRunning(configId).first()) {
            logger.warning("Ignoring delete of config $configId: a backup is currently running")
            _events.emit(DetailEvent.DeleteRefusedWhileRunning)
        } else if (!alsoDeleteCloudFolder || current == null) {
            performLocalDelete(current?.sourceTreeUri)
        } else {
            _cloudDeleteState.value = CloudDeleteState.InProgress
            when (val authResult = authorizer.authorize(current.cloudAccountIdentifier)) {
                is CloudAuthResult.Authorized -> deleteCloudThenFinish(
                    provider = authResult.data,
                    folderId = current.cloudSubFolderId,
                    sourceTreeUri = current.sourceTreeUri,
                )
                is CloudAuthResult.ConsentRequired ->
                    _cloudDeleteState.value = CloudDeleteState.ConsentRequired(authResult.pendingIntent)
                CloudAuthResult.Error -> {
                    logger.warning("Could not authorize to delete cloud folder for config $configId")
                    _cloudDeleteState.value = CloudDeleteState.FolderDeleteFailed
                }
            }
        }
    }

    /**
     * Resumes the cloud-folder deletion after the user completed (or cancelled) the re-consent
     * screen launched for [CloudDeleteState.ConsentRequired]. A cancelled or failed consent is
     * treated as a cloud-delete failure — the config is still removed once the user acknowledges.
     *
     * Moves back to [CloudDeleteState.InProgress] first so the screen shows the blocking progress
     * dialog again while the (possibly slow) Drive delete + local delete run: leaving the state at
     * [CloudDeleteState.ConsentRequired] would keep the screen fully interactive, letting the user
     * tap delete again or start a backup into the folder being deleted.
     */
    fun handleDriveConsentResult(data: Intent?) = safeLaunch {
        _cloudDeleteState.value = CloudDeleteState.InProgress
        val current = config.value
        val authResult = authorizer.authorizeFromIntent(data)
        if (current != null && authResult is SuccessResult) {
            deleteCloudThenFinish(
                provider = authResult.value,
                folderId = current.cloudSubFolderId,
                sourceTreeUri = current.sourceTreeUri,
            )
        } else {
            logger.warning("Drive re-consent for deleting cloud folder was cancelled or failed")
            _cloudDeleteState.value = CloudDeleteState.FolderDeleteFailed
        }
    }

    /**
     * Deletes the config's Drive sub-folder (which cascades to every backed-up file inside it) and,
     * on success, removes the config locally. An already-gone folder counts as success; any other
     * error surfaces [CloudDeleteState.FolderDeleteFailed] and leaves the config in place for now.
     */
    private suspend fun deleteCloudThenFinish(
        provider: ICloudStorageProvider,
        folderId: String,
        sourceTreeUri: String,
    ) {
        val deleteResult = provider.deleteFile(folderId)
        if (isDeleted(deleteResult)) {
            performLocalDelete(sourceTreeUri)
        } else {
            logger.warning(
                "Failed to delete cloud folder $folderId for config $configId: " +
                    (deleteResult as ErrorResult).error,
            )
            _cloudDeleteState.value = CloudDeleteState.FolderDeleteFailed
        }
    }

    /**
     * Acknowledges the [CloudDeleteState.FolderDeleteFailed] warning: the Drive folder was left
     * behind, but the config is still deleted locally so the user's delete is honored.
     */
    fun acknowledgeFolderDeleteFailure() = safeLaunch {
        _cloudDeleteState.value = CloudDeleteState.Idle
        performLocalDelete(config.value?.sourceTreeUri)
    }

    /**
     * A cloud delete counts as done when it succeeded or the folder was already gone — deleting an
     * already-absent folder must not strand the local delete (mirrors `RetentionManager.isGone`).
     */
    private fun isDeleted(result: BinaryResult<Unit, Exception>): Boolean =
        result is SuccessResult ||
            (result is ErrorResult && result.error is CloudNotFoundException)

    /**
     * Deletes the config and everything tied to it, then hands back the folder's persisted SAF
     * grant if no other config still uses it (BUG-12). Order matters: the config is removed first
     * so it can no longer count as a user of its own tree URI, and it is additionally excluded from
     * the in-use check to be robust against the delete not yet having propagated to the flow.
     */
    private suspend fun performLocalDelete(sourceTreeUri: String?) {
        scheduler.cancel(configId)
        messageRepo.deleteAllForConfig(configId)
        configRepo.deleteById(configId)
        if (sourceTreeUri != null) {
            releaseSafPermissionIfUnused(sourceTreeUri, excludingConfigId = configId)
        }
        _cloudDeleteState.value = CloudDeleteState.Idle
        _events.emit(DetailEvent.Deleted)
    }
}
