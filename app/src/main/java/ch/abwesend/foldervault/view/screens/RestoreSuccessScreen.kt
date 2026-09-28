package ch.abwesend.foldervault.view.screens

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.restore.RestoreMode
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme

/**
 * Landing screen of a restore that succeeded, shown in place of an inline result on the form.
 *
 * The form's success state used to sit next to its own input controls — for a single file, next to
 * the password field, which regained focus and the keyboard the moment the progress dialog closed.
 * A destination of its own shows only what matters now (what was restored) and the two ways on:
 * another restore, or back to the start of the app. It holds no state; the restore form was reset
 * when it handed over, so [onRestoreAnother] simply returns to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreSuccessScreen(
    mode: RestoreMode,
    result: RestoreResult.Success,
    onRestoreAnother: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.restore_title)) }) },
    ) { innerPadding ->
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
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.restore_success_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = restoreSuccessMessage(mode, result),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.button_done))
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onRestoreAnother, modifier = Modifier.fillMaxWidth()) {
                val textRes = if (mode == RestoreMode.SINGLE_FILE) {
                    R.string.button_restore_another_file
                } else {
                    R.string.button_restore_another_folder
                }
                Text(stringResource(textRes))
            }
        }
    }
}

/**
 * The single-file flow handled exactly one file, so it gets a sentence rather than the folder
 * flow's counters — "Restored 0 encrypted file(s), copied 1 plain file(s)." would read oddly for
 * one plain file saved as a copy.
 */
@Composable
private fun restoreSuccessMessage(mode: RestoreMode, result: RestoreResult.Success): String =
    if (mode == RestoreMode.SINGLE_FILE) {
        if (result.copied > 0) {
            stringResource(R.string.restore_single_success_copied)
        } else {
            stringResource(R.string.restore_single_success_decrypted)
        }
    } else {
        buildString {
            append(stringResource(R.string.restore_success_base, result.decrypted))
            if (result.copied > 0) append(stringResource(R.string.restore_success_and_copied, result.copied))
            if (result.skipped > 0) append(stringResource(R.string.restore_success_and_skipped, result.skipped))
            if (result.failed > 0) append(stringResource(R.string.restore_success_and_failed, result.failed))
            append(".")
        }
    }

@Preview(showBackground = true)
@Composable
private fun RestoreSuccessScreenPreview() {
    FolderVaultTheme {
        RestoreSuccessScreen(
            mode = RestoreMode.WHOLE_FOLDER,
            result = RestoreResult.Success(decrypted = 12, copied = 3, skipped = 0, failed = 0),
            onRestoreAnother = {},
            onFinish = {},
        )
    }
}
