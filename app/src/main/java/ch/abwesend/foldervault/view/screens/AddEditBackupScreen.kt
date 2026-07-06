package ch.abwesend.foldervault.view.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.components.EnumDropdown
import ch.abwesend.foldervault.view.components.InfoIconButton
import ch.abwesend.foldervault.view.components.PasswordTextField
import ch.abwesend.foldervault.view.components.UnexpectedErrorDialog
import ch.abwesend.foldervault.view.util.displayNameFromUri
import ch.abwesend.foldervault.view.viewmodel.AddEditBackupViewModel
import ch.abwesend.foldervault.view.viewmodel.AddEditEvent
import ch.abwesend.foldervault.view.viewmodel.AddEditFormState
import ch.abwesend.foldervault.view.viewmodel.CloudSetupState
import ch.abwesend.foldervault.view.viewmodel.asString
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private const val RETENTION_DEFAULT_KEEP_LAST_N = 10
private const val RETENTION_DEFAULT_KEEP_DAYS = 90

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditBackupScreen(
    configId: String?,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddEditBackupViewModel = koinViewModel(parameters = { parametersOf(configId) }),
) {
    val form by viewModel.form.collectAsState()
    val unexpectedError by viewModel.unexpectedError.collectAsState()
    val context = LocalContext.current
    val currentOnSave by rememberUpdatedState(onSave)

    UnexpectedErrorDialog(error = unexpectedError, onDismiss = viewModel::dismissUnexpectedError)

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            viewModel.setSourceFolder(uri.toString(), displayNameFromUri(uri))
        }
    }

    val driveConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.handleDriveConsentResult(result.data)
    }

    LaunchedEffect(form.cloudSetup) {
        val state = form.cloudSetup
        if (state is CloudSetupState.ConsentRequired) {
            driveConsentLauncher.launch(
                IntentSenderRequest.Builder(state.pendingIntent.intentSender).build(),
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is AddEditEvent.Saved) currentOnSave()
        }
    }

    val titleRes = if (configId == null) R.string.add_backup_title else R.string.edit_backup_title
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.button_back_cd),
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        AddEditContent(
            form = form,
            modifier = Modifier.padding(innerPadding),
            onDisplayNameChange = viewModel::setDisplayName,
            onPickFolder = { folderPickerLauncher.launch(null) },
            onConnectDrive = viewModel::startDriveSetup,
            onScheduleChange = viewModel::setSchedule,
            onChangedFilePolicyChange = viewModel::setChangedFilePolicy,
            onNetworkPolicyChange = viewModel::setNetworkPolicy,
            onRequiresChargingChange = viewModel::setRequiresCharging,
            onEncryptionToggle = viewModel::setEncryptionEnabled,
            onPasswordChange = viewModel::setPassword,
            onPasswordConfirmChange = viewModel::setPasswordConfirm,
            onRetentionChange = viewModel::setRetentionPolicy,
            onSave = viewModel::save,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun AddEditContent(
    form: AddEditFormState,
    onDisplayNameChange: (String) -> Unit,
    onPickFolder: () -> Unit,
    onConnectDrive: () -> Unit,
    onScheduleChange: (BackupSchedule) -> Unit,
    onChangedFilePolicyChange: (ChangedFilePolicy) -> Unit,
    onNetworkPolicyChange: (NetworkPolicy) -> Unit,
    onRequiresChargingChange: (Boolean) -> Unit,
    onEncryptionToggle: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirmChange: (String) -> Unit,
    onRetentionChange: (RetentionPolicy) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicsSection(
            displayName = form.displayName,
            sourceFolderName = form.sourceFolderDisplayName,
            onDisplayNameChange = onDisplayNameChange,
            onPickFolder = onPickFolder,
        )

        HorizontalDivider()
        CloudSection(cloudSetup = form.cloudSetup, onConnect = onConnectDrive)

        HorizontalDivider()
        ScheduleSection(
            schedule = form.schedule,
            networkPolicy = form.networkPolicy,
            requiresCharging = form.requiresCharging,
            onScheduleChange = onScheduleChange,
            onNetworkPolicyChange = onNetworkPolicyChange,
            onRequiresChargingChange = onRequiresChargingChange,
        )

        HorizontalDivider()
        FileVersioningSection(
            changedFilePolicy = form.changedFilePolicy,
            retentionPolicy = form.retentionPolicy,
            onChangedFilePolicyChange = onChangedFilePolicyChange,
            onRetentionChange = onRetentionChange,
        )

        HorizontalDivider()
        EncryptionSection(
            enabled = form.encryptionEnabled,
            password = form.password,
            passwordConfirm = form.passwordConfirm,
            onToggle = onEncryptionToggle,
            onPasswordChange = onPasswordChange,
            onPasswordConfirmChange = onPasswordConfirmChange,
        )

        form.errorMessage?.let {
            Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = onSave,
            enabled = !form.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (form.isSaving) R.string.button_saving else R.string.button_save))
        }
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun BasicsSection(
    displayName: String,
    sourceFolderName: String,
    onDisplayNameChange: (String) -> Unit,
    onPickFolder: () -> Unit,
) {
    SectionHeader(stringResource(R.string.section_basics))
    OutlinedTextField(
        value = displayName,
        onValueChange = onDisplayNameChange,
        label = { Text(stringResource(R.string.label_display_name)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (sourceFolderName.isNotEmpty()) {
                stringResource(R.string.button_folder_selected, sourceFolderName)
            } else {
                stringResource(R.string.button_select_folder)
            },
        )
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun CloudSection(
    cloudSetup: CloudSetupState,
    onConnect: () -> Unit,
) {
    SectionHeader(stringResource(R.string.section_cloud_destination))
    val statusText = when (cloudSetup) {
        CloudSetupState.Idle -> stringResource(R.string.cloud_status_idle)
        CloudSetupState.Authorizing -> stringResource(R.string.cloud_status_authorizing)
        is CloudSetupState.ConsentRequired -> stringResource(R.string.cloud_status_awaiting_auth)
        CloudSetupState.CreatingFolder -> stringResource(R.string.cloud_status_creating_folder)
        is CloudSetupState.Done -> stringResource(
            R.string.cloud_status_done,
            cloudSetup.accountId,
            cloudSetup.folderName,
        )
        is CloudSetupState.Error -> stringResource(R.string.cloud_status_error, cloudSetup.message.asString())
    }
    Text(
        statusText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (cloudSetup !is CloudSetupState.Done) {
        Spacer(modifier = Modifier.height(8.dp))
        val busy = cloudSetup is CloudSetupState.Authorizing ||
            cloudSetup is CloudSetupState.ConsentRequired ||
            cloudSetup is CloudSetupState.CreatingFolder
        OutlinedButton(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.button_connect_drive))
        }
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun ScheduleSection(
    schedule: BackupSchedule,
    networkPolicy: NetworkPolicy,
    requiresCharging: Boolean,
    onScheduleChange: (BackupSchedule) -> Unit,
    onNetworkPolicyChange: (NetworkPolicy) -> Unit,
    onRequiresChargingChange: (Boolean) -> Unit,
) {
    SectionHeader(stringResource(R.string.section_schedule_network))
    EnumDropdown(
        label = stringResource(R.string.label_schedule),
        selected = schedule,
        options = BackupSchedule.entries.filter { it != BackupSchedule.USE_GLOBAL_DEFAULT },
        displayName = { stringResource(it.labelResId()) },
        onSelect = onScheduleChange,
    )
    Spacer(modifier = Modifier.height(12.dp))
    EnumDropdown(
        label = stringResource(R.string.label_network_policy),
        selected = networkPolicy,
        options = NetworkPolicy.entries,
        displayName = { stringResource(it.labelResId) },
        onSelect = onNetworkPolicyChange,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.label_requires_charging), modifier = Modifier.weight(1f))
        InfoIconButton(
            title = stringResource(R.string.info_requires_charging_title),
            body = stringResource(R.string.info_requires_charging_body),
        )
        Switch(checked = requiresCharging, onCheckedChange = onRequiresChargingChange)
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun FileVersioningSection(
    changedFilePolicy: ChangedFilePolicy,
    retentionPolicy: RetentionPolicy,
    onChangedFilePolicyChange: (ChangedFilePolicy) -> Unit,
    onRetentionChange: (RetentionPolicy) -> Unit,
) {
    SectionHeader(stringResource(R.string.section_file_versioning))

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        EnumDropdown(
            label = stringResource(R.string.label_changed_file_policy),
            selected = changedFilePolicy,
            options = ChangedFilePolicy.entries,
            displayName = { stringResource(it.labelResId) },
            onSelect = onChangedFilePolicyChange,
            modifier = Modifier.weight(1f),
        )
        InfoIconButton(
            title = stringResource(R.string.info_changed_file_policy_title),
            body = stringResource(R.string.info_changed_file_policy_body),
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
    RetentionPicker(policy = retentionPolicy, onRetentionChange = onRetentionChange)
}

@Suppress("MultipleEmitters")
@Composable
private fun RetentionPicker(
    policy: RetentionPolicy,
    onRetentionChange: (RetentionPolicy) -> Unit,
) {
    var keepNCount by remember(policy) {
        val initial = if (policy is RetentionPolicy.KeepLastN) policy.count else RETENTION_DEFAULT_KEEP_LAST_N
        mutableStateOf(initial.toString())
    }
    var keepDays by remember(policy) {
        val initial = if (policy is RetentionPolicy.KeepNewerThan) policy.days else RETENTION_DEFAULT_KEEP_DAYS
        mutableStateOf(initial.toString())
    }

    val retentionOptions = listOf(
        stringResource(R.string.retention_keep_all),
        stringResource(R.string.retention_keep_last_n_option),
        stringResource(R.string.retention_keep_newer_than_n_option),
    )
    val selectedIndex = when (policy) {
        RetentionPolicy.KeepAll -> 0
        is RetentionPolicy.KeepLastN -> 1
        is RetentionPolicy.KeepNewerThan -> 2
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        EnumDropdown(
            label = stringResource(R.string.label_retention_policy),
            selected = selectedIndex,
            options = retentionOptions.indices.toList(),
            displayName = { retentionOptions[it] },
            onSelect = { idx ->
                onRetentionChange(
                    when (idx) {
                        0 -> RetentionPolicy.KeepAll
                        1 -> RetentionPolicy.KeepLastN(keepNCount.toIntOrNull() ?: RETENTION_DEFAULT_KEEP_LAST_N)
                        else -> RetentionPolicy.KeepNewerThan(
                            keepDays.toIntOrNull() ?: RETENTION_DEFAULT_KEEP_DAYS,
                        )
                    },
                )
            },
            modifier = Modifier.weight(1f),
        )
        InfoIconButton(
            title = stringResource(R.string.info_retention_policy_title),
            body = stringResource(R.string.info_retention_policy_body),
        )
    }

    if (selectedIndex == 1) {
        RetentionCountField(
            value = keepNCount,
            onValueChange = { v ->
                keepNCount = v
                v.toIntOrNull()?.let { onRetentionChange(RetentionPolicy.KeepLastN(it)) }
            },
            labelRes = R.string.label_keep_n_copies,
        )
    } else if (selectedIndex == 2) {
        RetentionCountField(
            value = keepDays,
            onValueChange = { v ->
                keepDays = v
                v.toIntOrNull()?.let { onRetentionChange(RetentionPolicy.KeepNewerThan(it)) }
            },
            labelRes = R.string.label_keep_newer_than_days,
        )
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun RetentionCountField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes labelRes: Int,
) {
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Suppress("MultipleEmitters")
@Composable
private fun EncryptionSection(
    enabled: Boolean,
    password: String,
    passwordConfirm: String,
    onToggle: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirmChange: (String) -> Unit,
) {
    SectionHeader(stringResource(R.string.section_encryption))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.label_encrypt_backup), modifier = Modifier.weight(1f))
        InfoIconButton(
            title = stringResource(R.string.info_encryption_title),
            body = stringResource(R.string.info_encryption_body),
        )
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
    if (enabled) {
        Text(
            stringResource(R.string.warning_encryption_password),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        PasswordTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.label_password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        PasswordTextField(
            value = passwordConfirm,
            onValueChange = onPasswordConfirmChange,
            label = stringResource(R.string.label_confirm_password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@StringRes
private fun BackupSchedule.labelResId(): Int = when (this) {
    BackupSchedule.USE_GLOBAL_DEFAULT -> R.string.schedule_use_global_default
    BackupSchedule.MANUAL_ONLY -> R.string.schedule_manual_only
    BackupSchedule.DAILY -> R.string.schedule_daily
    BackupSchedule.WEEKLY -> R.string.schedule_weekly
    BackupSchedule.MONTHLY -> R.string.schedule_monthly
}

@Preview(showBackground = true)
@Composable
private fun AddEditBackupScreenPreview() {
    FolderVaultTheme {
        AddEditContent(
            form = AddEditFormState(displayName = "My documents", encryptionEnabled = true),
            onDisplayNameChange = {},
            onPickFolder = {},
            onConnectDrive = {},
            onScheduleChange = {},
            onChangedFilePolicyChange = {},
            onNetworkPolicyChange = {},
            onRequiresChargingChange = {},
            onEncryptionToggle = {},
            onPasswordChange = {},
            onPasswordConfirmChange = {},
            onRetentionChange = {},
            onSave = {},
        )
    }
}
