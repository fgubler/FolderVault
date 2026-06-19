package ch.abwesend.foldervault.view.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.view.viewmodel.UiText
import ch.abwesend.foldervault.view.viewmodel.asString

/**
 * Generic error dialog shown when a ViewModel's `safeLaunch` block surfaces an unexpected failure.
 *
 * Domain-specific errors (form validation, auth-failed states) should continue to use their own
 * inline UI — this is the last-resort catch for failures the screen otherwise wouldn't notice.
 */
@Composable
fun UnexpectedErrorDialog(error: UiText?, onDismiss: () -> Unit) {
    if (error != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.dialog_unexpected_error_title)) },
            text = { Text(error.asString()) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.button_close))
                }
            },
        )
    }
}
