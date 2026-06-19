package ch.abwesend.foldervault.view.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.logging.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Base for all ViewModels that launch coroutines from the UI layer.
 *
 * Provides [safeLaunch] which wraps [viewModelScope] launches with a try/catch that:
 * - re-throws [CancellationException] so structured concurrency keeps working;
 * - logs any other [Throwable] via [logger.error] (→ local log + Crashlytics with stack trace);
 * - exposes [unexpectedError] so the hosting screen can render a generic error dialog instead of
 *   leaving the UI silently stuck in a loading state.
 *
 * Domain-specific error states (form validation, named cloud-setup errors, etc.) should keep
 * driving their own flows — [unexpectedError] is the last-resort catch for genuinely unexpected
 * failures from layers below the ViewModel.
 */
abstract class BaseViewModel : ViewModel() {

    private val _unexpectedError = MutableStateFlow<UiText?>(null)
    val unexpectedError: StateFlow<UiText?> = _unexpectedError.asStateFlow()

    fun dismissUnexpectedError() {
        _unexpectedError.value = null
    }

    protected fun safeLaunch(
        @StringRes errorMessageRes: Int = R.string.error_unexpected,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Unhandled error in ${this@BaseViewModel::class.java.simpleName}", e)
            _unexpectedError.value = UiText.Resource(errorMessageRes)
        }
    }
}
