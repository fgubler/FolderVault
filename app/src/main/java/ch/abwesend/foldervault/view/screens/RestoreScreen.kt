package ch.abwesend.foldervault.view.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.components.EnumDropdown
import ch.abwesend.foldervault.view.components.PasswordTextField
import ch.abwesend.foldervault.view.components.UnexpectedErrorDialog
import ch.abwesend.foldervault.view.viewmodel.RestoreState
import ch.abwesend.foldervault.view.viewmodel.RestoreViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RestoreViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val unexpectedError by viewModel.unexpectedError.collectAsState()
    val context = LocalContext.current

    UnexpectedErrorDialog(error = unexpectedError, onDismiss = viewModel::dismissUnexpectedError)

    val sourceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            viewModel.setSourceFolder(uri.toString())
        }
    }

    val outputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.setOutputFolder(uri.toString())
        }
    }

    if (uiState.state is RestoreState.Running) {
        RestoreProgressDialog(progress = uiState.progress, onCancel = viewModel::cancel)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.restore_title)) },
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
        RestoreContent(
            state = uiState.state,
            cryptFileCount = uiState.cryptFileCount,
            otherFileCount = uiState.otherFileCount,
            outputUri = uiState.outputUri,
            collisionPolicy = uiState.collisionPolicy,
            onPickSource = { sourceLauncher.launch(null) },
            onPickOutput = { outputLauncher.launch(null) },
            onCollisionPolicyChange = viewModel::setCollisionPolicy,
            onStartRestore = viewModel::startRestore,
            onReset = viewModel::reset,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun RestoreContent(
    state: RestoreState,
    cryptFileCount: Int,
    otherFileCount: Int,
    outputUri: String?,
    collisionPolicy: RestoreCollisionPolicy,
    onPickSource: () -> Unit,
    onPickOutput: () -> Unit,
    onCollisionPolicyChange: (RestoreCollisionPolicy) -> Unit,
    onStartRestore: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ExplanationSection()

        HorizontalDivider()
        SourceFolderSection(
            state = state,
            cryptFileCount = cryptFileCount,
            otherFileCount = otherFileCount,
            onPickSource = onPickSource,
        )

        val showOutputAndRestore = cryptFileCount > 0 &&
            (
                state == RestoreState.SourceReady ||
                    state == RestoreState.ReadyToStart ||
                    state is RestoreState.Done
                )

        if (showOutputAndRestore) {
            HorizontalDivider()
            OutputFolderSection(outputUri = outputUri, onPickOutput = onPickOutput)
        }

        if (showOutputAndRestore && outputUri != null && state != RestoreState.Scanning) {
            HorizontalDivider()
            PasswordAndStartSection(
                collisionPolicy = collisionPolicy,
                onCollisionPolicyChange = onCollisionPolicyChange,
                onStartRestore = onStartRestore,
                enabled = state == RestoreState.ReadyToStart,
            )
        }

        if (state is RestoreState.Done) {
            HorizontalDivider()
            RestoreResultSection(result = state.result, onReset = onReset)
        }
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun ExplanationSection() {
    Text(stringResource(R.string.restore_explanation_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.restore_explanation_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Suppress("MultipleEmitters")
@Composable
private fun SourceFolderSection(
    state: RestoreState,
    cryptFileCount: Int,
    otherFileCount: Int,
    onPickSource: () -> Unit,
) {
    Text(stringResource(R.string.restore_step1_header), style = MaterialTheme.typography.labelLarge)
    OutlinedButton(onClick = onPickSource, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.restore_pick_backup_folder))
    }
    when (state) {
        RestoreState.Scanning -> Text(
            stringResource(R.string.restore_scanning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RestoreState.SourceReady, RestoreState.ReadyToStart, is RestoreState.Done -> {
            if (cryptFileCount == 0 && otherFileCount > 0) {
                Text(
                    stringResource(R.string.restore_no_encrypted_files, otherFileCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else if (cryptFileCount > 0) {
                val msg = if (otherFileCount > 0) {
                    stringResource(R.string.restore_found_encrypted_and_other, cryptFileCount, otherFileCount)
                } else {
                    stringResource(R.string.restore_found_encrypted, cryptFileCount)
                }
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> Unit
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun OutputFolderSection(outputUri: String?, onPickOutput: () -> Unit) {
    Text(stringResource(R.string.restore_step2_header), style = MaterialTheme.typography.labelLarge)
    val outputButtonRes = if (outputUri != null) {
        R.string.restore_output_selected
    } else {
        R.string.restore_pick_output_folder
    }
    OutlinedButton(onClick = onPickOutput, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(outputButtonRes))
    }
    if (outputUri == null) {
        Text(
            stringResource(R.string.restore_output_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun PasswordAndStartSection(
    collisionPolicy: RestoreCollisionPolicy,
    onCollisionPolicyChange: (RestoreCollisionPolicy) -> Unit,
    onStartRestore: (String) -> Unit,
    enabled: Boolean,
) {
    Text(stringResource(R.string.restore_step3_header), style = MaterialTheme.typography.labelLarge)

    var password by remember { mutableStateOf("") }

    PasswordTextField(
        value = password,
        onValueChange = { password = it },
        label = stringResource(R.string.label_backup_password),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    EnumDropdown(
        label = stringResource(R.string.label_if_file_exists),
        selected = collisionPolicy,
        options = RestoreCollisionPolicy.entries,
        displayName = { stringResource(it.labelResId) },
        onSelect = onCollisionPolicyChange,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = { onStartRestore(password) },
        enabled = enabled && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.button_start_restore))
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun RestoreResultSection(result: RestoreResult, onReset: () -> Unit) {
    Text(stringResource(R.string.restore_result_header), style = MaterialTheme.typography.labelLarge)
    when (result) {
        is RestoreResult.Success -> {
            val msg = buildString {
                append(stringResource(R.string.restore_success_base, result.decrypted))
                if (result.copied > 0) append(stringResource(R.string.restore_success_and_copied, result.copied))
                if (result.skipped > 0) append(stringResource(R.string.restore_success_and_skipped, result.skipped))
                if (result.failed > 0) append(stringResource(R.string.restore_success_and_failed, result.failed))
                append(".")
            }
            Text(msg, style = MaterialTheme.typography.bodyMedium)
        }
        RestoreResult.InvalidPassword -> Text(
            stringResource(R.string.restore_wrong_password),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        RestoreResult.Cancelled -> Text(
            stringResource(R.string.restore_cancelled),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is RestoreResult.Failure -> Text(
            stringResource(R.string.restore_failed, result.message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.button_restore_again))
    }
}

@Composable
private fun RestoreProgressDialog(progress: RestoreProgress?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.dialog_restoring_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress == null || progress.total == 0) {
                    Text(
                        stringResource(R.string.restore_verifying_password),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    val fraction = progress.processed.toFloat() / progress.total.toFloat()
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.restore_decrypting_progress, progress.processed, progress.total),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (progress.failed > 0) {
                            Text(
                                stringResource(R.string.restore_failed_count, progress.failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (progress.currentFileName.isNotEmpty()) {
                        Text(
                            progress.currentFileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.button_cancel)) } },
    )
}

@Preview(showBackground = true)
@Composable
private fun RestoreScreenPreview() {
    FolderVaultTheme {
        RestoreContent(
            state = RestoreState.ReadyToStart,
            cryptFileCount = 42,
            otherFileCount = 3,
            outputUri = "content://com.example/tree/output",
            collisionPolicy = RestoreCollisionPolicy.SKIP,
            onPickSource = {},
            onPickOutput = {},
            onCollisionPolicyChange = {},
            onStartRestore = {},
            onReset = {},
        )
    }
}
