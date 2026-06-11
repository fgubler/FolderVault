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
import kotlinx.coroutines.launch

sealed interface RestoreState {
    data object Idle : RestoreState
    data object Scanning : RestoreState
    data object SourceReady : RestoreState
    data object ReadyToStart : RestoreState
    data object Running : RestoreState
    data class Done(val result: RestoreResult) : RestoreState
}

class RestoreViewModel(private val engine: IRestoreEngine) : ViewModel() {

    private val _state = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val state: StateFlow<RestoreState> = _state.asStateFlow()

    private val _sourceUri = MutableStateFlow<String?>(null)
    val sourceUri: StateFlow<String?> = _sourceUri.asStateFlow()

    private val _outputUri = MutableStateFlow<String?>(null)
    val outputUri: StateFlow<String?> = _outputUri.asStateFlow()

    private val _cryptFileCount = MutableStateFlow(0)
    val cryptFileCount: StateFlow<Int> = _cryptFileCount.asStateFlow()

    private val _otherFileCount = MutableStateFlow(0)
    val otherFileCount: StateFlow<Int> = _otherFileCount.asStateFlow()

    private val _collisionPolicy = MutableStateFlow(RestoreCollisionPolicy.SKIP)
    val collisionPolicy: StateFlow<RestoreCollisionPolicy> = _collisionPolicy.asStateFlow()

    private val _progress = MutableStateFlow<RestoreProgress?>(null)
    val progress: StateFlow<RestoreProgress?> = _progress.asStateFlow()

    private var restoreJob: Job? = null

    fun setSourceFolder(uri: String) {
        _sourceUri.value = uri
        _state.value = RestoreState.Scanning
        viewModelScope.launch {
            val result = engine.scanSourceFolder(uri)
            _cryptFileCount.value = result.cryptFileCount
            _otherFileCount.value = result.otherFileCount
            _state.value = if (_outputUri.value != null) RestoreState.ReadyToStart else RestoreState.SourceReady
        }
    }

    fun setOutputFolder(uri: String) {
        _outputUri.value = uri
        val current = _state.value
        if (current == RestoreState.SourceReady || current == RestoreState.ReadyToStart) {
            _state.value = RestoreState.ReadyToStart
        }
    }

    fun setCollisionPolicy(policy: RestoreCollisionPolicy) {
        _collisionPolicy.value = policy
    }

    fun startRestore(password: String) {
        val src = _sourceUri.value ?: return
        val out = _outputUri.value ?: return
        restoreJob = viewModelScope.launch {
            _state.value = RestoreState.Running
            _progress.value = null
            try {
                val result = engine.decryptAll(
                    sourceUri = src,
                    outputUri = out,
                    password = password,
                    collisionPolicy = _collisionPolicy.value,
                    onProgress = { _progress.value = it },
                )
                _state.value = RestoreState.Done(result)
            } finally {
                if (_state.value is RestoreState.Running) {
                    _state.value = if (_outputUri.value != null) {
                        RestoreState.ReadyToStart
                    } else {
                        RestoreState.SourceReady
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
        _sourceUri.value = null
        _outputUri.value = null
        _cryptFileCount.value = 0
        _otherFileCount.value = 0
        _progress.value = null
        _state.value = RestoreState.Idle
    }
}
