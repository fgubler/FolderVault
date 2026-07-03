package ch.abwesend.foldervault.view.viewmodel

import android.net.Uri
import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.logging.ILogExporter
import ch.abwesend.foldervault.domain.logging.ITelemetryToggle
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.AppTheme
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.domain.system.BackgroundRestrictionStatus
import ch.abwesend.foldervault.domain.system.IBackgroundRestrictionChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

internal class SettingsViewModel(
    private val settingsRepo: IAppSettingsRepository,
    private val telemetryToggle: ITelemetryToggle,
    private val logExporter: ILogExporter,
    private val restrictionChecker: IBackgroundRestrictionChecker,
    private val dispatchers: IDispatchers,
) : BaseViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _exportResult = MutableStateFlow<UiText?>(null)
    val exportResult: StateFlow<UiText?> = _exportResult.asStateFlow()

    private val _backgroundRestrictions = MutableStateFlow(BackgroundRestrictionStatus())
    val backgroundRestrictions: StateFlow<BackgroundRestrictionStatus> = _backgroundRestrictions.asStateFlow()

    /**
     * Re-reads the OS restriction states. Called whenever the settings screen (re-)enters the
     * foreground, so the shown status reflects changes the user just made in the system settings.
     */
    fun refreshBackgroundRestrictions() {
        _backgroundRestrictions.value = BackgroundRestrictionStatus(
            ignoringBatteryOptimizations = restrictionChecker.isIgnoringBatteryOptimizations(),
            backgroundDataRestricted = restrictionChecker.isBackgroundDataRestricted(),
        )
    }

    fun setDefaultSchedule(schedule: BackupSchedule) = update { it.copy(defaultSchedule = schedule) }

    fun setDefaultChangedFilePolicy(policy: ChangedFilePolicy) =
        update { it.copy(defaultChangedFilePolicy = policy) }

    fun setDefaultNetworkPolicy(policy: NetworkPolicy) =
        update { it.copy(defaultNetworkPolicy = policy) }

    fun setDefaultFileSizeLimit(limitMb: Int) =
        update { it.copy(defaultFileSizeLimitBytes = limitMb.toLong() * 1024 * 1024) }

    fun setTheme(theme: AppTheme) = update { it.copy(theme = theme) }

    fun setAnonymousErrorReports(enabled: Boolean) {
        telemetryToggle.setEnabled(enabled)
        update { it.copy(anonymousErrorReports = enabled) }
    }

    fun setShowOnboarding(show: Boolean) = update { it.copy(showOnboarding = show) }

    fun dismissExportResult() {
        _exportResult.value = null
    }

    fun exportTodayLogFile(uri: Uri) {
        safeLaunch {
            val exported = withContext(dispatchers.io) { logExporter.exportTodayLog(uri.toString()) }
            _exportResult.value = UiText.Resource(
                if (exported) R.string.export_log_success else R.string.export_log_failed,
            )
        }
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        safeLaunch { settingsRepo.update(transform) }
    }
}
