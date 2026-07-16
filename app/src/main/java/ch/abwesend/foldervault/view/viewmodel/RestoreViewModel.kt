package ch.abwesend.foldervault.view.viewmodel

import androidx.lifecycle.SavedStateHandle
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreMode
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface RestoreState {
    data object Idle : RestoreState
    data object Scanning : RestoreState
    data object SourceReady : RestoreState
    data object ReadyToStart : RestoreState
    data object Running : RestoreState
    data class Done(val result: RestoreResult) : RestoreState
}

data class RestoreUiState(
    val mode: RestoreMode = RestoreMode.WHOLE_FOLDER,
    val state: RestoreState = RestoreState.Idle,
    val sourceUri: String? = null,
    val outputUri: String? = null,
    val cryptFileCount: Int = 0,
    val otherFileCount: Int = 0,
    val collisionPolicy: RestoreCollisionPolicy = RestoreCollisionPolicy.SKIP,
    val progress: RestoreProgress? = null,
    val sourceFileUri: String? = null,
    val sourceFileName: String? = null,
    val suggestedOutputName: String? = null,
    val singleFilePassword: String = "",
)

/**
 * The restore mode and the single-file selection survive process death via [SavedStateHandle]:
 * the "Save as" picker round-trip leaves the app in the background, and if the process is killed
 * there, the picker result still arrives at the recreated activity — without the restored
 * selection it would be a silent no-op that leaves the picker-created empty document orphaned.
 * The password is deliberately NOT saved (see [setSingleFilePassword]); after process death the
 * restore runs with the empty password and surfaces as "wrong password", prompting re-entry.
 */
class RestoreViewModel(
    private val engine: IRestoreEngine,
    private val savedStateHandle: SavedStateHandle,
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(restoredUiState())
    val uiState: StateFlow<RestoreUiState> = _uiState.asStateFlow()

    private var restoreJob: Job? = null

    /** Rebuilds the saved-state-backed part of the UI state after process death. */
    private fun restoredUiState(): RestoreUiState {
        val mode = savedStateHandle.get<String>(KEY_MODE)
            ?.let { saved -> RestoreMode.entries.find { it.name == saved } }
            ?: RestoreMode.WHOLE_FOLDER
        val sourceFileUri = savedStateHandle.get<String>(KEY_SOURCE_FILE_URI)
        val sourceFileName = savedStateHandle.get<String>(KEY_SOURCE_FILE_NAME)
        return RestoreUiState(
            mode = mode,
            sourceFileUri = sourceFileUri,
            sourceFileName = sourceFileName,
            suggestedOutputName = sourceFileName?.let { suggestedOutputName(it) },
            state = if (sourceFileUri != null) RestoreState.SourceReady else RestoreState.Idle,
        )
    }

    private fun persistSingleFileSelection(uri: String?, name: String?) {
        savedStateHandle[KEY_SOURCE_FILE_URI] = uri
        savedStateHandle[KEY_SOURCE_FILE_NAME] = name
    }

    /**
     * Switches restore mode, discarding any half-finished selection so the two flows stay
     * separate. Re-selecting the already-active mode is a no-op: the segmented button fires its
     * click even for the selected segment, and a stray tap must not wipe the user's input.
     */
    fun setMode(mode: RestoreMode) {
        if (mode != _uiState.value.mode) {
            restoreJob?.cancel()
            restoreJob = null
            savedStateHandle[KEY_MODE] = mode.name
            persistSingleFileSelection(uri = null, name = null)
            _uiState.value = RestoreUiState(mode = mode)
        }
    }

    /** Records the single file the user picked and marks the source as ready. */
    fun setSourceFile(uri: String, name: String) {
        persistSingleFileSelection(uri = uri, name = name)
        _uiState.update {
            it.copy(
                sourceFileUri = uri,
                sourceFileName = name,
                suggestedOutputName = suggestedOutputName(name),
                state = RestoreState.SourceReady,
            )
        }
    }

    /**
     * Name to pre-fill the "Save as" picker with: the file's last path segment with the `.crypt`
     * suffix stripped, so `report.pdf.crypt` is suggested as `report.pdf`.
     */
    private fun suggestedOutputName(displayName: String): String =
        displayName.substringAfterLast('/').removeSuffix(".crypt")

    /**
     * The single-file password lives here (not in composable state) because it must survive the
     * activity recreation that a configuration change during the "Save as" picker round-trip
     * causes. It is deliberately not written to any saved state, so it is never persisted.
     */
    fun setSingleFilePassword(password: String) {
        _uiState.update { it.copy(singleFilePassword = password) }
    }

    fun setSourceFolder(uri: String) {
        _uiState.update { it.copy(sourceUri = uri, state = RestoreState.Scanning) }
        safeLaunch {
            try {
                val result = engine.scanSourceFolder(uri)
                _uiState.update { current ->
                    current.copy(
                        cryptFileCount = result.cryptFileCount,
                        otherFileCount = result.otherFileCount,
                        state = if (current.outputUri != null) RestoreState.ReadyToStart else RestoreState.SourceReady,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(state = RestoreState.Idle, sourceUri = null) }
                throw e
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
            restoreJob = safeLaunch {
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

    fun startSingleFileRestore(outputFileUri: String) {
        val src = _uiState.value.sourceFileUri
        if (src != null) {
            restoreJob = safeLaunch {
                _uiState.update { it.copy(state = RestoreState.Running, progress = null) }
                try {
                    val result = engine.decryptSingleFile(
                        sourceFileUri = src,
                        outputFileUri = outputFileUri,
                        password = _uiState.value.singleFilePassword,
                    )
                    _uiState.update { it.copy(state = RestoreState.Done(result)) }
                } finally {
                    _uiState.update { current ->
                        if (current.state is RestoreState.Running) {
                            current.copy(state = RestoreState.SourceReady)
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
        persistSingleFileSelection(uri = null, name = null)
        // Keep the selected mode so "Start over" clears the selection without flipping flows.
        _uiState.value = RestoreUiState(mode = _uiState.value.mode)
    }

    private companion object {
        private const val KEY_MODE = "restore.mode"
        private const val KEY_SOURCE_FILE_URI = "restore.singleFile.sourceUri"
        private const val KEY_SOURCE_FILE_NAME = "restore.singleFile.sourceName"
    }
}
