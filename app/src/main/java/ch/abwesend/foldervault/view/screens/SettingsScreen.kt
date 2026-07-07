package ch.abwesend.foldervault.view.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.AppTheme
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.system.BackgroundRestrictionStatus
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.components.EnumDropdown
import ch.abwesend.foldervault.view.components.InfoIconButton
import ch.abwesend.foldervault.view.components.UnexpectedErrorDialog
import ch.abwesend.foldervault.view.viewmodel.SettingsViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onShowOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val unexpectedError by viewModel.unexpectedError.collectAsState()
    val exportResult by viewModel.exportResult.collectAsState()
    val backgroundRestrictions by viewModel.backgroundRestrictions.collectAsState()
    val context = LocalContext.current
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }
    val requestNotificationPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) viewModel.exportTodayLogFile(uri) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshBackgroundRestrictions()
        onPauseOrDispose { }
    }

    UnexpectedErrorDialog(error = unexpectedError, onDismiss = viewModel::dismissUnexpectedError)
    exportResult?.let { message ->
        UnexpectedErrorDialog(
            error = message,
            onDismiss = viewModel::dismissExportResult,
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
    ) { innerPadding ->
        SettingsContent(
            settings = settings,
            backgroundRestrictions = backgroundRestrictions,
            modifier = Modifier.padding(innerPadding),
            onScheduleChange = viewModel::setDefaultSchedule,
            onChangedFilePolicyChange = viewModel::setDefaultChangedFilePolicy,
            onNetworkPolicyChange = viewModel::setDefaultNetworkPolicy,
            onFileSizeLimitChange = viewModel::setDefaultFileSizeLimit,
            onThemeChange = viewModel::setTheme,
            onErrorReportsChange = viewModel::setAnonymousErrorReports,
            onNotifyOnBackupCompletionChange = { enabled ->
                viewModel.setNotifyOnBackupCompletion(enabled)
                // Enabling the notification is pointless without the permission — ask right away.
                if (enabled) requestNotificationPermission()
            },
            onShowOnboarding = {
                viewModel.setShowOnboarding(true)
                onShowOnboarding()
            },
            onRequestNotificationPermission = requestNotificationPermission,
            onExportTodayLog = {
                exportLauncher.launch("foldervault-log-${System.currentTimeMillis()}.log")
            },
            onOpenBatterySettings = { context.openBatteryOptimizationSettings() },
            onOpenBackgroundDataSettings = { context.openBackgroundDataSettings() },
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun SettingsContent(
    settings: AppSettings,
    backgroundRestrictions: BackgroundRestrictionStatus,
    onScheduleChange: (BackupSchedule) -> Unit,
    onChangedFilePolicyChange: (ChangedFilePolicy) -> Unit,
    onNetworkPolicyChange: (NetworkPolicy) -> Unit,
    onFileSizeLimitChange: (Int) -> Unit,
    onThemeChange: (AppTheme) -> Unit,
    onErrorReportsChange: (Boolean) -> Unit,
    onNotifyOnBackupCompletionChange: (Boolean) -> Unit,
    onShowOnboarding: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onExportTodayLog: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenBackgroundDataSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SettingsSectionHeader(stringResource(R.string.section_backup_defaults))

        EnumDropdown(
            label = stringResource(R.string.label_default_schedule),
            selected = settings.defaultSchedule,
            options = BackupSchedule.entries.filter { it != BackupSchedule.USE_GLOBAL_DEFAULT },
            displayName = { stringResource(it.labelResId()) },
            onSelect = onScheduleChange,
        )

        Spacer(modifier = Modifier.height(12.dp))
        EnumDropdown(
            label = stringResource(R.string.label_default_changed_file_policy),
            selected = settings.defaultChangedFilePolicy,
            options = ChangedFilePolicy.entries,
            displayName = { stringResource(it.labelResId) },
            onSelect = onChangedFilePolicyChange,
        )

        Spacer(modifier = Modifier.height(12.dp))
        EnumDropdown(
            label = stringResource(R.string.label_default_network_policy),
            selected = settings.defaultNetworkPolicy,
            options = NetworkPolicy.entries,
            displayName = { stringResource(it.labelResId) },
            onSelect = onNetworkPolicyChange,
        )

        Spacer(modifier = Modifier.height(12.dp))
        FileSizeLimitField(
            defaultSizeMb = (settings.defaultFileSizeLimitBytes / (1024 * 1024)).toInt(),
            onFileSizeLimitChange = onFileSizeLimitChange,
        )

        SectionDivider()
        SettingsSectionHeader(stringResource(R.string.section_appearance))

        EnumDropdown(
            label = stringResource(R.string.label_theme),
            selected = settings.theme,
            options = AppTheme.entries,
            displayName = { stringResource(it.labelResId) },
            onSelect = onThemeChange,
        )

        SectionDivider()
        SettingsSectionHeader(stringResource(R.string.section_privacy))

        SwitchRow(
            label = stringResource(R.string.label_anonymous_error_reports),
            description = stringResource(R.string.desc_anonymous_error_reports),
            checked = settings.anonymousErrorReports,
            onCheckedChange = onErrorReportsChange,
        )

        SectionDivider()
        NotificationsSection(
            notifyOnBackupCompletion = settings.notifyOnBackupCompletion,
            onNotifyOnBackupCompletionChange = onNotifyOnBackupCompletionChange,
            onRequestNotificationPermission = onRequestNotificationPermission,
        )

        SectionDivider()
        ReliableBackupsSection(
            backgroundRestrictions = backgroundRestrictions,
            onOpenBatterySettings = onOpenBatterySettings,
            onOpenBackgroundDataSettings = onOpenBackgroundDataSettings,
        )

        SectionDivider()
        HelpSection(
            onShowOnboarding = onShowOnboarding,
            onExportTodayLog = onExportTodayLog,
        )
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun NotificationsSection(
    notifyOnBackupCompletion: Boolean,
    onNotifyOnBackupCompletionChange: (Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    SettingsSectionHeader(stringResource(R.string.section_notifications))

    SwitchRow(
        label = stringResource(R.string.label_notify_on_backup_completion),
        description = stringResource(R.string.desc_notify_on_backup_completion),
        checked = notifyOnBackupCompletion,
        onCheckedChange = onNotifyOnBackupCompletionChange,
    )

    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = onRequestNotificationPermission,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.button_request_notification_permission))
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun HelpSection(
    onShowOnboarding: () -> Unit,
    onExportTodayLog: () -> Unit,
) {
    SettingsSectionHeader(stringResource(R.string.section_help))

    OutlinedButton(
        onClick = onShowOnboarding,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.button_show_onboarding))
    }

    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = onExportTodayLog,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.button_export_today_log))
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Settings section showing the OS-level restrictions that can delay or block background backups,
 * with buttons jumping to the system-settings screens where the user can lift them.
 */
@Suppress("MultipleEmitters")
@Composable
private fun ReliableBackupsSection(
    backgroundRestrictions: BackgroundRestrictionStatus,
    onOpenBatterySettings: () -> Unit,
    onOpenBackgroundDataSettings: () -> Unit,
) {
    SettingsSectionHeader(stringResource(R.string.section_reliable_backups))

    BackgroundRestrictionRow(
        label = stringResource(R.string.label_battery_optimization),
        statusText = stringResource(
            if (backgroundRestrictions.ignoringBatteryOptimizations) {
                R.string.status_battery_optimization_exempt
            } else {
                R.string.status_battery_optimization_active
            },
        ),
        isResolved = backgroundRestrictions.ignoringBatteryOptimizations,
        buttonLabel = stringResource(R.string.button_open_battery_settings),
        infoTitle = stringResource(R.string.info_battery_optimization_title),
        infoBody = stringResource(R.string.info_battery_optimization_body),
        onOpenSettings = onOpenBatterySettings,
    )

    Spacer(modifier = Modifier.height(12.dp))
    BackgroundRestrictionRow(
        label = stringResource(R.string.label_background_data),
        statusText = stringResource(
            if (backgroundRestrictions.backgroundDataRestricted) {
                R.string.status_background_data_restricted
            } else {
                R.string.status_background_data_allowed
            },
        ),
        isResolved = !backgroundRestrictions.backgroundDataRestricted,
        buttonLabel = stringResource(R.string.button_open_data_settings),
        infoTitle = stringResource(R.string.info_background_data_title),
        infoBody = stringResource(R.string.info_background_data_body),
        onOpenSettings = onOpenBackgroundDataSettings,
    )
}

/**
 * One entry of the "reliable background backups" section: a label with the current restriction
 * state, an info popup explaining why resolving it helps, and a button jumping to the system
 * settings screen where the user can resolve it.
 */
@Suppress("LongParameterList")
@Composable
private fun BackgroundRestrictionRow(
    label: String,
    statusText: String,
    isResolved: Boolean,
    buttonLabel: String,
    infoTitle: String,
    infoBody: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isResolved) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            InfoIconButton(title = infoTitle, body = infoBody)
        }
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(buttonLabel)
        }
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun FileSizeLimitField(defaultSizeMb: Int, onFileSizeLimitChange: (Int) -> Unit) {
    var sizeLimitText by rememberSaveable(defaultSizeMb) { mutableStateOf(defaultSizeMb.toString()) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = sizeLimitText,
            onValueChange = { text ->
                sizeLimitText = text
                text.toIntOrNull()?.let { onFileSizeLimitChange(it) }
            },
            label = { Text(stringResource(R.string.label_default_file_size_limit)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        InfoIconButton(
            title = stringResource(R.string.info_file_size_limit_title),
            body = stringResource(R.string.info_file_size_limit_body),
        )
    }
}

/**
 * Opens the system list where the user can exclude FolderVault from battery optimization.
 * The app deliberately does not request the exemption directly (Play Store policy); the user
 * has to grant it in the system settings.
 */
private fun Context.openBatteryOptimizationSettings() {
    startSystemScreenWithFallback(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

/**
 * Opens the system screen for this app's background data usage, where the user can allow
 * unrestricted data so backups over mobile data keep working while Data Saver is on.
 */
private fun Context.openBackgroundDataSettings() {
    startSystemScreenWithFallback(
        Intent(
            Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}

/**
 * Starts the given system-settings intent, falling back to the app-details screen on devices
 * whose OEM skin does not offer the standard screen.
 */
private fun Context.startSystemScreenWithFallback(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }
}

@StringRes
private fun BackupSchedule.labelResId(): Int = when (this) {
    BackupSchedule.USE_GLOBAL_DEFAULT -> R.string.schedule_global_default
    BackupSchedule.MANUAL_ONLY -> R.string.schedule_manual_only
    BackupSchedule.DAILY -> R.string.schedule_daily
    BackupSchedule.WEEKLY -> R.string.schedule_weekly
    BackupSchedule.MONTHLY -> R.string.schedule_monthly
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    FolderVaultTheme {
        SettingsContent(
            settings = AppSettings(),
            backgroundRestrictions = BackgroundRestrictionStatus(),
            onScheduleChange = {},
            onChangedFilePolicyChange = {},
            onNetworkPolicyChange = {},
            onFileSizeLimitChange = {},
            onThemeChange = {},
            onErrorReportsChange = {},
            onNotifyOnBackupCompletionChange = {},
            onShowOnboarding = {},
            onRequestNotificationPermission = {},
            onExportTodayLog = {},
            onOpenBatterySettings = {},
            onOpenBackgroundDataSettings = {},
        )
    }
}
