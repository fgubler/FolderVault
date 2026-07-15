package ch.abwesend.foldervault.view.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.backup.BackupMessage
import ch.abwesend.foldervault.domain.backup.StartManualBackupUseCase
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.components.PasswordTextField
import ch.abwesend.foldervault.view.components.UnexpectedErrorDialog
import ch.abwesend.foldervault.view.util.formatRelativeAgo
import ch.abwesend.foldervault.view.viewmodel.BackupDetailViewModel
import ch.abwesend.foldervault.view.viewmodel.CloudDeleteState
import ch.abwesend.foldervault.view.viewmodel.DetailEvent
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val CLOUD_FOLDER_WIDTH_FRACTION = 0.75f

/**
 * Creates the screen's [BackupDetailViewModel], consuming [autoStartBackup] as a one-shot flag:
 * the nav entry keeps `autoStartBackup = true` on the back stack forever, but only the very
 * first ViewModel may see it — one re-created for the same entry (process-death restore) would
 * otherwise re-open the backup prompts the user already dismissed. The consumed state lives in
 * [rememberSaveable], so it survives exactly as long as the back-stack entry itself.
 */
@Composable
private fun autoStartConsumingViewModel(configId: String, autoStartBackup: Boolean): BackupDetailViewModel {
    var autoStartPending by rememberSaveable { mutableStateOf(autoStartBackup) }
    val viewModel: BackupDetailViewModel =
        koinViewModel(parameters = { parametersOf(configId, autoStartPending) })
    LaunchedEffect(Unit) { autoStartPending = false }
    return viewModel
}

private fun formatMessageTimestamp(epochMillis: Long, locale: Locale): String {
    val formatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(locale)
    return formatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDetailScreen(
    configId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShowRunHistory: () -> Unit,
    modifier: Modifier = Modifier,
    autoStartBackup: Boolean = false,
    viewModel: BackupDetailViewModel = autoStartConsumingViewModel(configId, autoStartBackup),
) {
    val config by viewModel.config.collectAsState()
    val continuesAutomatically by viewModel.continuesAutomatically.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val passwordCheckResult by viewModel.passwordCheckResult.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val unexpectedError by viewModel.unexpectedError.collectAsState()
    val showMeteredOverridePrompt by viewModel.showMeteredOverridePrompt.collectAsState()
    val showChargingOverridePrompt by viewModel.showChargingOverridePrompt.collectAsState()
    val currentOnDelete by rememberUpdatedState(onDelete)

    UnexpectedErrorDialog(error = unexpectedError, onDismiss = viewModel::dismissUnexpectedError)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is DetailEvent.Deleted) currentOnDelete()
        }
    }

    val cloudDeleteState by viewModel.cloudDeleteState.collectAsState()
    CloudFolderDeleteHandler(
        state = cloudDeleteState,
        onConsentResult = viewModel::handleDriveConsentResult,
        onAcknowledgeFailure = viewModel::acknowledgeFolderDeleteFailure,
    )

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }

    BackupDetailDialogs(
        showDeleteDialog = showDeleteDialog,
        showPasswordDialog = showPasswordDialog,
        showMeteredOverridePrompt = showMeteredOverridePrompt,
        showChargingOverridePrompt = showChargingOverridePrompt,
        passwordCheckResult = passwordCheckResult,
        onDeleteConfirm = { alsoDeleteCloudFolder ->
            showDeleteDialog = false
            viewModel.deleteBackup(alsoDeleteCloudFolder)
        },
        onDeleteDismiss = { showDeleteDialog = false },
        onCheckPassword = viewModel::checkPassword,
        onPasswordDismiss = {
            showPasswordDialog = false
            viewModel.clearPasswordCheckResult()
        },
        onMeteredOverrideConfirm = viewModel::confirmMeteredOverride,
        onMeteredOverrideDismiss = viewModel::dismissMeteredOverride,
        onChargingOverrideConfirm = viewModel::confirmChargingOverride,
        onChargingOverrideDismiss = viewModel::dismissChargingOverride,
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            BackupDetailTopBar(
                title = config?.displayName ?: stringResource(R.string.detail_default_title),
                onBack = onBack,
                onShowRunHistory = onShowRunHistory,
                onEdit = onEdit,
                onDelete = { showDeleteDialog = true },
                // Deleting is blocked while a run is active (either host): deleting the config —
                // and especially its Drive folder — mid-upload would fail or orphan the in-flight
                // run. The button re-enables as soon as the run finishes.
                deleteEnabled = !isRunning,
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
                isRunning = isRunning,
                continuesAutomatically = continuesAutomatically,
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

/** Top bar of the detail screen with navigation back plus run-history, edit and delete actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupDetailTopBar(
    title: String,
    onBack: () -> Unit,
    onShowRunHistory: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.button_back_cd),
                )
            }
        },
        actions = {
            IconButton(onClick = onShowRunHistory) {
                Icon(Icons.Default.History, stringResource(R.string.backup_run_history_title))
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, stringResource(R.string.edit_backup_title))
            }
            IconButton(onClick = onDelete, enabled = deleteEnabled) {
                Icon(Icons.Default.Delete, stringResource(R.string.dialog_delete_title))
            }
        },
    )
}

@Suppress("LongParameterList")
@Composable
private fun DetailContent(
    config: BackupConfig,
    messages: List<BackupMessage>,
    isRunning: Boolean,
    continuesAutomatically: Boolean,
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
        if (showInitialSyncBanner(config, isRunning)) {
            item {
                InitialSyncIncompleteBanner(
                    config = config,
                    continuesAutomatically = continuesAutomatically,
                    onContinue = onBackUpNow,
                )
            }
        }
        item { ActionButtonRow(config, isRunning, onBackUpNow, onTogglePause, onCheckPassword) }
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
        CloudFolderRow(config.cloudSubFolderName, config.cloudSubFolderId)
        InfoRow(stringResource(R.string.label_account), config.cloudAccountIdentifier)
        InfoRow(stringResource(R.string.label_schedule), stringResource(config.schedule.labelResId()))
        val networkLabelRes = if (config.networkPolicy == NetworkPolicy.WIFI_ONLY) {
            R.string.network_wifi_only
        } else {
            R.string.network_any_short
        }
        InfoRow(stringResource(R.string.label_network), stringResource(networkLabelRes))
        InfoRow(
            stringResource(R.string.label_only_while_charging),
            stringResource(if (config.requiresCharging) R.string.common_yes else R.string.common_no),
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
        Text(
            stringResource(R.string.status_section_header),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(config.lastRunStatus.labelResId),
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor,
        )
        if (config.totalFilesDiscovered > 0) {
            Text(
                stringResource(R.string.progress_text, config.filesUploadedTotal, config.totalFilesDiscovered),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        config.lastRunAt?.let { lastRun ->
            val agoText = formatRelativeAgo(lastRun)
            Text(
                stringResource(R.string.last_run_text, agoText, config.filesUploaded, config.filesFailed),
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
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CloudFolderRow(folderName: String, folderId: String) {
    val context = LocalContext.current
    var showOpenDriveError by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(CLOUD_FOLDER_WIDTH_FRACTION)) {
            Text(
                "${stringResource(R.string.label_cloud_folder)}: ",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(folderName, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(
            onClick = {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    "https://drive.google.com/drive/folders/$folderId".toUri(),
                )
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    logger.info("No activity found to open Drive folder link, showing fallback dialog")
                    showOpenDriveError = true
                }
            },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                stringResource(R.string.button_open_in_drive),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showOpenDriveError) {
        AlertDialog(
            onDismissRequest = { showOpenDriveError = false },
            title = { Text(stringResource(R.string.dialog_open_drive_error_title)) },
            text = { Text(stringResource(R.string.dialog_open_drive_error_body)) },
            confirmButton = {
                TextButton(onClick = { showOpenDriveError = false }) {
                    Text(stringResource(R.string.button_close))
                }
            },
        )
    }
}

@Composable
private fun ActionButtonRow(
    config: BackupConfig,
    isRunning: Boolean,
    onBackUpNow: () -> Unit,
    onTogglePause: () -> Unit,
    onCheckPassword: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onBackUpNow,
                enabled = !isRunning,
                modifier = Modifier.weight(1f),
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.button_backing_up))
                } else {
                    Text(stringResource(R.string.button_back_up_now))
                }
            }
            OutlinedButton(onClick = onTogglePause, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (config.isPaused) R.string.button_enable else R.string.button_disable))
            }
        }
        if (config.encryptionEnabled) {
            OutlinedButton(onClick = onCheckPassword, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.button_check_password))
            }
        }
    }
}

/**
 * The banner appears when an initial sync was interrupted (service stopped, run cancelled or
 * failed mid-sync) and nothing is currently running — a fresh config (IDLE) has nothing to
 * "continue" yet, and a paused config must not invite a run that would be skipped.
 */
private fun showInitialSyncBanner(config: BackupConfig, isRunning: Boolean): Boolean =
    StartManualBackupUseCase.needsForegroundService(config.lastRunStatus, config.totalFilesDiscovered) &&
        config.lastRunStatus != BackupRunStatus.IDLE &&
        !isRunning &&
        !config.isPaused

/**
 * Reassuring "this is not an error" banner for an interrupted initial sync (spec §7.6), with a
 * one-tap way to continue in the foreground service — going through the same prompt chain as
 * the "Back up now" button. A manual-only config gets a text without the "continues
 * automatically" promise: nothing is scheduled for it, so only the tap continues the sync. An
 * interrupted baseline pass ([BackupConfig.isBaselinePending]) gets "checking existing files"
 * wording without an upload count — the pass records metadata and uploads nothing.
 */
@Composable
private fun InitialSyncIncompleteBanner(
    config: BackupConfig,
    continuesAutomatically: Boolean,
    onContinue: () -> Unit,
) {
    val textRes = when {
        config.isBaselinePending && continuesAutomatically -> R.string.detail_initial_sync_indexing
        config.isBaselinePending -> R.string.detail_initial_sync_indexing_manual
        continuesAutomatically -> R.string.detail_initial_sync_incomplete
        else -> R.string.detail_initial_sync_incomplete_manual
    }
    val bannerText = if (config.isBaselinePending) {
        stringResource(textRes)
    } else {
        stringResource(textRes, config.filesUploadedTotal, config.totalFilesDiscovered)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = bannerText,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onContinue) {
                    Text(stringResource(R.string.detail_initial_sync_continue))
                }
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
        val locale = LocalConfiguration.current.locales[0]
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(message.severity.labelResId),
                    style = MaterialTheme.typography.labelSmall,
                    color = borderColor,
                )
                Text(
                    text = formatMessageTimestamp(message.timestamp, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                if (message.count > 1) {
                    Text(
                        "×${message.count}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val text = message.messageText ?: stringResource(message.type.labelResId)
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

@Suppress("LongParameterList")
@Composable
private fun BackupDetailDialogs(
    showDeleteDialog: Boolean,
    showPasswordDialog: Boolean,
    showMeteredOverridePrompt: Boolean,
    showChargingOverridePrompt: Boolean,
    passwordCheckResult: Boolean?,
    onDeleteConfirm: (alsoDeleteCloudFolder: Boolean) -> Unit,
    onDeleteDismiss: () -> Unit,
    onCheckPassword: (String) -> Unit,
    onPasswordDismiss: () -> Unit,
    onMeteredOverrideConfirm: () -> Unit,
    onMeteredOverrideDismiss: () -> Unit,
    onChargingOverrideConfirm: () -> Unit,
    onChargingOverrideDismiss: () -> Unit,
) {
    if (showDeleteDialog) {
        DeleteConfirmDialog(onConfirm = onDeleteConfirm, onDismiss = onDeleteDismiss)
    }
    if (showPasswordDialog) {
        CheckPasswordDialog(
            result = passwordCheckResult,
            onCheck = onCheckPassword,
            onDismiss = onPasswordDismiss,
        )
    }
    if (showMeteredOverridePrompt) {
        MeteredOverrideDialog(
            onConfirm = onMeteredOverrideConfirm,
            onDismiss = onMeteredOverrideDismiss,
        )
    }
    if (showChargingOverridePrompt) {
        ChargingOverrideDialog(
            onConfirm = onChargingOverrideConfirm,
            onDismiss = onChargingOverrideDismiss,
        )
    }
}

@Composable
private fun MeteredOverrideDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_metered_override_title)) },
        text = { Text(stringResource(R.string.dialog_metered_override_body)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.button_back_up_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        },
    )
}

/**
 * Warns that a charging-only backup was triggered while the device is unplugged. "Back up anyway"
 * runs this once without the charging requirement; cancelling starts nothing (the normal schedule
 * still runs the backup once the device is charging).
 */
@Composable
private fun ChargingOverrideDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_charging_override_title)) },
        text = { Text(stringResource(R.string.dialog_charging_override_body)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.button_back_up_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        },
    )
}

/**
 * Confirms deleting the config. The "also delete the Drive folder" checkbox is unchecked by default
 * so nothing on Google Drive is removed by accident — the user has to opt in, and a red caption then
 * spells out that the deletion is permanent. [onConfirm] carries the checkbox state.
 */
@Composable
private fun DeleteConfirmDialog(onConfirm: (alsoDeleteCloudFolder: Boolean) -> Unit, onDismiss: () -> Unit) {
    var alsoDeleteCloudFolder by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_delete_title)) },
        text = {
            Column {
                Text(stringResource(R.string.dialog_delete_body))
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { alsoDeleteCloudFolder = !alsoDeleteCloudFolder },
                ) {
                    Checkbox(
                        checked = alsoDeleteCloudFolder,
                        onCheckedChange = { alsoDeleteCloudFolder = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.dialog_delete_cloud_checkbox),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (alsoDeleteCloudFolder) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.dialog_delete_cloud_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(alsoDeleteCloudFolder) }) {
                Text(stringResource(R.string.button_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
    )
}

/**
 * Wires the optional "also delete the Drive folder" branch to the UI: launches the re-consent
 * screen when silent authorization needs it (mirroring AddEditBackupScreen's Drive consent flow),
 * feeding its result back through [onConsentResult], and shows the progress / failure dialogs.
 */
@Composable
private fun CloudFolderDeleteHandler(
    state: CloudDeleteState,
    onConsentResult: (Intent?) -> Unit,
    onAcknowledgeFailure: () -> Unit,
) {
    val driveConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> onConsentResult(result.data) }
    LaunchedEffect(state) {
        if (state is CloudDeleteState.ConsentRequired) {
            driveConsentLauncher.launch(
                IntentSenderRequest.Builder(state.pendingIntent.intentSender).build(),
            )
        }
    }
    CloudDeleteStatusDialogs(state = state, onAcknowledgeFailure = onAcknowledgeFailure)
}

/**
 * Blocking status dialogs for the "also delete the Drive folder" branch: a progress dialog while the
 * folder is being deleted, and a warning if it couldn't be — acknowledging the warning still deletes
 * the config locally (via [onAcknowledgeFailure]), so the user's delete is always honored.
 */
@Composable
private fun CloudDeleteStatusDialogs(state: CloudDeleteState, onAcknowledgeFailure: () -> Unit) {
    when (state) {
        CloudDeleteState.InProgress -> AlertDialog(
            onDismissRequest = { /* non-cancelable: a delete is in flight */ },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(stringResource(R.string.dialog_delete_in_progress))
                }
            },
            confirmButton = {},
        )
        CloudDeleteState.FolderDeleteFailed -> AlertDialog(
            onDismissRequest = onAcknowledgeFailure,
            title = { Text(stringResource(R.string.dialog_delete_cloud_failed_title)) },
            text = { Text(stringResource(R.string.dialog_delete_cloud_failed_body)) },
            confirmButton = {
                Button(onClick = onAcknowledgeFailure) { Text(stringResource(R.string.button_ok)) }
            },
        )
        CloudDeleteState.Idle, is CloudDeleteState.ConsentRequired -> Unit
    }
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
                PasswordTextField(
                    value = candidate,
                    onValueChange = { candidate = it },
                    label = stringResource(R.string.label_password),
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

@Preview(showBackground = true)
@Composable
private fun BackupDetailPreview() {
    val sample = BackupConfig(
        id = "1",
        displayName = "Documents",
        sourceTreeUri = "content://com.example/tree/docs",
        cloudProvider = "google_drive",
        cloudSubFolderId = "abc",
        cloudSubFolderName = "Documents_a3f9c2",
        cloudAccountIdentifier = "user@gmail.com",
        schedule = BackupSchedule.DAILY,
        changedFilePolicy = ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP,
        encryptionEnabled = true,
        encryptedPasswordBlob = null,
        encryptionSaltBase64 = null,
        retentionPolicy = RetentionPolicy.KeepAll,
        networkPolicy = NetworkPolicy.WIFI_ONLY,
        requiresCharging = false,
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
            isRunning = false,
            continuesAutomatically = true,
            onBackUpNow = {},
            onTogglePause = {},
            onCheckPassword = {},
            onDismissMessage = {},
            onDismissAll = {},
            onMarkRead = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChargingOverrideDialogPreview() {
    FolderVaultTheme {
        ChargingOverrideDialog(onConfirm = {}, onDismiss = {})
    }
}
