package com.pocketnode.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketnode.app.data.ModelDownloadSpec
import com.pocketnode.app.data.StorageUtils

@Composable
fun SetupRequiredScreen(
    onNavigateToModels: () -> Unit,
    onImportModel: (Uri) -> Unit,
    operatorSpec: ModelDownloadSpec? = null,
    operatorDownloadState: DownloadState = DownloadState.Idle,
    onDownloadOperator: () -> Unit = {},
    onCancelOperatorDownload: () -> Unit = {},
    onUseExistingOperator: () -> Unit = {},
    onReplaceOperator: () -> Unit = {}
) {
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onImportModel(it) } }

    var showExistsDialog by remember { mutableStateOf(false) }
    LaunchedEffect(operatorDownloadState) {
        showExistsDialog = operatorDownloadState is DownloadState.FileExists
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Model Setup Required",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Pocket Node runs fully on-device. To start, add a local GGUF model.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        // Operator download section — shown when spec is available and not actively downloading
        if (operatorSpec != null) {
            when (val state = operatorDownloadState) {
                is DownloadState.Idle, is DownloadState.Cancelled -> {
                    Button(
                        onClick = onDownloadOperator,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download Pocket Node Operator")
                    }
                    Spacer(Modifier.height(12.dp))
                }
                is DownloadState.Queued -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Starting download…", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                is DownloadState.Downloading -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Downloading Pocket Node Operator",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (state.totalBytes > 0)
                                    "${StorageUtils.formatBytes(state.bytesDownloaded)} / ${StorageUtils.formatBytes(state.totalBytes)}"
                                else
                                    "${StorageUtils.formatBytes(state.bytesDownloaded)} downloaded",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = onCancelOperatorDownload,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Cancel", color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                is DownloadState.Verifying -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Verifying model…", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                is DownloadState.Error -> {
                    Text(
                        state.msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onDownloadOperator, modifier = Modifier.fillMaxWidth()) {
                        Text("Retry")
                    }
                    Spacer(Modifier.height(12.dp))
                }
                else -> {} // Complete/FileExists handled by dialog or ViewModel state transition
            }
        }

        Button(
            onClick = onNavigateToModels,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Download / Browse Models")
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { fileLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Import GGUF from Device")
        }
    }

    // Duplicate file dialog
    if (showExistsDialog && operatorSpec != null) {
        AlertDialog(
            onDismissRequest = { showExistsDialog = false },
            title = { Text("File Already Exists") },
            text = { Text("${operatorSpec.filename} is already in your models folder.") },
            confirmButton = {
                TextButton(onClick = { showExistsDialog = false; onReplaceOperator() }) {
                    Text("Replace", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExistsDialog = false }) {
                        Text("Cancel")
                    }
                    TextButton(onClick = { showExistsDialog = false; onUseExistingOperator() }) {
                        Text("Use Existing")
                    }
                }
            }
        )
    }
}
