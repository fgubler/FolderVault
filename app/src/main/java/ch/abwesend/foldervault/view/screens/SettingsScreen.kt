package ch.abwesend.foldervault.view.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SettingsSectionHeader("Backup defaults")

        EnumDropdown(
            label = "Default schedule",
            selected = settings.defaultSchedule,
            options = BackupSchedule.entries.filter { it != BackupSchedule.USE_GLOBAL_DEFAULT },
            displayName = { it.displayName() },
            onSelect = onScheduleChange,
        )

        Spacer(modifier = Modifier.height(12.dp))
        EnumDropdown(
            label = "Default changed-file policy",
            selected = settings.defaultChangedFilePolicy,
            options = ChangedFilePolicy.entries,
            displayName = { it.displayName() },
            onSelect = onChangedFilePolicyChange,
        )

        Spacer(modifier = Modifier.height(12.dp))
        EnumDropdown(
            label = "Default network policy",
            selected = settings.defaultNetworkPolicy,
            options = NetworkPolicy.entries,
            displayName = { it.displayName() },
            onSelect = onNetworkPolicyChange,
        )

        SectionDivider()
        SettingsSectionHeader("Appearance")

        EnumDropdown(
            label = "Theme",
            selected = settings.theme,
            options = AppTheme.entries,
            displayName = { it.displayName() },
            onSelect = onThemeChange,
        )

        SectionDivider()
        SettingsSectionHeader("Privacy")

        SwitchRow(
            label = "Anonymous error reports",
            description = "Help improve the app by sending anonymous crash reports",
            checked = settings.anonymousErrorReports,
            onCheckedChange = onErrorReportsChange,
        )

        SectionDivider()
        SettingsSectionHeader("Notifications")

        OutlinedButton(
            onClick = onRequestNotificationPermission,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Re-request notification permission")
        }

        SectionDivider()
        SettingsSectionHeader("Help")

        OutlinedButton(
            onClick = onShowOnboarding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Show onboarding again")
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

private fun BackupSchedule.displayName() = when (this) {
    BackupSchedule.USE_GLOBAL_DEFAULT -> "Global default"
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

private fun AppTheme.displayName() = when (this) {
    AppTheme.SYSTEM -> "System default"
    AppTheme.LIGHT -> "Light"
    AppTheme.DARK -> "Dark"
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
