package ch.abwesend.foldervault.view.viewmodel

import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.BackupMessage
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupMessageRepository
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.network.INetworkConnectivityChecker
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
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
     * Triggered by the "Back up now" button. If the config is Wi-Fi-only and the device is
     * currently on a metered (non-Wi-Fi) network, exposes a confirmation prompt instead of
     * scheduling immediately — otherwise enqueues a one-time run constrained to the configured
     * network policy.
     */
    fun backUpNow() {
        val current = config.value
        if (current != null && !current.isPaused && !isRunning.value) {
            val needsWifi = current.networkPolicy == NetworkPolicy.WIFI_ONLY
            if (needsWifi && !connectivityChecker.isOnUnmeteredNetwork()) {
                _showMeteredOverridePrompt.value = true
            } else {
                scheduler.scheduleOneTime(configId, current.networkPolicy)
            }
        }
    }

    /** Confirms the warning — schedules the run with no Wi-Fi requirement for this one time. */
    fun confirmMeteredOverride() {
        _showMeteredOverridePrompt.value = false
        if (config.value?.isPaused != true && !isRunning.value) {
            scheduler.scheduleOneTime(configId, NetworkPolicy.ANY)
        }
    }

    /** Dismisses the warning without starting a backup. */
    fun dismissMeteredOverride() {
        _showMeteredOverridePrompt.value = false
    }

    fun togglePause() = safeLaunch {
        val current = config.value ?: return@safeLaunch
        val newPaused = !current.isPaused
        if (newPaused) {
            scheduler.cancel(configId)
        } else {
            val globalDefault = settingsRepo.settings.first().defaultSchedule
            scheduler.schedulePeriodicIfNeeded(configId, current.schedule, current.networkPolicy, globalDefault)
        }
        configRepo.setPaused(configId, newPaused)
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
