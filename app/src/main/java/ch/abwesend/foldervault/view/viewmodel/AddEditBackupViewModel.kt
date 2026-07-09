package ch.abwesend.foldervault.view.viewmodel

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.backup.SubFolderNameBuilder
import ch.abwesend.foldervault.domain.cloud.CloudAuthResult
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.CloudAccountRoot
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.result.rethrowCancellation
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.domain.storage.ReleaseSafPermissionIfUnusedUseCase
import ch.abwesend.foldervault.view.util.displayNameFromUri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val requiresCharging: Boolean = false,
    val encryptionEnabled: Boolean = false,
    /**
     * True when editing a config that was already encrypted at creation. The encryption password is
     * immutable after creation (INC-3): the password fields are hidden and the toggle is disabled, so
     * a save can never leave part of the archive encrypted under a different password than the rest.
     */
    val encryptionSettingsLocked: Boolean = false,
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
    private val releaseSafPermissionIfUnused: ReleaseSafPermissionIfUnusedUseCase,
    private val existingConfigId: String?,
) : BaseViewModel() {

    private val _form = MutableStateFlow(AddEditFormState())
    val form: StateFlow<AddEditFormState> = _form.asStateFlow()

    private val _events = MutableSharedFlow<AddEditEvent>()
    val events: SharedFlow<AddEditEvent> = _events.asSharedFlow()

    private var existingConfig: BackupConfig? = null

    init {
        safeLaunch {
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
        val settings = settingsRepo.settings.first()
        val root = settings.rootForAccount(config.cloudAccountIdentifier)
        val cloudState = if (root != null) {
            CloudSetupState.Done(root.rootFolderId, root.rootFolderName, config.cloudAccountIdentifier)
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
            requiresCharging = config.requiresCharging,
            encryptionEnabled = config.encryptionEnabled,
            encryptionSettingsLocked = config.encryptionEnabled,
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

    fun setRequiresCharging(enabled: Boolean) = updateForm { it.copy(requiresCharging = enabled) }

    /**
     * Toggles encryption. Ignored once [AddEditFormState.encryptionSettingsLocked] is set: an
     * already-encrypted config can neither change its password nor be switched to unencrypted, since
     * both would strand the existing cloud files under the old key (INC-3).
     */
    fun setEncryptionEnabled(enabled: Boolean) = updateForm {
        if (it.encryptionSettingsLocked) it else it.copy(encryptionEnabled = enabled)
    }

    fun setPassword(pw: String) = updateForm { it.copy(password = pw, errorMessage = null) }

    fun setPasswordConfirm(pw: String) = updateForm { it.copy(passwordConfirm = pw, errorMessage = null) }

    fun setRetentionPolicy(policy: RetentionPolicy) = updateForm { it.copy(retentionPolicy = policy) }

    /**
     * Starts (or restarts) the Drive connection flow.
     *
     * [accountName] is the Google account chosen in the system account picker (add mode). When
     * `null` (edit mode — the account is locked after creation), the existing config's account is
     * targeted instead, so a reconnect can never silently land on a different account.
     */
    fun startDriveSetup(accountName: String? = null) {
        val currentState = _form.value.cloudSetup
        val busy = currentState is CloudSetupState.Authorizing || currentState is CloudSetupState.CreatingFolder
        if (!busy) {
            val targetAccount = accountName ?: existingConfig?.cloudAccountIdentifier
            safeLaunch {
                updateForm { it.copy(cloudSetup = CloudSetupState.Authorizing, errorMessage = null) }
                when (val result = authorizer.authorize(targetAccount)) {
                    is CloudAuthResult.Authorized -> createCloudFolder(result.data)
                    is CloudAuthResult.ConsentRequired -> updateForm {
                        it.copy(cloudSetup = CloudSetupState.ConsentRequired(result.pendingIntent))
                    }
                    CloudAuthResult.Error -> setAuthFailedError()
                }
            }
        }
    }

    fun handleDriveConsentResult(data: Intent?) {
        safeLaunch {
            val result = authorizer.authorizeFromIntent(data)
            if (result is SuccessResult) {
                createCloudFolder(result.value)
            } else {
                setAuthFailedError()
            }
        }
    }

    private fun setAuthFailedError() = updateForm {
        it.copy(cloudSetup = CloudSetupState.Error(UiText.Resource(R.string.error_auth_failed)))
    }

    /**
     * Sets up the Drive root for the authorized account.
     *
     * Reuses that account's previously created `FolderVault_<UUID>` root from settings when one
     * exists; otherwise creates a fresh root and appends it to [AppSettings.cloudRoots]. Roots
     * are per account, so connecting a second account never touches the first account's root.
     * The per-config sub-folder is created later, at save time (see [ensureSubFolder]).
     */
    private suspend fun createCloudFolder(provider: ICloudStorageProvider) {
        updateForm { it.copy(cloudSetup = CloudSetupState.CreatingFolder) }
        val accountResult = provider.getAccountIdentifier()
        if (accountResult !is SuccessResult) {
            updateForm {
                it.copy(cloudSetup = CloudSetupState.Error(UiText.Resource(R.string.error_create_folder_failed)))
            }
            return
        }
        val account = accountResult.value

        val settings = settingsRepo.settings.first()
        val existingRoot = settings.rootForAccount(account)

        val (rootId, rootName) = if (existingRoot != null) {
            existingRoot.rootFolderId to existingRoot.rootFolderName
        } else {
            val createResult = provider.createRootFolder()
            if (createResult !is SuccessResult) {
                updateForm {
                    it.copy(cloudSetup = CloudSetupState.Error(UiText.Resource(R.string.error_create_folder_failed)))
                }
                return
            }
            val newRoot = createResult.value
            val accountRoot = CloudAccountRoot(
                accountIdentifier = account,
                rootFolderId = newRoot.id,
                rootFolderName = newRoot.name,
            )
            settingsRepo.update { s ->
                s.copy(cloudRoots = s.cloudRoots.filterNot { it.accountIdentifier == account } + accountRoot)
            }
            newRoot.id to newRoot.name
        }

        updateForm {
            it.copy(cloudSetup = CloudSetupState.Done(rootId, rootName, account))
        }
    }

    fun save() {
        val state = _form.value
        if (!validate(state)) return
        safeLaunch {
            updateForm { it.copy(isSaving = true, errorMessage = null) }
            try {
                val subFolder = ensureSubFolder(state) ?: return@safeLaunch
                val config = buildConfig(state, subFolder.first, subFolder.second) ?: return@safeLaunch
                val previousTreeUri = existingConfig?.sourceTreeUri
                configRepo.save(config)
                // Editing may repoint a config at a different folder. The old folder's persisted
                // SAF grant is then dead weight, so release it if no other config still uses it
                // (BUG-12). Done after save so the config already references the new URI, not the old.
                if (previousTreeUri != null && previousTreeUri != config.sourceTreeUri) {
                    releaseSafPermissionIfUnused(previousTreeUri, excludingConfigId = config.id)
                }
                val globalDefault = settingsRepo.settings.first().defaultSchedule
                scheduler.schedulePeriodicIfNeeded(
                    configId = config.id,
                    schedule = config.schedule,
                    networkPolicy = config.networkPolicy,
                    requiresCharging = config.requiresCharging,
                    globalDefault = globalDefault,
                )
                _events.emit(AddEditEvent.Saved)
            } catch (e: Exception) {
                e.rethrowCancellation()
                logger.error("Failed to save backup config", e)
                updateForm {
                    it.copy(
                        isSaving = false,
                        errorMessage = UiText.ResourceWithArg(R.string.error_save_failed, e.message ?: ""),
                    )
                }
            }
        }
    }

    /**
     * Resolves the per-config Drive sub-folder.
     *
     * Edit-mode configs keep their original sub-folder so renaming the displayName doesn't move
     * the existing Drive folder (v1 — the sub-folder name is immutable after first creation).
     * New configs eagerly create the sub-folder under the shared root via [getOrCreateChildFolder]
     * so that the persisted entity is always tied to a real Drive folder.
     */
    private suspend fun ensureSubFolder(state: AddEditFormState): Pair<String, String>? {
        val existing = existingConfig
        return if (existing != null && existing.cloudSubFolderId.isNotEmpty()) {
            existing.cloudSubFolderId to existing.cloudSubFolderName
        } else {
            createNewSubFolder(state)
        }
    }

    private suspend fun createNewSubFolder(state: AddEditFormState): Pair<String, String>? {
        val cloudState = state.cloudSetup as? CloudSetupState.Done
        val authResult = if (cloudState != null) authorizer.authorize(cloudState.accountId) else null
        val provider = (authResult as? CloudAuthResult.Authorized)?.data
        val errorRes: Int? = when {
            cloudState == null -> R.string.error_no_drive_connection
            provider == null -> R.string.error_auth_failed
            else -> null
        }
        if (errorRes != null) {
            updateForm { it.copy(isSaving = false, errorMessage = UiText.Resource(errorRes)) }
            return null
        }
        val subName = SubFolderNameBuilder.buildName(state.displayName, state.sourceTreeUri)
        val folderResult = provider!!.getOrCreateChildFolder(cloudState!!.folderId, subName)
        if (folderResult !is SuccessResult) {
            updateForm {
                it.copy(
                    isSaving = false,
                    errorMessage = UiText.Resource(R.string.error_create_folder_failed),
                )
            }
            return null
        }
        return folderResult.value.id to folderResult.value.name
    }

    private fun validate(state: AddEditFormState): Boolean {
        val error: UiText? = when {
            state.displayName.isBlank() -> UiText.Resource(R.string.error_display_name_required)
            state.sourceTreeUri.isBlank() -> UiText.Resource(R.string.error_no_source_folder)
            state.cloudSetup !is CloudSetupState.Done -> UiText.Resource(R.string.error_no_drive_connection)
            // Password fields are hidden when locked (INC-3), so don't validate them in that case.
            state.encryptionEnabled && !state.encryptionSettingsLocked && state.password.isBlank() ->
                UiText.Resource(R.string.error_password_required)
            state.encryptionEnabled && !state.encryptionSettingsLocked && state.password != state.passwordConfirm ->
                UiText.Resource(R.string.error_passwords_dont_match)
            else -> null
        }
        if (error != null) updateForm { it.copy(errorMessage = error) }
        return error == null
    }

    private fun buildConfig(
        state: AddEditFormState,
        subFolderId: String,
        subFolderName: String,
    ): BackupConfig? {
        val cloudState = state.cloudSetup as? CloudSetupState.Done ?: return null
        val id = existingConfig?.id ?: UUID.randomUUID().toString()

        val (encryptedBlob, saltBase64) = resolveEncryptionMaterial(state) ?: return null

        return BackupConfig(
            id = id,
            displayName = state.displayName,
            sourceTreeUri = state.sourceTreeUri,
            cloudProvider = "google_drive",
            cloudSubFolderId = subFolderId,
            cloudSubFolderName = subFolderName,
            cloudAccountIdentifier = cloudState.accountId,
            schedule = state.schedule,
            changedFilePolicy = state.changedFilePolicy,
            encryptionEnabled = state.encryptionEnabled,
            encryptedPasswordBlob = encryptedBlob,
            encryptionSaltBase64 = saltBase64,
            retentionPolicy = state.retentionPolicy,
            networkPolicy = state.networkPolicy,
            requiresCharging = state.requiresCharging,
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

    /**
     * Resolves the `(encryptedPasswordBlob, saltBase64)` pair to persist.
     *
     * Returns `Pair(null, null)` when encryption is disabled, and `null` (after surfacing an error on
     * the form) when password encryption fails. For a locked config the original blob and salt are
     * reused verbatim: re-encrypting here would silently re-key future uploads while old cloud files
     * keep the previous password, producing a mixed-password archive that half-fails on restore (INC-3).
     */
    private fun resolveEncryptionMaterial(state: AddEditFormState): Pair<String?, String?>? = when {
        !state.encryptionEnabled -> Pair(null, null)
        state.encryptionSettingsLocked ->
            Pair(existingConfig?.encryptedPasswordBlob, existingConfig?.encryptionSaltBase64)
        else -> {
            val result = encryptionRepo.encryptPassword(state.password)
            if (result is SuccessResult) {
                val salt = existingConfig?.encryptionSaltBase64
                    ?: Base64.getEncoder().encodeToString(cipher.generateBackupSalt())
                Pair(result.value, salt)
            } else {
                updateForm {
                    it.copy(isSaving = false, errorMessage = UiText.Resource(R.string.error_encryption_setup_failed))
                }
                null
            }
        }
    }

    private fun extractFolderDisplayName(uriString: String): String =
        if (uriString.isBlank()) "" else displayNameFromUri(Uri.parse(uriString))

    private fun updateForm(transform: (AddEditFormState) -> AddEditFormState) {
        _form.value = transform(_form.value)
    }
}
