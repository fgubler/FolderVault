package ch.abwesend.foldervault.view.viewmodel

import androidx.lifecycle.SavedStateHandle
import ch.abwesend.foldervault.domain.crypto.Fvc1Header
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.restore.IRestoreEngine
import ch.abwesend.foldervault.domain.restore.IRestoreRunCoordinator
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreMode
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreRequest
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.domain.restore.RestoreRunState
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
    /**
     * Whether the running folder restore is hosted in the foreground service. When it is not, the
     * progress dialog warns the user to keep the app open — leaving it can have the run killed.
     */
    val restoreHostedInForegroundService: Boolean = false,
)

/**
 * The restore mode and the single-file selection survive process death via [SavedStateHandle]:
 * the "Save as" file-picker round-trip leaves the app in the background, and if the process is
 * killed there, the picker result still arrives at the recreated activity — without the restored
 * selection it would be a silent no-op. The password is deliberately NOT saved (see
 * [setSingleFilePassword]), so after process death the arriving result finds an empty password and
 * [startSingleFileRestore] declines to run — see there for why running anyway would be destructive.
 */
class RestoreViewModel(
    private val engine: IRestoreEngine,
    private val coordinator: IRestoreRunCoordinator,
    private val savedStateHandle: SavedStateHandle,
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(restoredUiState())
    val uiState: StateFlow<RestoreUiState> = _uiState.asStateFlow()

    init {
        observeRestoreRun()
    }

    /**
     * Mirrors the coordinator's run state into the UI state. Both restores are owned by
     * [IRestoreRunCoordinator] rather than by this ViewModel because a service-hosted run outlives
     * the screen — re-entering the restore screen while one is in flight has to pick the progress
     * back up rather than start over.
     */
    private fun observeRestoreRun() {
        safeLaunch {
            coordinator.state.collect { runState ->
                _uiState.update { current -> current.withRunState(runState) }
            }
        }
    }

    /**
     * Projects [runState] onto this UI state, but only when the run belongs to the mode the screen
     * is currently showing. Both flows are coordinator-hosted now, so the guard is a mode *match*
     * rather than "folder only": without it a folder run still going in the service would drive the
     * single-file screen's progress, and its result would land on top of the single-file one.
     *
     * Applied on every coordinator emission, but deliberately also *pulled* wherever the UI state
     * is rebuilt from scratch ([setMode]) or a start turns out to have been refused ([stage]). A
     * `StateFlow` re-emits only on change, and a folder run spends its opening minutes — the source
     * walk and the password probing — without publishing any progress at all. A screen rebuilt in
     * that window would otherwise show an empty, fully enabled form while a restore is in flight,
     * and the running restore's progress and counters would arrive later as though they described
     * whatever selection had been made in the meantime.
     */
    private fun RestoreUiState.withRunState(runState: RestoreRunState): RestoreUiState = when {
        runState is RestoreRunState.Running && runState.mode == mode -> copy(
            state = RestoreState.Running,
            progress = runState.progress,
            restoreHostedInForegroundService = runState.hostedInForegroundService,
        )
        runState is RestoreRunState.Finished && runState.mode == mode -> copy(
            state = RestoreState.Done(runState.result),
            // On success the password has served its purpose — drop it so it does not outlive the
            // restore. A failed attempt keeps it, so the user can correct a typo instead of
            // retyping from scratch. (Only the single-file flow keeps a password in this state.)
            singleFilePassword = if (runState.result is RestoreResult.Success) "" else singleFilePassword,
        )
        else -> this
    }

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
            coordinator.acknowledgeResult()
            savedStateHandle[KEY_MODE] = mode.name
            persistSingleFileSelection(uri = null, name = null)
            // Pulled rather than started blank: switching back to the folder mode while a run is
            // still in flight has to re-attach the screen to that run, and the coordinator will not
            // re-emit for a mode change of ours.
            _uiState.value = RestoreUiState(mode = mode).withRunState(coordinator.state.value)
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
        displayName.substringAfterLast('/').removeSuffix(Fvc1Header.CRYPT_FILE_SUFFIX)

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

    /**
     * Starts the whole-folder restore. Choosing (and, if needed, replacing) the host is the
     * coordinator's job, deliberately not this ViewModel's: a run tied to the screen's lifetime
     * would be lost the moment the user navigates away, which is the very problem the foreground
     * service exists to solve.
     *
     * A refusal — the coordinator already has a run staged or running — re-syncs the screen to that
     * run instead of being discarded. Swallowing it left the user in front of a form whose button
     * did nothing, and then handed them the *other* run's progress and counters as if they belonged
     * to the source and output folders they had just picked.
     */
    fun startRestore(password: String) {
        val snapshot = _uiState.value
        val src = snapshot.sourceUri
        val out = snapshot.outputUri
        if (src != null && out != null) {
            stage(
                RestoreRequest.WholeFolder(
                    sourceUri = src,
                    outputUri = out,
                    password = password,
                    collisionPolicy = snapshot.collisionPolicy,
                ),
            )
        }
    }

    /**
     * Decrypts the picked source into [outputFileUri] — the document the system "Save as" file
     * picker created (or handed back for an overwrite). The destination name and location are the
     * picker's business; this only needs the resulting document uri.
     *
     * Hosted by the coordinator like the folder flow, and for the same reason: a picked file can be
     * a multi-gigabyte video, and while this ran on `viewModelScope` simply navigating away
     * cancelled it and deleted whatever had been decrypted so far.
     *
     * An empty password declines the run instead of attempting it. The "Decrypt & save" button
     * already requires a non-empty password, so an empty one here can only mean the process was
     * killed during the picker round-trip and the (unsaved) password is gone. Running anyway would
     * fail the GCM tag check *after* the output document was truncated — silently destroying the
     * pre-existing file the user picked, with no user action at all. Leaving the state at
     * [RestoreState.SourceReady] instead lets the user re-enter the password and retry; the cost is
     * an orphaned empty document when the picker created a fresh one.
     */
    fun startSingleFileRestore(outputFileUri: String) {
        val snapshot = _uiState.value
        val src = snapshot.sourceFileUri
        if (src != null && snapshot.singleFilePassword.isNotEmpty()) {
            stage(
                RestoreRequest.SingleFile(
                    sourceFileUri = src,
                    outputFileUri = outputFileUri,
                    password = snapshot.singleFilePassword,
                ),
            )
        }
    }

    /**
     * Hands [request] to the coordinator, which picks the host. A refusal — it already has a run
     * staged or running — re-syncs the screen to that run instead of being discarded. Swallowing it
     * left the user in front of a form whose button did nothing, and then handed them the *other*
     * run's progress and counters as if they belonged to the selection just made.
     */
    private fun stage(request: RestoreRequest) {
        if (!coordinator.start(request)) {
            logger.warning("A restore is already in flight — showing that run instead of starting a new one")
        }
        _uiState.update { it.withRunState(coordinator.state.value) }
    }

    /**
     * Stops the running restore *cooperatively* — never by cancelling its coroutine, which would
     * make the engine's result be discarded. A folder run then reports how many files it already
     * restored; a single-file run reports nothing, because its half-written output is deleted.
     */
    fun cancel() {
        coordinator.requestStop()
    }

    fun reset() {
        coordinator.acknowledgeResult()
        persistSingleFileSelection(uri = null, name = null)
        // Keep the selected mode so "Start over" clears the selection without flipping flows, and
        // pull the run state for the same reason [setMode] does: a blank rebuild must never hide a
        // restore that is still in flight.
        _uiState.value = RestoreUiState(mode = _uiState.value.mode).withRunState(coordinator.state.value)
    }

    private companion object {
        private const val KEY_MODE = "restore.mode"
        private const val KEY_SOURCE_FILE_URI = "restore.singleFile.sourceUri"
        private const val KEY_SOURCE_FILE_NAME = "restore.singleFile.sourceName"
    }
}
