package ch.abwesend.foldervault.view.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupMessageRepository
import ch.abwesend.foldervault.domain.model.MessageSeverity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(
    configRepo: IBackupConfigRepository,
    private val messageRepo: IBackupMessageRepository,
) : ViewModel() {

    private val errorSeverities = listOf(MessageSeverity.ERROR, MessageSeverity.CRITICAL)

    val configs: StateFlow<List<BackupConfig>> = configRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val errorBadgeCounts: StateFlow<Map<String, Int>> = configs
        .flatMapLatest { configList ->
            if (configList.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    configList.map { config ->
                        messageRepo.getUnreadCountBySeverity(config.id, errorSeverities)
                            .map { count -> config.id to count }
                    },
                ) { pairs -> pairs.toMap() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
