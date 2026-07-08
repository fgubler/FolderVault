package ch.abwesend.foldervault.view.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.components.LogExportResultDialog
import ch.abwesend.foldervault.view.components.UnexpectedErrorDialog
import ch.abwesend.foldervault.view.viewmodel.DatabaseGuardViewModel

/**
 * Full-screen replacement for the app UI when the local database cannot be opened
 * (e.g. a missing or invalid migration). Offers the user a retry, exporting today's
 * log file for a bug report, and — behind a confirmation dialog — a destructive reset
 * of the local database.
 */
@Composable
internal fun DatabaseErrorScreen(
    viewModel: DatabaseGuardViewModel,
    modifier: Modifier = Modifier,
) {
    val userMessage by viewModel.userMessage.collectAsState()
    val exportResult by viewModel.exportResult.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) viewModel.exportTodayLogFile(uri.toString()) }

    userMessage?.let { message ->
        UnexpectedErrorDialog(error = message, onDismiss = viewModel::dismissUserMessage)
    }
    LogExportResultDialog(success = exportResult, onDismiss = viewModel::dismissExportResult)

    DatabaseErrorContent(
        onRetry = viewModel::verifyDatabase,
        onExportTodayLog = {
            exportLauncher.launch("foldervault-log-${System.currentTimeMillis()}.log")
        },
        onResetDatabase = viewModel::resetDatabase,
        modifier = modifier,
    )
}

@Composable
private fun DatabaseErrorContent(
    onRetry: () -> Unit,
    onExportTodayLog: () -> Unit,
    onResetDatabase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }

    if (showResetConfirmation) {
        ResetDatabaseConfirmationDialog(
            onConfirm = {
                showResetConfirmation = false
                onResetDatabase()
            },
            onDismiss = { showResetConfirmation = false },
        )
    }

    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(56.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.database_error_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.database_error_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.button_retry))
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onExportTodayLog, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.button_export_today_log))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { showResetConfirmation = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.button_reset_database))
            }
        }
    }
}

@Composable
private fun ResetDatabaseConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_reset_database_title)) },
        text = { Text(stringResource(R.string.dialog_reset_database_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.button_reset_database_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun DatabaseErrorScreenPreview() {
    FolderVaultTheme {
        DatabaseErrorContent(
            onRetry = {},
            onExportTodayLog = {},
            onResetDatabase = {},
        )
    }
}
