package ch.abwesend.foldervault.view.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.documentfile.provider.DocumentFile
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreMode
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.components.EnumDropdown
import ch.abwesend.foldervault.view.components.PasswordTextField
import ch.abwesend.foldervault.view.components.UnexpectedErrorDialog
import ch.abwesend.foldervault.view.viewmodel.RestoreState
import ch.abwesend.foldervault.view.viewmodel.RestoreUiState
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

    UnexpectedErrorDialog(error = unexpectedError, onDismiss = viewModel::dismissUnexpectedError)

    val actions = rememberRestoreLaunchActions(
        viewModel = viewModel,
    )

    if (uiState.state is RestoreState.Running) {
        RestoreProgressDialog(mode = uiState.mode, progress = uiState.progress, onCancel = viewModel::cancel)
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
            uiState = uiState,
            onModeChange = viewModel::setMode,
            onPickSource = actions.pickSourceFolder,
            onPickOutput = actions.pickOutputFolder,
            onCollisionPolicyChange = viewModel::setCollisionPolicy,
            onStartRestore = viewModel::startRestore,
            onPickSourceFile = actions.pickSourceFile,
            onSingleFilePasswordChange = viewModel::setSingleFilePassword,
            onDecryptAndSave = actions.decryptAndSave,
            onReset = viewModel::reset,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/** Bundles the four system-picker triggers the restore screen needs. */
private class RestoreLaunchActions(
    val pickSourceFolder: () -> Unit,
    val pickOutputFolder: () -> Unit,
    val pickSourceFile: () -> Unit,
    val decryptAndSave: () -> Unit,
)

/**
 * Creates the SAF launchers for both restore flows and returns their trigger callbacks. Keeping
 * them here keeps [RestoreScreen] short.
 */
@Composable
private fun rememberRestoreLaunchActions(
    viewModel: RestoreViewModel,
): RestoreLaunchActions {
    val context = LocalContext.current

    val sourceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

    val sourceFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // The single file is read once within this session, so the temporary OpenDocument grant is
        // enough — no persistable permission is taken (unlike the folder flow, which re-scans).
        if (uri != null) {
            val name = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
            viewModel.setSourceFile(uri.toString(), name)
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        // The single-file output is a destination *folder*: the app then creates a fresh,
        // non-colliding file inside it, so a restore can never overwrite an existing file. The
        // password is read from the ViewModel state, which survives the activity recreation a
        // configuration change during the picker round-trip causes (composable state would not).
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.startSingleFileRestore(uri.toString())
        }
    }

    return RestoreLaunchActions(
        pickSourceFolder = { sourceLauncher.launch(null) },
        pickOutputFolder = { outputLauncher.launch(null) },
        pickSourceFile = { sourceFileLauncher.launch(arrayOf("*/*")) },
        decryptAndSave = { saveAsLauncher.launch(null) },
    )
}

@Suppress("LongParameterList")
@Composable
private fun RestoreContent(
    uiState: RestoreUiState,
    onModeChange: (RestoreMode) -> Unit,
    onPickSource: () -> Unit,
    onPickOutput: () -> Unit,
    onCollisionPolicyChange: (RestoreCollisionPolicy) -> Unit,
    onStartRestore: (String) -> Unit,
    onPickSourceFile: () -> Unit,
    onSingleFilePasswordChange: (String) -> Unit,
    onDecryptAndSave: () -> Unit,
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
        RestoreModeSelector(mode = uiState.mode, onModeChange = onModeChange)
        HorizontalDivider()

        when (uiState.mode) {
            RestoreMode.WHOLE_FOLDER -> WholeFolderContent(
                uiState = uiState,
                onPickSource = onPickSource,
                onPickOutput = onPickOutput,
                onCollisionPolicyChange = onCollisionPolicyChange,
                onStartRestore = onStartRestore,
                onReset = onReset,
            )
            RestoreMode.SINGLE_FILE -> SingleFileContent(
                uiState = uiState,
                onPickSourceFile = onPickSourceFile,
                onPasswordChange = onSingleFilePasswordChange,
                onDecryptAndSave = onDecryptAndSave,
                onReset = onReset,
            )
        }
    }
}

@Composable
private fun RestoreModeSelector(mode: RestoreMode, onModeChange: (RestoreMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        RestoreMode.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = mode == entry,
                onClick = { onModeChange(entry) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = RestoreMode.entries.size),
            ) {
                Text(stringResource(entry.labelResId))
            }
        }
    }
}

@Suppress("LongParameterList", "MultipleEmitters")
@Composable
private fun WholeFolderContent(
    uiState: RestoreUiState,
    onPickSource: () -> Unit,
    onPickOutput: () -> Unit,
    onCollisionPolicyChange: (RestoreCollisionPolicy) -> Unit,
    onStartRestore: (String) -> Unit,
    onReset: () -> Unit,
) {
    val state = uiState.state
    ExplanationSection(
        titleRes = R.string.restore_explanation_title,
        bodyRes = R.string.restore_explanation_body,
    )

    HorizontalDivider()
    SourceFolderSection(
        state = state,
        cryptFileCount = uiState.cryptFileCount,
        otherFileCount = uiState.otherFileCount,
        onPickSource = onPickSource,
    )

    val showOutputAndRestore = uiState.cryptFileCount > 0 &&
        (
            state == RestoreState.SourceReady ||
                state == RestoreState.ReadyToStart ||
                state is RestoreState.Done
            )

    if (showOutputAndRestore) {
        HorizontalDivider()
        OutputFolderSection(outputUri = uiState.outputUri, onPickOutput = onPickOutput)
    }

    if (showOutputAndRestore && uiState.outputUri != null && state != RestoreState.Scanning) {
        HorizontalDivider()
        PasswordAndStartSection(
            collisionPolicy = uiState.collisionPolicy,
            onCollisionPolicyChange = onCollisionPolicyChange,
            onStartRestore = onStartRestore,
            enabled = state == RestoreState.ReadyToStart || state is RestoreState.Done,
        )
    }

    if (state is RestoreState.Done) {
        HorizontalDivider()
        RestoreResultSection(mode = RestoreMode.WHOLE_FOLDER, result = state.result, onReset = onReset)
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun SingleFileContent(
    uiState: RestoreUiState,
    onPickSourceFile: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onDecryptAndSave: () -> Unit,
    onReset: () -> Unit,
) {
    val state = uiState.state
    ExplanationSection(
        titleRes = R.string.restore_explanation_title,
        bodyRes = R.string.restore_single_explanation_body,
    )

    HorizontalDivider()
    SingleFileSourceSection(fileName = uiState.sourceFileName, onPickSourceFile = onPickSourceFile)

    val sourceReady = uiState.sourceFileUri != null
    if (sourceReady) {
        HorizontalDivider()
        SingleFilePasswordSection(
            password = uiState.singleFilePassword,
            onPasswordChange = onPasswordChange,
            onDecryptAndSave = onDecryptAndSave,
            enabled = state == RestoreState.SourceReady || state is RestoreState.Done,
        )
    }

    if (state is RestoreState.Done) {
        HorizontalDivider()
        RestoreResultSection(mode = RestoreMode.SINGLE_FILE, result = state.result, onReset = onReset)
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun ExplanationSection(@StringRes titleRes: Int, @StringRes bodyRes: Int) {
    Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(bodyRes),
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
private fun SingleFileSourceSection(fileName: String?, onPickSourceFile: () -> Unit) {
    Text(stringResource(R.string.restore_single_step1_header), style = MaterialTheme.typography.labelLarge)
    OutlinedButton(onClick = onPickSourceFile, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.restore_pick_encrypted_file))
    }
    if (fileName != null) {
        Text(
            stringResource(R.string.restore_single_file_selected, fileName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
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
private fun SingleFilePasswordSection(
    password: String,
    onPasswordChange: (String) -> Unit,
    onDecryptAndSave: () -> Unit,
    enabled: Boolean,
) {
    Text(stringResource(R.string.restore_single_step2_header), style = MaterialTheme.typography.labelLarge)

    PasswordTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = stringResource(R.string.label_backup_password),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = onDecryptAndSave,
        enabled = enabled && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.button_decrypt_and_save))
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun RestoreResultSection(mode: RestoreMode, result: RestoreResult, onReset: () -> Unit) {
    Text(stringResource(R.string.restore_result_header), style = MaterialTheme.typography.labelLarge)
    when (result) {
        is RestoreResult.Success -> {
            // The single-file flow handled exactly one file — the counter-based folder message
            // ("Restored 0 encrypted file(s), copied 1 plain file(s).") would read oddly here.
            val msg = if (mode == RestoreMode.SINGLE_FILE) {
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
            stringResource(R.string.restore_failed, stringResource(result.reason.messageResId)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.button_restore_start_over))
    }
}

@Composable
private fun RestoreProgressDialog(mode: RestoreMode, progress: RestoreProgress?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.dialog_restoring_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress == null || progress.total == 0) {
                    // The single-file flow never reports progress, so this branch covers its whole
                    // (potentially long) run — "Verifying password" would be misleading there.
                    val textRes = if (mode == RestoreMode.SINGLE_FILE) {
                        R.string.restore_single_decrypting
                    } else {
                        R.string.restore_verifying_password
                    }
                    Text(
                        stringResource(textRes),
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
            uiState = RestoreUiState(
                state = RestoreState.ReadyToStart,
                cryptFileCount = 42,
                otherFileCount = 3,
                outputUri = "content://com.example/tree/output",
                collisionPolicy = RestoreCollisionPolicy.SKIP,
            ),
            onModeChange = {},
            onPickSource = {},
            onPickOutput = {},
            onCollisionPolicyChange = {},
            onStartRestore = {},
            onPickSourceFile = {},
            onSingleFilePasswordChange = {},
            onDecryptAndSave = {},
            onReset = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RestoreScreenSingleFilePreview() {
    FolderVaultTheme {
        RestoreContent(
            uiState = RestoreUiState(
                mode = RestoreMode.SINGLE_FILE,
                state = RestoreState.SourceReady,
                sourceFileUri = "content://com.example/document/report.pdf.crypt",
                sourceFileName = "report.pdf.crypt",
                suggestedOutputName = "report.pdf",
            ),
            onModeChange = {},
            onPickSource = {},
            onPickOutput = {},
            onCollisionPolicyChange = {},
            onStartRestore = {},
            onPickSourceFile = {},
            onSingleFilePasswordChange = {},
            onDecryptAndSave = {},
            onReset = {},
        )
    }
}
