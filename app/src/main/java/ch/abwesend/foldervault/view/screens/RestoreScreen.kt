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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ch.abwesend.foldervault.domain.restore.RestoreCollisionPolicy
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import ch.abwesend.foldervault.domain.restore.RestoreResult
import ch.abwesend.foldervault.ui.theme.FolderVaultTheme
import ch.abwesend.foldervault.view.components.EnumDropdown
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
    val state by viewModel.state.collectAsState()
    val cryptFileCount by viewModel.cryptFileCount.collectAsState()
    val otherFileCount by viewModel.otherFileCount.collectAsState()
    val outputUri by viewModel.outputUri.collectAsState()
    val collisionPolicy by viewModel.collisionPolicy.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val context = LocalContext.current

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

    if (state is RestoreState.Running) {
        RestoreProgressDialog(progress = progress, onCancel = viewModel::cancel)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Restore backup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        RestoreContent(
            state = state,
            cryptFileCount = cryptFileCount,
            otherFileCount = otherFileCount,
            outputUri = outputUri,
            collisionPolicy = collisionPolicy,
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
    Text("Restore an encrypted backup", style = MaterialTheme.typography.titleSmall)
    Text(
        "1. Use the Google Drive app to download your backup folder to local storage.\n" +
            "2. Pick the downloaded folder and an empty output folder here.\n" +
            "3. Enter your backup password — every file will be decrypted locally.",
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
    Text("Step 1: downloaded backup folder", style = MaterialTheme.typography.labelLarge)
    OutlinedButton(onClick = onPickSource, modifier = Modifier.fillMaxWidth()) {
        Text("Pick backup folder")
    }
    when (state) {
        RestoreState.Scanning -> Text(
            "Scanning…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RestoreState.SourceReady, RestoreState.ReadyToStart, is RestoreState.Done -> {
            if (cryptFileCount == 0 && otherFileCount > 0) {
                Text(
                    "No encrypted files found. Your $otherFileCount file(s) are already usable without decryption.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else if (cryptFileCount > 0) {
                Text(
                    "Found $cryptFileCount encrypted file(s)" +
                        "${if (otherFileCount > 0) " and $otherFileCount other file(s)" else ""}.",
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
    Text("Step 2: output folder", style = MaterialTheme.typography.labelLarge)
    OutlinedButton(onClick = onPickOutput, modifier = Modifier.fillMaxWidth()) {
        Text(if (outputUri != null) "Output folder selected ✓" else "Pick output folder")
    }
    if (outputUri == null) {
        Text(
            "Pick an empty folder. Non-empty folders trigger the collision policy below.",
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
    Text("Step 3: password & options", style = MaterialTheme.typography.labelLarge)

    var password by remember { mutableStateOf("") }

    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Backup password") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    EnumDropdown(
        label = "If file already exists",
        selected = collisionPolicy,
        options = RestoreCollisionPolicy.entries,
        displayName = { it.displayName() },
        onSelect = onCollisionPolicyChange,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = { onStartRestore(password) },
        enabled = enabled && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Start restore")
    }
}

@Suppress("MultipleEmitters")
@Composable
private fun RestoreResultSection(result: RestoreResult, onReset: () -> Unit) {
    Text("Result", style = MaterialTheme.typography.labelLarge)
    when (result) {
        is RestoreResult.Success -> {
            val msg = buildString {
                append("Restored ${result.decrypted} encrypted file(s)")
                if (result.copied > 0) append(", copied ${result.copied} plain file(s)")
                if (result.skipped > 0) append(", skipped ${result.skipped}")
                if (result.failed > 0) append(", ${result.failed} failed")
                append(".")
            }
            Text(msg, style = MaterialTheme.typography.bodyMedium)
        }
        RestoreResult.InvalidPassword -> Text(
            "Wrong password. No files were modified.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        RestoreResult.Cancelled -> Text(
            "Restore was cancelled.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is RestoreResult.Failure -> Text(
            "Restore failed: ${result.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
        Text("Restore again")
    }
}

@Composable
private fun RestoreProgressDialog(progress: RestoreProgress?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Restoring…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress == null || progress.total == 0) {
                    Text("Verifying password…", style = MaterialTheme.typography.bodyMedium)
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
                            "Decrypting ${progress.processed} / ${progress.total} files",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (progress.failed > 0) {
                            Text(
                                "${progress.failed} failed",
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
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

private fun RestoreCollisionPolicy.displayName() = when (this) {
    RestoreCollisionPolicy.SKIP -> "Skip existing files"
    RestoreCollisionPolicy.OVERWRITE -> "Overwrite"
    RestoreCollisionPolicy.RENAME_WITH_SUFFIX -> "Rename with _restored suffix"
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
