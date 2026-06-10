package ch.abwesend.foldervault.view.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.AppTheme
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepo: IAppSettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setDefaultSchedule(schedule: BackupSchedule) = update { it.copy(defaultSchedule = schedule) }

    fun setDefaultChangedFilePolicy(policy: ChangedFilePolicy) =
        update { it.copy(defaultChangedFilePolicy = policy) }

    fun setDefaultNetworkPolicy(policy: NetworkPolicy) =
        update { it.copy(defaultNetworkPolicy = policy) }

    fun setDefaultFileSizeLimit(limitMb: Int) =
        update { it.copy(defaultFileSizeLimitBytes = limitMb.toLong() * 1024 * 1024) }

    fun setTheme(theme: AppTheme) = update { it.copy(theme = theme) }

    fun setAnonymousErrorReports(enabled: Boolean) =
        update { it.copy(anonymousErrorReports = enabled) }

    fun setShowOnboarding(show: Boolean) = update { it.copy(showOnboarding = show) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }
}
