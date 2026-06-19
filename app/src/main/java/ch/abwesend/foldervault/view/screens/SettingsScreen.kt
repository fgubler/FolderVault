package ch.abwesend.foldervault.view.screens

import android.Manifest
import android.os.Build
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.model.AppSettings
import ch.abwesend.foldervault.domain.model.AppTheme
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
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
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) viewModel.exportTodayLogFile(uri) }

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
            modifier = Modifier.padding(innerPadding),
            onScheduleChange = viewModel::setDefaultSchedule,
            onChangedFilePolicyChange = viewModel::setDefaultChangedFilePolicy,
            onNetworkPolicyChange = viewModel::setDefaultNetworkPolicy,
            onFileSizeLimitChange = viewModel::setDefaultFileSizeLimit,
            onThemeChange = viewModel::setTheme,
            onErrorReportsChange = viewModel::setAnonymousErrorReports,
            onShowOnboarding = {
                viewModel.setShowOnboarding(true)
                onShowOnboarding()
            },
            onRequestNotificationPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onExportTodayLog = {
                exportLauncher.launch("foldervault-log-${System.currentTimeMillis()}.log")
            },
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun SettingsContent(
    settings: AppSettings,
    onScheduleChange: (BackupSchedule) -> Unit,
    onChangedFilePolicyChange: (ChangedFilePolicy) -> Unit,
    onNetworkPolicyChange: (NetworkPolicy) -> Unit,
    onFileSizeLimitChange: (Int) -> Unit,
    onThemeChange: (AppTheme) -> Unit,
    onErrorReportsChange: (Boolean) -> Unit,
    onShowOnboarding: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onExportTodayLog: () -> Unit,
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
        SettingsSectionHeader(stringResource(R.string.section_notifications))

        OutlinedButton(
            onClick = onRequestNotificationPermission,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.button_request_notification_permission))
        }

        SectionDivider()
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
            onScheduleChange = {},
            onChangedFilePolicyChange = {},
            onNetworkPolicyChange = {},
            onFileSizeLimitChange = {},
            onThemeChange = {},
            onErrorReportsChange = {},
            onShowOnboarding = {},
            onRequestNotificationPermission = {},
            onExportTodayLog = {},
        )
    }
}
