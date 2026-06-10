package ch.abwesend.foldervault.view.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupMessageRepository
import ch.abwesend.foldervault.domain.model.MessageSeverity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(
    configRepo: IBackupConfigRepository,
    private val messageRepo: IBackupMessageRepository,
) : ViewModel() {

    val configs: StateFlow<List<BackupConfig>> = configRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun errorBadgeCount(configId: String): Flow<Int> = messageRepo.getUnreadCountBySeverity(
        configId,
        listOf(MessageSeverity.ERROR, MessageSeverity.CRITICAL),
    )
}
