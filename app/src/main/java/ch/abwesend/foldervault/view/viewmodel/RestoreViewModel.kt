package ch.abwesend.foldervault.view.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RestoreState {
    data object Idle : RestoreState
    data object Scanning : RestoreState
    data object SourceReady : RestoreState
    data object ReadyToStart : RestoreState
    data object Running : RestoreState
    data class Done(val result: RestoreResult) : RestoreState
}

data class RestoreUiState(
    val state: RestoreState = RestoreState.Idle,
    val sourceUri: String? = null,
    val outputUri: String? = null,
    val cryptFileCount: Int = 0,
    val otherFileCount: Int = 0,
    val collisionPolicy: RestoreCollisionPolicy = RestoreCollisionPolicy.SKIP,
    val progress: RestoreProgress? = null,
)

class RestoreViewModel(private val engine: IRestoreEngine) : ViewModel() {

    private val _uiState = MutableStateFlow(RestoreUiState())
    val uiState: StateFlow<RestoreUiState> = _uiState.asStateFlow()

    private var restoreJob: Job? = null

    fun setSourceFolder(uri: String) {
        _uiState.update { it.copy(sourceUri = uri, state = RestoreState.Scanning) }
        viewModelScope.launch {
            val result = engine.scanSourceFolder(uri)
            _uiState.update { current ->
                current.copy(
                    cryptFileCount = result.cryptFileCount,
                    otherFileCount = result.otherFileCount,
                    state = if (current.outputUri != null) RestoreState.ReadyToStart else RestoreState.SourceReady,
                )
            }
        }
    }

    fun setOutputFolder(uri: String) {
        _uiState.update { current ->
            val newState = if (
                current.state == RestoreState.SourceReady ||
                current.state == RestoreState.ReadyToStart
            ) {
                RestoreState.ReadyToStart
            } else {
                current.state
            }
            current.copy(outputUri = uri, state = newState)
        }
    }

    fun setCollisionPolicy(policy: RestoreCollisionPolicy) {
        _uiState.update { it.copy(collisionPolicy = policy) }
    }

    fun startRestore(password: String) {
        val snapshot = _uiState.value
        val src = snapshot.sourceUri
        val out = snapshot.outputUri
        if (src != null && out != null) {
            restoreJob = viewModelScope.launch {
                _uiState.update { it.copy(state = RestoreState.Running, progress = null) }
                try {
                    val result = engine.decryptAll(
                        sourceUri = src,
                        outputUri = out,
                        password = password,
                        collisionPolicy = _uiState.value.collisionPolicy,
                        onProgress = { progress -> _uiState.update { it.copy(progress = progress) } },
                    )
                    _uiState.update { it.copy(state = RestoreState.Done(result)) }
                } finally {
                    _uiState.update { current ->
                        if (current.state is RestoreState.Running) {
                            current.copy(
                                state = if (current.outputUri != null) {
                                    RestoreState.ReadyToStart
                                } else {
                                    RestoreState.SourceReady
                                },
                            )
                        } else {
                            current
                        }
                    }
                }
            }
        }
    }

    fun cancel() {
        restoreJob?.cancel()
        restoreJob = null
    }

    fun reset() {
        restoreJob?.cancel()
        restoreJob = null
        _uiState.value = RestoreUiState()
    }
}
