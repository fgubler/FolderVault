package ch.abwesend.foldervault.view.viewmodel

import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.BackupMessage
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupMessageRepository
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.network.INetworkConnectivityChecker
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.domain.system.IChargingStateChecker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

sealed interface DetailEvent {
    data object Deleted : DetailEvent
}

class BackupDetailViewModel(
    private val configId: String,
    private val configRepo: IBackupConfigRepository,
    private val messageRepo: IBackupMessageRepository,
    private val scheduler: IBackupScheduler,
    private val encryptionRepo: IEncryptionRepository,
    private val settingsRepo: IAppSettingsRepository,
    private val connectivityChecker: INetworkConnectivityChecker,
    private val chargingChecker: IChargingStateChecker,
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

    private val _passwordCheckResult = MutableStateFlow<Boolean?>(null)
    val passwordCheckResult: StateFlow<Boolean?> = _passwordCheckResult.asStateFlow()

    private val _events = MutableSharedFlow<DetailEvent>()
    val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

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
                scheduler.scheduleOneTime(configId, networkPolicy, current.requiresCharging)
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
            scheduler.scheduleOneTime(configId, networkPolicy, requiresCharging = false)
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

    fun deleteBackup() = safeLaunch {
        scheduler.cancel(configId)
        messageRepo.deleteAllForConfig(configId)
        configRepo.deleteById(configId)
        _events.emit(DetailEvent.Deleted)
    }
}
