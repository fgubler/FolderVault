package ch.abwesend.foldervault.view.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.BackupMessage
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupMessageRepository
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.model.MessageSeverity
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
import kotlinx.coroutines.launch

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
) : ViewModel() {

    val config: StateFlow<BackupConfig?> = configRepo.getById(configId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<BackupMessage>> = messageRepo.getUndismissed(configId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val errorCount: StateFlow<Int> = messageRepo.getUnreadCountBySeverity(
        configId,
        listOf(MessageSeverity.ERROR, MessageSeverity.CRITICAL),
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _passwordCheckResult = MutableStateFlow<Boolean?>(null)
    val passwordCheckResult: StateFlow<Boolean?> = _passwordCheckResult.asStateFlow()

    private val _events = MutableSharedFlow<DetailEvent>()
    val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

    fun backUpNow() {
        if (config.value?.isPaused == true) return
        scheduler.scheduleOneTime(configId)
    }

    fun togglePause() {
        viewModelScope.launch {
            val current = config.value ?: return@launch
            val newPaused = !current.isPaused
            if (newPaused) {
                scheduler.cancel(configId)
            } else {
                val globalDefault = settingsRepo.settings.first().defaultSchedule
                scheduler.schedulePeriodicIfNeeded(configId, current.schedule, current.networkPolicy, globalDefault)
            }
            configRepo.setPaused(configId, newPaused)
        }
    }

    fun checkPassword(candidate: String) {
        viewModelScope.launch {
            val blob = config.value?.encryptedPasswordBlob
            if (blob == null) {
                _passwordCheckResult.value = false
                return@launch
            }
            val result = encryptionRepo.decryptPassword(blob)
            _passwordCheckResult.value = (result as? SuccessResult)?.value == candidate
        }
    }

    fun clearPasswordCheckResult() {
        _passwordCheckResult.value = null
    }

    fun markRead(ids: List<Long>) {
        viewModelScope.launch { messageRepo.markRead(ids) }
    }

    fun dismiss(ids: List<Long>) {
        viewModelScope.launch { messageRepo.dismiss(ids) }
    }

    fun dismissAll() {
        viewModelScope.launch { messageRepo.dismissAllForConfig(configId) }
    }

    fun deleteBackup() {
        viewModelScope.launch {
            scheduler.cancel(configId)
            messageRepo.deleteAllForConfig(configId)
            configRepo.deleteById(configId)
            _events.emit(DetailEvent.Deleted)
        }
    }
}
