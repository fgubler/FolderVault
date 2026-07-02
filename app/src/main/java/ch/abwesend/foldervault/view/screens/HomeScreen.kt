package ch.abwesend.foldervault.view.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.backup.BackupConfig
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.util.formatRelativeAgo
import ch.abwesend.foldervault.view.viewmodel.HomeViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onAddBackup: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenRestore: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val configs by viewModel.configs.collectAsState()
    val errorBadgeCounts by viewModel.errorBadgeCounts.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenRestore) {
                        Icon(Icons.Default.Restore, contentDescription = stringResource(R.string.home_cd_restore))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.home_cd_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddBackup) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.home_cd_add))
            }
        },
    ) { innerPadding ->
        if (configs.isEmpty()) {
            HomeEmptyState(modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                contentPadding = innerPadding,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(configs, key = { it.id }) { config ->
                    BackupConfigCard(
                        config = config,
                        errorCount = errorBadgeCounts[config.id] ?: 0,
                        onClick = { onOpenDetail(config.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.home_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.home_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun BackupConfigCard(
    config: BackupConfig,
    errorCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheduleName = stringResource(config.schedule.labelResId())
    val networkName = stringResource(config.networkPolicy.labelResId)

    Card(
        modifier = modifier.clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BadgedBox(
                    badge = {
                        if (errorCount > 0) {
                            Badge { Text(errorCount.toString()) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = config.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (config.encryptionEnabled) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = stringResource(R.string.home_cd_encrypted),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (config.isPaused) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = stringResource(R.string.home_cd_paused),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = config.cloudSubFolderName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.home_card_schedule_network, scheduleName, networkName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))
            BackupStatusLine(config = config)
        }
    }
}

@Composable
private fun BackupStatusLine(config: BackupConfig) {
    val color = when (config.lastRunStatus) {
        BackupRunStatus.FAILED -> MaterialTheme.colorScheme.error
        BackupRunStatus.COMPLETED_WITH_WARNINGS -> MaterialTheme.colorScheme.tertiary
        BackupRunStatus.RUNNING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusText = when {
        config.isPaused -> stringResource(R.string.home_status_paused)
        config.lastRunStatus == BackupRunStatus.INITIAL_SYNC_IN_PROGRESS ->
            stringResource(R.string.home_status_initial_sync, config.filesUploadedTotal, config.totalFilesDiscovered)
        config.lastRunStatus == BackupRunStatus.RUNNING -> stringResource(R.string.home_status_running)
        config.lastRunAt != null -> buildLastRunText(config)
        else -> stringResource(R.string.home_status_never_run)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (config.lastRunStatus == BackupRunStatus.FAILED) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = color,
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun buildLastRunText(config: BackupConfig): String {
    val lastRun = config.lastRunAt ?: return stringResource(R.string.home_status_never_run)
    val agoText = formatRelativeAgo(lastRun)
    val parts = buildList {
        add(stringResource(R.string.home_status_uploaded, config.filesUploaded))
        if (config.filesFailed > 0) add(stringResource(R.string.home_status_failed, config.filesFailed))
    }
    return stringResource(R.string.home_status_last_run, agoText, parts.joinToString(" • "))
}

@StringRes
private fun BackupSchedule.labelResId(): Int = when (this) {
    BackupSchedule.USE_GLOBAL_DEFAULT -> R.string.schedule_default
    BackupSchedule.MANUAL_ONLY -> R.string.schedule_manual
    BackupSchedule.DAILY -> R.string.schedule_daily
    BackupSchedule.WEEKLY -> R.string.schedule_weekly
    BackupSchedule.MONTHLY -> R.string.schedule_monthly
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    FolderVaultTheme {
        HomeEmptyState()
    }
}

@Preview(showBackground = true)
@Composable
private fun BackupCardPreview() {
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
    FolderVaultTheme {
        BackupConfigCard(
            config = sample,
            errorCount = 2,
            onClick = {},
        )
    }
}
