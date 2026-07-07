package ch.abwesend.foldervault.view.viewmodel

import android.net.Uri
import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.database.IDatabaseRecoveryService
import ch.abwesend.foldervault.domain.logging.ILogExporter
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Health of the local database as seen by the startup guard. */
sealed interface DatabaseGuardState {
    /** Health check in progress — the UI shows nothing / a placeholder. */
    data object Checking : DatabaseGuardState

    /** Database opened successfully — the normal app UI may be shown. */
    data object Healthy : DatabaseGuardState

    /** Database could not be opened — the error screen with recovery options is shown. */
    data object Error : DatabaseGuardState
}

/**
 * Backs the startup database guard: verifies the database opens before the normal UI is shown
 * and drives the recovery options (log export, user-confirmed reset) of the error screen.
 */
internal class DatabaseGuardViewModel(
    private val recoveryService: IDatabaseRecoveryService,
    private val logExporter: ILogExporter,
    private val dispatchers: IDispatchers,
) : BaseViewModel() {

    private val _state = MutableStateFlow<DatabaseGuardState>(DatabaseGuardState.Checking)
    val state: StateFlow<DatabaseGuardState> = _state.asStateFlow()

    private val _userMessage = MutableStateFlow<UiText?>(null)
    val userMessage: StateFlow<UiText?> = _userMessage.asStateFlow()

    init {
        verifyDatabase()
    }

    /** (Re-)runs the health check; also serves as the "try again" action on the error screen. */
    fun verifyDatabase() {
        _state.value = DatabaseGuardState.Checking
        viewModelScope.launch {
            _state.value = when (recoveryService.verifyDatabaseHealth()) {
                is SuccessResult -> DatabaseGuardState.Healthy
                is ErrorResult -> DatabaseGuardState.Error
            }
        }
    }

    /**
     * Wipes and recreates the local database. Only call after the user confirmed the
     * destructive consequences in the confirmation dialog.
     */
    fun resetDatabase() {
        _state.value = DatabaseGuardState.Checking
        viewModelScope.launch {
            when (recoveryService.resetDatabase()) {
                is SuccessResult -> _state.value = DatabaseGuardState.Healthy
                is ErrorResult -> {
                    _state.value = DatabaseGuardState.Error
                    _userMessage.value = UiText.Resource(R.string.database_reset_failed)
                }
            }
        }
    }

    fun exportTodayLogFile(uri: Uri) {
        safeLaunch {
            val exported = withContext(dispatchers.io) { logExporter.exportTodayLog(uri.toString()) }
            _userMessage.value = UiText.Resource(
                if (exported) R.string.export_log_success else R.string.export_log_failed,
            )
        }
    }

    fun dismissUserMessage() {
        _userMessage.value = null
    }
}
