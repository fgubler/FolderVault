package ch.abwesend.foldervault.view.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.components.EnumDropdown
import ch.abwesend.foldervault.view.components.InfoIconButton
import ch.abwesend.foldervault.view.viewmodel.AddEditBackupViewModel
import ch.abwesend.foldervault.view.viewmodel.AddEditEvent
import ch.abwesend.foldervault.view.viewmodel.AddEditFormState
import ch.abwesend.foldervault.view.viewmodel.CloudSetupState
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
    val context = LocalContext.current
    val currentOnSave by rememberUpdatedState(onSave)

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val displayName = uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()
            viewModel.setSourceFolder(uri.toString(), displayName)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (configId == null) "Add backup" else "Edit backup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            onScheduleChange = onScheduleChange,
            onNetworkPolicyChange = onNetworkPolicyChange,
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
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = onSave,
            enabled = !form.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (form.isSaving) "Saving…" else "Save")
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
    SectionHeader("Basics")
    OutlinedTextField(
        value = displayName,
        onValueChange = onDisplayNameChange,
        label = { Text("Display name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
        Text(if (sourceFolderName.isNotEmpty()) "Folder: $sourceFolderName" else "Select source folder…")
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun CloudSection(
    cloudSetup: CloudSetupState,
    onConnect: () -> Unit,
) {
    SectionHeader("Cloud destination")
    val statusText = when (cloudSetup) {
        CloudSetupState.Idle -> "Not connected"
        CloudSetupState.Authorizing -> "Signing in…"
        is CloudSetupState.ConsentRequired -> "Awaiting authorization…"
        CloudSetupState.CreatingFolder -> "Creating backup folder…"
        is CloudSetupState.Done -> "Google Drive • ${cloudSetup.accountId}\nFolder: ${cloudSetup.folderName}"
        is CloudSetupState.Error -> "Error: ${cloudSetup.message}"
    }
    Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (cloudSetup !is CloudSetupState.Done) {
        Spacer(modifier = Modifier.height(8.dp))
        val busy = cloudSetup is CloudSetupState.Authorizing ||
            cloudSetup is CloudSetupState.ConsentRequired ||
            cloudSetup is CloudSetupState.CreatingFolder
        OutlinedButton(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Connect to Google Drive")
        }
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun ScheduleSection(
    schedule: BackupSchedule,
    networkPolicy: NetworkPolicy,
    onScheduleChange: (BackupSchedule) -> Unit,
    onNetworkPolicyChange: (NetworkPolicy) -> Unit,
) {
    SectionHeader("Schedule & network")
    EnumDropdown(
        label = "Schedule",
        selected = schedule,
        options = BackupSchedule.entries.filter { it != BackupSchedule.USE_GLOBAL_DEFAULT },
        displayName = { it.displayName() },
        onSelect = onScheduleChange,
    )
    Spacer(modifier = Modifier.height(12.dp))
    EnumDropdown(
        label = "Network policy",
        selected = networkPolicy,
        options = NetworkPolicy.entries,
        displayName = { it.displayName() },
        onSelect = onNetworkPolicyChange,
    )
}

@Suppress("MultipleEmitters")
@Composable
private fun FileVersioningSection(
    changedFilePolicy: ChangedFilePolicy,
    retentionPolicy: RetentionPolicy,
    onChangedFilePolicyChange: (ChangedFilePolicy) -> Unit,
    onRetentionChange: (RetentionPolicy) -> Unit,
) {
    SectionHeader("File versioning")

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        EnumDropdown(
            label = "Changed-file policy",
            selected = changedFilePolicy,
            options = ChangedFilePolicy.entries,
            displayName = { it.displayName() },
            onSelect = onChangedFilePolicyChange,
            modifier = Modifier.weight(1f),
        )
        InfoIconButton(
            title = "Changed-file policy",
            body = "FolderVault is built for files that rarely change. When a file you've " +
                "already backed up gets edited, this setting decides whether to upload it as " +
                "a new timestamped copy, overwrite the previous upload, or skip the change.",
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

    val retentionOptions = listOf("Keep all", "Keep last N copies", "Keep newer than N days")
    val selectedIndex = when (policy) {
        RetentionPolicy.KeepAll -> 0
        is RetentionPolicy.KeepLastN -> 1
        is RetentionPolicy.KeepNewerThan -> 2
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        EnumDropdown(
            label = "Retention policy",
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
            title = "Retention policy",
            body = "When files change repeatedly, copies accumulate in the cloud. Retention " +
                "lets FolderVault prune older copies — keep only the last N versions, or only " +
                "versions newer than N days. \"Keep all\" never deletes anything automatically.",
        )
    }

    if (selectedIndex == 1) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = keepNCount,
            onValueChange = { v ->
                keepNCount = v
                v.toIntOrNull()?.let { onRetentionChange(RetentionPolicy.KeepLastN(it)) }
            },
            label = { Text("Number of copies to keep") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    } else if (selectedIndex == 2) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = keepDays,
            onValueChange = { v ->
                keepDays = v
                v.toIntOrNull()?.let { onRetentionChange(RetentionPolicy.KeepNewerThan(it)) }
            },
            label = { Text("Keep files newer than (days)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
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
    SectionHeader("Encryption")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Encrypt backup", modifier = Modifier.weight(1f))
        InfoIconButton(
            title = "Encryption",
            body = "When enabled, your files are encrypted on this device before being " +
                "uploaded. Google Drive only sees scrambled data; only FolderVault on a " +
                "device with your password can read them back. If you forget the password, " +
                "the backup cannot be recovered.",
        )
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
    if (enabled) {
        Text(
            "Warning: If you forget the password, the backup cannot be recovered.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = passwordConfirm,
            onValueChange = onPasswordConfirmChange,
            label = { Text("Confirm password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
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

private fun BackupSchedule.displayName() = when (this) {
    BackupSchedule.USE_GLOBAL_DEFAULT -> "Use global default"
    BackupSchedule.MANUAL_ONLY -> "Manual only"
    BackupSchedule.DAILY -> "Daily"
    BackupSchedule.WEEKLY -> "Weekly"
    BackupSchedule.MONTHLY -> "Monthly"
}

private fun ChangedFilePolicy.displayName() = when (this) {
    ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP -> "Keep timestamped copy"
    ChangedFilePolicy.OVERWRITE -> "Overwrite"
    ChangedFilePolicy.IGNORE -> "Skip changed files"
}

private fun NetworkPolicy.displayName() = when (this) {
    NetworkPolicy.WIFI_ONLY -> "Wi-Fi only"
    NetworkPolicy.ANY -> "Any network"
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
            onEncryptionToggle = {},
            onPasswordChange = {},
            onPasswordConfirmChange = {},
            onRetentionChange = {},
            onSave = {},
        )
    }
}
