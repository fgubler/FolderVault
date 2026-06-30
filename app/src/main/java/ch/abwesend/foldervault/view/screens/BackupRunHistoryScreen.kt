package ch.abwesend.foldervault.view.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.backup.BackupRun
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.viewmodel.BackupRunHistoryViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L

private fun formatRunTimestamp(epochMillis: Long, locale: Locale): String {
    val formatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(locale)
    return formatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRunHistoryScreen(
    configId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupRunHistoryViewModel = koinViewModel(parameters = { parametersOf(configId) }),
) {
    val runs by viewModel.runs.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_run_history_title)) },
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
        HistoryContent(runs = runs, modifier = Modifier.padding(innerPadding))
    }
}

@Composable
private fun HistoryContent(runs: List<BackupRun>, modifier: Modifier = Modifier) {
    if (runs.isEmpty()) {
        Text(
            stringResource(R.string.backup_run_history_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(16.dp),
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(runs, key = { it.id }) { run -> RunHistoryItem(run = run) }
        }
    }
}

@Composable
private fun RunHistoryItem(run: BackupRun) {
    val borderColor = when (run.status) {
        BackupRunStatus.FAILED -> MaterialTheme.colorScheme.error
        BackupRunStatus.COMPLETED_WITH_WARNINGS -> MaterialTheme.colorScheme.tertiary
        BackupRunStatus.RUNNING, BackupRunStatus.INITIAL_SYNC_IN_PROGRESS -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val locale = LocalConfiguration.current.locales[0]

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(run.status.labelResId),
                    style = MaterialTheme.typography.labelSmall,
                    color = borderColor,
                )
                Text(
                    text = formatRunTimestamp(run.startedAt, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            val duration = formatDuration(run.startedAt, run.completedAt)
            Text(
                text = stringResource(R.string.backup_run_duration, duration),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.backup_run_files_uploaded, run.filesUploaded),
                style = MaterialTheme.typography.bodySmall,
            )
            if (run.filesSkipped > 0) {
                Text(
                    text = stringResource(R.string.backup_run_files_skipped, run.filesSkipped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (run.filesFailed > 0) {
                Text(
                    text = stringResource(R.string.backup_run_files_failed, run.filesFailed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun formatDuration(startedAt: Long, completedAt: Long?): String {
    if (completedAt == null) {
        return stringResource(R.string.backup_run_duration_in_progress)
    }
    val totalSeconds = (completedAt - startedAt).coerceAtLeast(0) / MS_PER_SECOND
    val hours = totalSeconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR)
    val minutes = (totalSeconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return when {
        hours > 0 -> stringResource(R.string.backup_run_duration_hms, hours, minutes, seconds)
        minutes > 0 -> stringResource(R.string.backup_run_duration_ms, minutes, seconds)
        else -> stringResource(R.string.backup_run_duration_s, seconds)
    }
}

@Preview(showBackground = true)
@Composable
private fun BackupRunHistoryPreview() {
    val now = System.currentTimeMillis()
    val sample = listOf(
        BackupRun(
            id = 1,
            backupConfigId = "c1",
            runId = "r1",
            startedAt = now - 600_000,
            completedAt = now - 540_000,
            status = BackupRunStatus.UP_TO_DATE,
            filesUploaded = 42,
            filesSkipped = 0,
            filesFailed = 0,
            bytesUploaded = 0,
        ),
        BackupRun(
            id = 2,
            backupConfigId = "c1",
            runId = "r2",
            startedAt = now - 1_800_000,
            completedAt = now - 1_795_000,
            status = BackupRunStatus.FAILED,
            filesUploaded = 0,
            filesSkipped = 0,
            filesFailed = 3,
            bytesUploaded = 0,
        ),
    )
    FolderVaultTheme {
        HistoryContent(runs = sample)
    }
}
