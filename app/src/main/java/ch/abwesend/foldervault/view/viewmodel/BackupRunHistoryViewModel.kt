package ch.abwesend.foldervault.view.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.domain.backup.BackupRun
import ch.abwesend.foldervault.domain.backup.IBackupRunRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class BackupRunHistoryViewModel(
    configId: String,
    runRepo: IBackupRunRepository,
) : ViewModel() {

    val runs: StateFlow<List<BackupRun>> = runRepo.observeByConfig(configId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
