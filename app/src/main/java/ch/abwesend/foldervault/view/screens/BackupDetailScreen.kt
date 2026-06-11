package ch.abwesend.foldervault.view.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.BackupMessage
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.viewmodel.BackupDetailViewModel
import ch.abwesend.foldervault.view.viewmodel.DetailEvent
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private const val MS_PER_MINUTE = 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDetailScreen(
    configId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupDetailViewModel = koinViewModel(parameters = { parametersOf(configId) }),
) {
    val config by viewModel.config.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val passwordCheckResult by viewModel.passwordCheckResult.collectAsState()
    val currentOnDelete by rememberUpdatedState(onDelete)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is DetailEvent.Deleted) currentOnDelete()
        }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteBackup()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    if (showPasswordDialog) {
        CheckPasswordDialog(
            result = passwordCheckResult,
            onCheck = viewModel::checkPassword,
            onDismiss = {
                showPasswordDialog = false
                viewModel.clearPasswordCheckResult()
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(config?.displayName ?: stringResource(R.string.detail_default_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.button_back_cd))
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, stringResource(R.string.edit_backup_title))
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.dialog_delete_title))
                    }
                },
            )
        },
    ) { innerPadding ->
        val cfg = config
        if (cfg == null) {
            Text(stringResource(R.string.loading), modifier = Modifier.padding(innerPadding).padding(16.dp))
        } else {
            DetailContent(
                config = cfg,
                messages = messages,
                onBackUpNow = viewModel::backUpNow,
                onTogglePause = viewModel::togglePause,
                onCheckPassword = { showPasswordDialog = true },
                onDismissMessage = { viewModel.dismiss(listOf(it)) },
                onDismissAll = viewModel::dismissAll,
                modifier = Modifier.padding(innerPadding),
                onMarkRead = { viewModel.markRead(it) },
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun DetailContent(
    config: BackupConfig,
    messages: List<BackupMessage>,
    onBackUpNow: () -> Unit,
    onTogglePause: () -> Unit,
    onCheckPassword: () -> Unit,
    onDismissMessage: (Long) -> Unit,
    onDismissAll: () -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("LambdaParameterEventTrailing")
    onMarkRead: (List<Long>) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ConfigInfoSection(config = config) }
        item { ActionButtonRow(config, onBackUpNow, onTogglePause, onCheckPassword) }
        item { HorizontalDivider() }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.messages_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (messages.isNotEmpty()) {
                    TextButton(onClick = {
                        onMarkRead(messages.map { it.id })
                        onDismissAll()
                    }) { Text(stringResource(R.string.button_clear_all)) }
                }
            }
        }
        if (messages.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_messages),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(messages, key = { it.id }) { msg ->
                MessageItem(
                    message = msg,
                    onDismiss = { onDismissMessage(msg.id) },
                    onMarkRead = { onMarkRead(listOf(msg.id)) },
                )
            }
        }
    }
}

@Composable
private fun ConfigInfoSection(config: BackupConfig) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InfoRow(stringResource(R.string.label_cloud_folder), config.cloudRootFolderName)
        InfoRow(stringResource(R.string.label_account), config.cloudAccountIdentifier)
        InfoRow(stringResource(R.string.label_schedule), stringResource(config.schedule.labelResId()))
        InfoRow(
            stringResource(R.string.label_network),
            stringResource(if (config.networkPolicy == NetworkPolicy.WIFI_ONLY) R.string.network_wifi_only else R.string.network_any_short),
        )
        InfoRow(
            stringResource(R.string.label_encryption),
            stringResource(if (config.encryptionEnabled) R.string.encryption_enabled else R.string.encryption_disabled),
        )
        InfoRow(stringResource(R.string.label_retention), config.retentionPolicy.displayName())
        Spacer(modifier = Modifier.height(8.dp))
        StatusSection(config)
    }
}

@Composable
private fun StatusSection(config: BackupConfig) {
    val statusColor = when (config.lastRunStatus) {
        BackupRunStatus.FAILED -> MaterialTheme.colorScheme.error
        BackupRunStatus.COMPLETED_WITH_WARNINGS -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.status_section_header), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(config.lastRunStatus.labelResId()), style = MaterialTheme.typography.bodyMedium, color = statusColor)
        if (config.totalFilesDiscovered > 0) {
            Text(
                stringResource(R.string.progress_text, config.filesUploadedTotal, config.totalFilesDiscovered),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        config.lastRunAt?.let { lastRun ->
            val ago = (System.currentTimeMillis() - lastRun) / MS_PER_MINUTE
            Text(
                stringResource(R.string.last_run_text, ago, config.filesUploaded, config.filesFailed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActionButtonRow(
    config: BackupConfig,
    onBackUpNow: () -> Unit,
    onTogglePause: () -> Unit,
    onCheckPassword: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onBackUpNow, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.button_back_up_now))
            }
            OutlinedButton(onClick = onTogglePause, modifier = Modifier.weight(1f)) {
                Icon(
                    if (config.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = null,
                )
                Text(stringResource(if (config.isPaused) R.string.button_resume else R.string.button_pause))
            }
        }
        if (config.encryptionEnabled) {
            OutlinedButton(onClick = onCheckPassword, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.button_check_password))
            }
        }
    }
}

@Composable
private fun MessageItem(
    message: BackupMessage,
    onDismiss: () -> Unit,
    onMarkRead: () -> Unit,
) {
    val borderColor = when (message.severity) {
        MessageSeverity.CRITICAL, MessageSeverity.ERROR -> MaterialTheme.colorScheme.error
        MessageSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        MessageSeverity.INFO -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (message.readAt == null) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(message.severity.labelResId()),
                    style = MaterialTheme.typography.labelSmall,
                    color = borderColor,
                    modifier = Modifier.weight(1f),
                )
                if (message.count > 1) {
                    Text(
                        "×${message.count}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val text = message.messageText ?: stringResource(message.type.labelResId())
            Text(text, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (message.readAt == null) {
                    TextButton(onClick = onMarkRead) { Text(stringResource(R.string.button_mark_read)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_dismiss)) }
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_delete_title)) },
        text = { Text(stringResource(R.string.dialog_delete_body)) },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.button_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
    )
}

@Composable
private fun CheckPasswordDialog(
    result: Boolean?,
    onCheck: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var candidate by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_check_password_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.dialog_check_password_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = candidate,
                    onValueChange = { candidate = it },
                    label = { Text(stringResource(R.string.label_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                result?.let { matches ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(if (matches) R.string.password_correct else R.string.password_incorrect),
                        color = if (matches) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onCheck(candidate) }) { Text(stringResource(R.string.button_check)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_close)) } },
    )
}

@Composable
private fun RetentionPolicy.displayName(): String = when (this) {
    RetentionPolicy.KeepAll -> stringResource(R.string.retention_keep_all)
    is RetentionPolicy.KeepLastN -> stringResource(R.string.retention_keep_last_n, count)
    is RetentionPolicy.KeepNewerThan -> stringResource(R.string.retention_keep_newer_than, days)
}

@StringRes
private fun BackupSchedule.labelResId(): Int = when (this) {
    BackupSchedule.USE_GLOBAL_DEFAULT -> R.string.schedule_default
    BackupSchedule.MANUAL_ONLY -> R.string.schedule_manual_only
    BackupSchedule.DAILY -> R.string.schedule_daily
    BackupSchedule.WEEKLY -> R.string.schedule_weekly
    BackupSchedule.MONTHLY -> R.string.schedule_monthly
}

@StringRes
private fun BackupRunStatus.labelResId(): Int = when (this) {
    BackupRunStatus.IDLE -> R.string.status_idle
    BackupRunStatus.RUNNING -> R.string.status_running
    BackupRunStatus.INITIAL_SYNC_IN_PROGRESS -> R.string.status_initial_sync_in_progress
    BackupRunStatus.UP_TO_DATE -> R.string.status_up_to_date
    BackupRunStatus.COMPLETED_WITH_WARNINGS -> R.string.status_completed_with_warnings
    BackupRunStatus.FAILED -> R.string.status_failed
}

@StringRes
private fun MessageSeverity.labelResId(): Int = when (this) {
    MessageSeverity.INFO -> R.string.severity_info
    MessageSeverity.WARNING -> R.string.severity_warning
    MessageSeverity.ERROR -> R.string.severity_error
    MessageSeverity.CRITICAL -> R.string.severity_critical
}

@StringRes
private fun MessageType.labelResId(): Int = when (this) {
    MessageType.AUTH_LOST -> R.string.msg_auth_lost
    MessageType.FOLDER_UNREADABLE -> R.string.msg_folder_unreadable
    MessageType.FILE_TOO_LARGE -> R.string.msg_file_too_large
    MessageType.UPLOAD_FAILED -> R.string.msg_upload_failed
    MessageType.ENCRYPTION_FAILED -> R.string.msg_encryption_failed
    MessageType.INITIAL_SYNC_COMPLETE -> R.string.msg_initial_sync_complete
    MessageType.QUOTA_EXCEEDED -> R.string.msg_quota_exceeded
    MessageType.UNRELIABLE_TIMESTAMPS -> R.string.msg_unreliable_timestamps
    MessageType.RATE_LIMITED -> R.string.msg_rate_limited
    MessageType.GENERIC_INFO -> R.string.msg_generic_info
    MessageType.GENERIC_WARNING -> R.string.msg_generic_warning
    MessageType.GENERIC_ERROR -> R.string.msg_generic_error
}

@Preview(showBackground = true)
@Composable
private fun BackupDetailPreview() {
    val sample = BackupConfig(
        id = "1",
        displayName = "Documents",
        sourceTreeUri = "content://com.example/tree/docs",
        cloudProvider = "google_drive",
        cloudRootFolderId = "abc",
        cloudRootFolderName = "FolderVault_123",
        cloudAccountIdentifier = "user@gmail.com",
        schedule = BackupSchedule.DAILY,
        changedFilePolicy = ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP,
        encryptionEnabled = true,
        encryptedPasswordBlob = null,
        encryptionSaltBase64 = null,
        retentionPolicy = RetentionPolicy.KeepAll,
        networkPolicy = NetworkPolicy.WIFI_ONLY,
        createdAt = System.currentTimeMillis(),
        lastRunAt = System.currentTimeMillis() - 7_200_000,
        lastRunStatus = BackupRunStatus.UP_TO_DATE,
        filesUploaded = 1204,
        filesSkipped = 0,
        filesFailed = 3,
        bytesUploaded = 0L,
        totalFilesDiscovered = 1207,
        filesUploadedTotal = 1204,
        lastRunCompletedNormally = true,
        isPaused = false,
    )
    val sampleMessages = listOf(
        BackupMessage(
            id = 1,
            backupConfigId = "1",
            runId = "r1",
            timestamp = System.currentTimeMillis(),
            severity = MessageSeverity.ERROR,
            type = MessageType.UPLOAD_FAILED,
            messageText = "3 files failed to upload",
            formatArgs = emptyList(),
            relativePath = null,
            count = 3,
            readAt = null,
            dismissed = false,
        ),
    )
    FolderVaultTheme {
        DetailContent(
            config = sample,
            messages = sampleMessages,
            onBackUpNow = {},
            onTogglePause = {},
            onCheckPassword = {},
            onDismissMessage = {},
            onDismissAll = {},
            onMarkRead = {},
        )
    }
}
