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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import ch.abwesend.foldervault.view.viewmodel.SettingsViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onShowOnboarding: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* graceful */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.button_back_cd))
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
    onThemeChange: (AppTheme) -> Unit,
    onErrorReportsChange: (Boolean) -> Unit,
    onShowOnboarding: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

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
            displayName = { context.getString(it.labelResId()) },
            onSelect = onScheduleChange,
        )

        Spacer(modifier = Modifier.height(12.dp))
        EnumDropdown(
            label = stringResource(R.string.label_default_changed_file_policy),
            selected = settings.defaultChangedFilePolicy,
            options = ChangedFilePolicy.entries,
            displayName = { context.getString(it.labelResId()) },
            onSelect = onChangedFilePolicyChange,
        )

        Spacer(modifier = Modifier.height(12.dp))
        EnumDropdown(
            label = stringResource(R.string.label_default_network_policy),
            selected = settings.defaultNetworkPolicy,
            options = NetworkPolicy.entries,
            displayName = { context.getString(it.labelResId()) },
            onSelect = onNetworkPolicyChange,
        )

        SectionDivider()
        SettingsSectionHeader(stringResource(R.string.section_appearance))

        EnumDropdown(
            label = stringResource(R.string.label_theme),
            selected = settings.theme,
            options = AppTheme.entries,
            displayName = { context.getString(it.labelResId()) },
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

@StringRes
private fun BackupSchedule.labelResId(): Int = when (this) {
    BackupSchedule.USE_GLOBAL_DEFAULT -> R.string.schedule_global_default
    BackupSchedule.MANUAL_ONLY -> R.string.schedule_manual_only
    BackupSchedule.DAILY -> R.string.schedule_daily
    BackupSchedule.WEEKLY -> R.string.schedule_weekly
    BackupSchedule.MONTHLY -> R.string.schedule_monthly
}

@StringRes
private fun ChangedFilePolicy.labelResId(): Int = when (this) {
    ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP -> R.string.changed_file_keep_timestamp
    ChangedFilePolicy.OVERWRITE -> R.string.changed_file_overwrite
    ChangedFilePolicy.IGNORE -> R.string.changed_file_skip
}

@StringRes
private fun NetworkPolicy.labelResId(): Int = when (this) {
    NetworkPolicy.WIFI_ONLY -> R.string.network_wifi_only
    NetworkPolicy.ANY -> R.string.network_any
}

@StringRes
private fun AppTheme.labelResId(): Int = when (this) {
    AppTheme.SYSTEM -> R.string.theme_system
    AppTheme.LIGHT -> R.string.theme_light
    AppTheme.DARK -> R.string.theme_dark
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
            onThemeChange = {},
            onErrorReportsChange = {},
            onShowOnboarding = {},
            onRequestNotificationPermission = {},
        )
    }
}
