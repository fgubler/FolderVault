package ch.abwesend.foldervault.view.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.abwesend.foldervault.R

/**
 * Dialog informing the user about the outcome of exporting today's log file.
 * A dedicated dialog (instead of [UnexpectedErrorDialog]) so a successful export
 * is not presented under an error title.
 */
@Composable
fun LogExportResultDialog(success: Boolean?, onDismiss: () -> Unit) {
    if (success != null) {
        val title = if (success) {
            R.string.dialog_export_log_success_title
        } else {
            R.string.dialog_export_log_failed_title
        }
        val body = if (success) R.string.export_log_success else R.string.export_log_failed

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(body)) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.button_close))
                }
            },
        )
    }
}
