package ch.abwesend.foldervault.view.viewmodel

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.BackupMeta
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.cloud.CloudAuthResult
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.view.util.displayNameFromUri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class AddEditFormState(
    val displayName: String = "",
    val sourceTreeUri: String = "",
    val sourceFolderDisplayName: String = "",
    val cloudSetup: CloudSetupState = CloudSetupState.Idle,
    val schedule: BackupSchedule = BackupSchedule.DAILY,
    val changedFilePolicy: ChangedFilePolicy = ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP,
    val networkPolicy: NetworkPolicy = NetworkPolicy.WIFI_ONLY,
    val encryptionEnabled: Boolean = false,
    val password: String = "",
    val passwordConfirm: String = "",
    val retentionPolicy: RetentionPolicy = RetentionPolicy.KeepAll,
    val isSaving: Boolean = false,
    val errorMessage: UiText? = null,
    val isEditMode: Boolean = false,
)

sealed interface CloudSetupState {
    data object Idle : CloudSetupState
    data object Authorizing : CloudSetupState
    data class ConsentRequired(val pendingIntent: PendingIntent) : CloudSetupState
    data object CreatingFolder : CloudSetupState
    data class Done(
        val folderId: String,
        val folderName: String,
        val accountId: String,
    ) : CloudSetupState
    data class Error(val message: UiText) : CloudSetupState
}

sealed interface AddEditEvent {
    data object Saved : AddEditEvent
}

@Suppress("TooManyFunctions")
class AddEditBackupViewModel(
    private val configRepo: IBackupConfigRepository,
    private val scheduler: IBackupScheduler,
    private val authorizer: ICloudAuthorizer,
    private val encryptionRepo: IEncryptionRepository,
    private val cipher: IFvc1Cipher,
    private val settingsRepo: IAppSettingsRepository,
    private val existingConfigId: String?,
) : ViewModel() {

    private val _form = MutableStateFlow(AddEditFormState())
    val form: StateFlow<AddEditFormState> = _form.asStateFlow()

    private val _events = MutableSharedFlow<AddEditEvent>()
    val events: SharedFlow<AddEditEvent> = _events.asSharedFlow()

    private var existingConfig: BackupConfig? = null

    init {
        viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            if (existingConfigId != null) {
                loadExisting(existingConfigId, settings.defaultSchedule)
            } else {
                _form.value = _form.value.copy(
                    schedule = settings.defaultSchedule,
                    changedFilePolicy = settings.defaultChangedFilePolicy,
                    networkPolicy = settings.defaultNetworkPolicy,
                )
            }
        }
    }

    private suspend fun loadExisting(id: String, globalDefaultSchedule: BackupSchedule) {
        val config = configRepo.getById(id).first() ?: return
        existingConfig = config
        val cloudState = if (config.cloudRootFolderId.isNotEmpty()) {
            CloudSetupState.Done(config.cloudRootFolderId, config.cloudRootFolderName, config.cloudAccountIdentifier)
        } else {
            CloudSetupState.Idle
        }
        val resolvedSchedule = if (config.schedule == BackupSchedule.USE_GLOBAL_DEFAULT) {
            globalDefaultSchedule
        } else {
            config.schedule
        }
        _form.value = AddEditFormState(
            displayName = config.displayName,
            sourceTreeUri = config.sourceTreeUri,
            sourceFolderDisplayName = extractFolderDisplayName(config.sourceTreeUri),
            cloudSetup = cloudState,
            schedule = resolvedSchedule,
            changedFilePolicy = config.changedFilePolicy,
            networkPolicy = config.networkPolicy,
            encryptionEnabled = config.encryptionEnabled,
            retentionPolicy = config.retentionPolicy,
            isEditMode = true,
        )
    }

    fun setDisplayName(name: String) = updateForm { it.copy(displayName = name, errorMessage = null) }

    fun setSourceFolder(uri: String, displayName: String) =
        updateForm { it.copy(sourceTreeUri = uri, sourceFolderDisplayName = displayName, errorMessage = null) }

    fun setSchedule(schedule: BackupSchedule) = updateForm { it.copy(schedule = schedule) }

    fun setChangedFilePolicy(policy: ChangedFilePolicy) = updateForm { it.copy(changedFilePolicy = policy) }

    fun setNetworkPolicy(policy: NetworkPolicy) = updateForm { it.copy(networkPolicy = policy) }

    fun setEncryptionEnabled(enabled: Boolean) = updateForm { it.copy(encryptionEnabled = enabled) }

    fun setPassword(pw: String) = updateForm { it.copy(password = pw, errorMessage = null) }

    fun setPasswordConfirm(pw: String) = updateForm { it.copy(passwordConfirm = pw, errorMessage = null) }

    fun setRetentionPolicy(policy: RetentionPolicy) = updateForm { it.copy(retentionPolicy = policy) }

    fun startDriveSetup() {
        if (_form.value.cloudSetup is CloudSetupState.Done) return
        viewModelScope.launch {
            updateForm { it.copy(cloudSetup = CloudSetupState.Authorizing, errorMessage = null) }
            when (val result = authorizer.authorize()) {
                is CloudAuthResult.Authorized -> createCloudFolder(result.data)
                is CloudAuthResult.ConsentRequired ->
                    updateForm { it.copy(cloudSetup = CloudSetupState.ConsentRequired(result.pendingIntent)) }
                CloudAuthResult.Error ->
                    updateForm { it.copy(cloudSetup = CloudSetupState.Error(UiText.Resource(R.string.error_auth_failed))) }
            }
        }
    }

    fun handleDriveConsentResult(data: Intent?) {
        viewModelScope.launch {
            val result = authorizer.authorizeFromIntent(data)
            if (result is SuccessResult) {
                createCloudFolder(result.value)
            } else {
                updateForm { it.copy(cloudSetup = CloudSetupState.Error(UiText.Resource(R.string.error_auth_failed))) }
            }
        }
    }

    private suspend fun createCloudFolder(provider: ICloudStorageProvider) {
        updateForm { it.copy(cloudSetup = CloudSetupState.CreatingFolder) }
        val folderResult = provider.createRootFolder()
        val accountResult = provider.getAccountIdentifier()
        if (folderResult is SuccessResult && accountResult is SuccessResult) {
            val folder = folderResult.value
            updateForm {
                it.copy(cloudSetup = CloudSetupState.Done(folder.id, folder.name, accountResult.value))
            }
            writeMetaFile(provider, folder.id)
        } else {
            updateForm { it.copy(cloudSetup = CloudSetupState.Error(UiText.Resource(R.string.error_create_folder_failed))) }
        }
    }

    private suspend fun writeMetaFile(provider: ICloudStorageProvider, folderId: String) {
        val form = _form.value
        val meta = BackupMeta(
            displayName = form.displayName,
            createdAt = Instant.now().toString(),
            encrypted = form.encryptionEnabled,
        )
        provider.writeRootMetadata(
            folderId,
            BackupMeta.CLOUD_FILE_NAME,
            Json.encodeToString(meta).toByteArray(Charsets.UTF_8),
        )
    }

    fun save() {
        val state = _form.value
        if (!validate(state)) return
        viewModelScope.launch {
            updateForm { it.copy(isSaving = true, errorMessage = null) }
            try {
                val config = buildConfig(state) ?: return@launch
                configRepo.save(config)
                val globalDefault = settingsRepo.settings.first().defaultSchedule
                scheduler.schedulePeriodicIfNeeded(config.id, config.schedule, config.networkPolicy, globalDefault)
                _events.emit(AddEditEvent.Saved)
            } catch (e: Exception) {
                updateForm {
                    it.copy(
                        isSaving = false,
                        errorMessage = UiText.ResourceWithArg(R.string.error_save_failed, e.message ?: ""),
                    )
                }
            }
        }
    }

    private fun validate(state: AddEditFormState): Boolean {
        val error: UiText? = when {
            state.displayName.isBlank() -> UiText.Resource(R.string.error_display_name_required)
            state.sourceTreeUri.isBlank() -> UiText.Resource(R.string.error_no_source_folder)
            state.cloudSetup !is CloudSetupState.Done -> UiText.Resource(R.string.error_no_drive_connection)
            state.encryptionEnabled && state.password.isBlank() -> UiText.Resource(R.string.error_password_required)
            state.encryptionEnabled && state.password != state.passwordConfirm -> UiText.Resource(R.string.error_passwords_dont_match)
            else -> null
        }
        if (error != null) updateForm { it.copy(errorMessage = error) }
        return error == null
    }

    private suspend fun buildConfig(state: AddEditFormState): BackupConfig? {
        val cloudState = state.cloudSetup as? CloudSetupState.Done ?: return null
        val id = existingConfig?.id ?: UUID.randomUUID().toString()

        val (encryptedBlob, saltBase64) = if (state.encryptionEnabled) {
            val result = encryptionRepo.encryptPassword(state.password)
            if (result !is SuccessResult) {
                updateForm { it.copy(isSaving = false, errorMessage = UiText.Resource(R.string.error_encryption_setup_failed)) }
                return null
            }
            val salt = existingConfig?.encryptionSaltBase64
                ?: Base64.getEncoder().encodeToString(cipher.generateBackupSalt())
            Pair(result.value, salt)
        } else {
            Pair(null, null)
        }

        return BackupConfig(
            id = id,
            displayName = state.displayName,
            sourceTreeUri = state.sourceTreeUri,
            cloudProvider = "google_drive",
            cloudRootFolderId = cloudState.folderId,
            cloudRootFolderName = cloudState.folderName,
            cloudAccountIdentifier = cloudState.accountId,
            schedule = state.schedule,
            changedFilePolicy = state.changedFilePolicy,
            encryptionEnabled = state.encryptionEnabled,
            encryptedPasswordBlob = encryptedBlob,
            encryptionSaltBase64 = saltBase64,
            retentionPolicy = state.retentionPolicy,
            networkPolicy = state.networkPolicy,
            createdAt = existingConfig?.createdAt ?: System.currentTimeMillis(),
            lastRunAt = existingConfig?.lastRunAt,
            lastRunStatus = existingConfig?.lastRunStatus ?: BackupRunStatus.IDLE,
            filesUploaded = existingConfig?.filesUploaded ?: 0,
            filesSkipped = existingConfig?.filesSkipped ?: 0,
            filesFailed = existingConfig?.filesFailed ?: 0,
            bytesUploaded = existingConfig?.bytesUploaded ?: 0L,
            totalFilesDiscovered = existingConfig?.totalFilesDiscovered ?: 0,
            filesUploadedTotal = existingConfig?.filesUploadedTotal ?: 0,
            lastRunCompletedNormally = existingConfig?.lastRunCompletedNormally ?: false,
            isPaused = existingConfig?.isPaused ?: false,
        )
    }

    private fun extractFolderDisplayName(uriString: String): String =
        if (uriString.isBlank()) "" else displayNameFromUri(Uri.parse(uriString))

    private fun updateForm(transform: (AddEditFormState) -> AddEditFormState) {
        _form.value = transform(_form.value)
    }
}
