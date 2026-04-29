package com.pocketnode.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketnode.app.data.model.LocalModel
import com.pocketnode.app.data.model.RECOMMENDED_MODELS
import com.pocketnode.app.data.model.RemoteModel
import com.pocketnode.app.licensing.ProGate

@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel,
    isPro: Boolean,
    onModelSelected: (LocalModel) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToUpgrade: () -> Unit
) {
    val context = LocalContext.current
    val models by viewModel.models.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()

    var showUrlDialog by remember { mutableStateOf(false) }
    var downloadUrl by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.importModel(context, it) } }
    )

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Model Hub",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "Select or download a model to start",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Import button — Pro-gated (Lite tier: 1 model only)
                if (isPro || models.isEmpty()) {
                    FloatingActionButton(
                        onClick = { launcher.launch(arrayOf("*/*")) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Import GGUF")
                    }
                } else {
                    // Show locked state — tap to upgrade
                    FloatingActionButton(
                        onClick = onNavigateToUpgrade,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Import GGUF — Pro")
                    }
                }

                FloatingActionButton(
                    onClick = { showUrlDialog = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Download URL")
                }

                FloatingActionButton(
                    onClick = onNavigateToSettings,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }

        if (!isPro && models.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Lite: 1 model included. Unlock Pro for unlimited.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onNavigateToUpgrade) {
                        Text("Upgrade", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { SectionHeader("Recommended", Icons.Default.Recommend) }

            items(RECOMMENDED_MODELS) { remoteModel ->
                val state = downloadStates[remoteModel.name] ?: DownloadState.Idle
                RemoteModelCard(
                    model = remoteModel,
                    downloadState = state,
                    onDownload = { viewModel.downloadModel(context, remoteModel) },
                    onReset = { viewModel.resetDownloadState(remoteModel.name) }
                )
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            if (models.isNotEmpty()) {
                item { SectionHeader("Installed", Icons.Default.FolderOpen) }
                items(models) { model ->
                    InstalledModelCard(
                        model = model,
                        onSelect = { onModelSelected(model) },
                        onDelete = { viewModel.deleteModel(model) }
                    )
                }
            } else {
                item { EmptyModelsPlaceholder() }
            }
        }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Download Model") },
            text = {
                OutlinedTextField(
                    value = downloadUrl,
                    onValueChange = { downloadUrl = it },
                    label = { Text("Hugging Face GGUF URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (downloadUrl.isNotBlank()) {
                            viewModel.downloadModelFromUrl(context, downloadUrl)
                        }
                        showUrlDialog = false
                        downloadUrl = ""
                    }
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun RemoteModelCard(
    model: RemoteModel,
    downloadState: DownloadState,
    onDownload: () -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(model.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text(model.size, color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 10.sp)
                    }
                    if (downloadState is DownloadState.Downloading) {
                        Text("${(downloadState.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                when (downloadState) {
                    is DownloadState.Idle -> {
                        IconButton(
                            onClick = onDownload,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Download",
                                modifier = Modifier.size(20.dp))
                        }
                    }
                    is DownloadState.Downloading -> {
                        CircularProgressIndicator(
                            progress = { downloadState.progress },
                            modifier = Modifier.size(36.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeWidth = 3.dp
                        )
                    }
                    is DownloadState.Importing -> {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp),
                            color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
                    }
                    is DownloadState.Done -> {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded",
                            tint = Color(0xFF4CAF50), modifier = Modifier.size(32.dp))
                    }
                    is DownloadState.Error -> {
                        IconButton(onClick = onReset, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Error, contentDescription = "Error — tap to retry",
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledModelCard(model: LocalModel, onSelect: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                
                // Extract Quantization (e.g. Q4_K_M)
                val quantRegex = Regex("(Q[1-8]_[A-Z0-9_]+)", RegexOption.IGNORE_CASE)
                val quantMatch = quantRegex.find(model.path) ?: quantRegex.find(model.name)
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(model.path.split("/").last(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    if (quantMatch != null) {
                        Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                            Text(quantMatch.value.uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 10.sp)
                        }
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun EmptyModelsPlaceholder() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No models installed yet", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Download a recommendation or import a .gguf file",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}
