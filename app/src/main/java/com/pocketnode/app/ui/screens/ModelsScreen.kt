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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
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
import com.pocketnode.app.data.ModelDownloadSpec
import com.pocketnode.app.data.OPERATOR_SPEC
import com.pocketnode.app.data.StorageUtils
import com.pocketnode.app.data.VerificationStatus
import com.pocketnode.app.data.model.LocalModel
import com.pocketnode.app.data.model.ModelRole
import com.pocketnode.app.data.model.RECOMMENDED_MODELS
import com.pocketnode.app.data.model.RemoteModel
import com.pocketnode.app.inference.InferenceStats
import com.pocketnode.app.licensing.ProGate
import com.pocketnode.app.ui.components.ModelRoleBadge
import com.pocketnode.app.ui.components.VerificationStatusBadge
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel,
    isPro: Boolean,
    onModelSelected: (LocalModel) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToUpgrade: () -> Unit,
    benchmarkMode: Boolean = false,
    lastInferenceStats: InferenceStats? = null
) {
    val context = LocalContext.current
    val models by viewModel.models.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val operatorDownloadState by viewModel.operatorDownloadState.collectAsState()
    val storageStats by viewModel.storageStats.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showUrlDialog by remember { mutableStateOf(false) }
    var downloadUrl by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<LocalModel?>(null) }
    var confirmCleanupFailedPrimaries by remember { mutableStateOf(false) }

    val chatModels = models.filter { it.role != ModelRole.DRAFT.name }
    val draftModels = models.filter { it.role == ModelRole.DRAFT.name }
    val failedPrimaryCount = chatModels.count { it.verificationStatus == VerificationStatus.FAILED }

    LaunchedEffect(Unit) {
        viewModel.importCompletedDownloads(context)
        viewModel.refreshStorageStats()
    }

    LaunchedEffect(importError) {
        importError?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
            viewModel.clearImportError()
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
            viewModel.clearStatusMessage()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.importModel(context, it) } }
    )

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { scaffoldPadding ->
    Column(modifier = Modifier
        .fillMaxSize()
        .padding(scaffoldPadding)
        .background(MaterialTheme.colorScheme.background)) {
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
                    onClick = { viewModel.rescanModels() },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Rescan")
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

        storageStats?.let { stats ->
            StorageHeaderCard(stats, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { viewModel.auditInstalledModels() }) {
                Text("Audit Models")
            }
            if (failedPrimaryCount > 0) {
                OutlinedButton(
                    onClick = { confirmCleanupFailedPrimaries = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clean Failed Primary ($failedPrimaryCount)")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

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
            item {
                OperatorDownloadCard(
                    spec = OPERATOR_SPEC,
                    downloadState = operatorDownloadState,
                    onDownload = { spec -> viewModel.downloadOperatorModel(spec) },
                    onCancel = { viewModel.cancelOperatorDownload() },
                    onRetry = { spec ->
                        viewModel.resetOperatorDownloadState()
                        viewModel.downloadOperatorModel(spec)
                    },
                    onUseExisting = { spec -> viewModel.useExistingOperatorModel(spec) },
                    onReplace = { spec -> viewModel.replaceOperatorModel(spec) },
                    onDismissExists = { viewModel.resetOperatorDownloadState() }
                )
            }

            item { SectionHeader("Recommended", Icons.Default.Recommend) }

            items(RECOMMENDED_MODELS) { remoteModel ->
                val state = downloadStates[remoteModel.name] ?: DownloadState.Idle
                val isBasicModel = remoteModel == RECOMMENDED_MODELS.first()
                val isDraftModel = remoteModel.defaultRole == ModelRole.DRAFT
                val canDownload = isPro || isBasicModel || isDraftModel
                
                RemoteModelCard(
                    model = remoteModel,
                    downloadState = state,
                    isLocked = !canDownload,
                    onDownload = { 
                        if (canDownload) {
                            viewModel.downloadModel(context, remoteModel) 
                        } else {
                            onNavigateToUpgrade()
                        }
                    },
                    onReset = { viewModel.resetDownloadState(remoteModel.name) }
                )
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            if (chatModels.isNotEmpty()) {
                item { SectionHeader("Chat Models", Icons.Default.FolderOpen) }
                items(chatModels) { model ->
                    val tpsLabel = if (benchmarkMode && lastInferenceStats != null)
                        "%.1f TPS".format(lastInferenceStats.tps) else null
                    InstalledModelCard(
                        model = model,
                        onSelect = { onModelSelected(model) },
                        onDelete = { pendingDelete = model },
                        tpsLabel = tpsLabel,
                        onToggleRole = { viewModel.setModelRole(model, ModelRole.DRAFT.name) }
                    )
                }
            }

            if (draftModels.isNotEmpty()) {
                item { Spacer(Modifier.height(8.dp)) }
                item { SectionHeader("Draft Models", Icons.Default.FolderOpen) }
                items(draftModels) { model ->
                    InstalledModelCard(
                        model = model,
                        isDraft = true,
                        onSelect = {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Set as draft model in Settings → Speculative Decoding"
                                )
                            }
                        },
                        onDelete = { pendingDelete = model },
                        onToggleRole = { viewModel.setModelRole(model, ModelRole.MAIN.name) }
                    )
                }
            }

            if (chatModels.isEmpty() && draftModels.isEmpty()) {
                item { EmptyModelsPlaceholder() }
            }
        }
    } // Column
    } // Scaffold

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this model?") },
            text = {
                Text(
                    "This removes the GGUF file from this device. " +
                    "It does not affect any cloud account.\n\n${model.name}.gguf",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteModel(model)
                        pendingDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmCleanupFailedPrimaries) {
        AlertDialog(
            onDismissRequest = { confirmCleanupFailedPrimaries = false },
            title = { Text("Remove failed primary models?") },
            text = {
                Text(
                    "This removes only failed primary GGUF files from Pocket Node's app-managed model directory. " +
                    "Draft models and user-owned Downloads or SAF source files are left untouched."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.cleanupFailedPrimaryModels()
                        confirmCleanupFailedPrimaries = false
                    }
                ) {
                    Text("Clean", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCleanupFailedPrimaries = false }) {
                    Text("Cancel")
                }
            }
        )
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
    isLocked: Boolean = false,
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
                                containerColor = if (isLocked) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                                contentColor = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                if (isLocked) Icons.Default.Lock else Icons.Default.Download, 
                                contentDescription = if (isLocked) "Pro Required" else "Download",
                                modifier = Modifier.size(20.dp)
                            )
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
                    else -> {}
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledModelCard(
    model: LocalModel,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    isDraft: Boolean = false,
    tpsLabel: String? = null,
    onToggleRole: (() -> Unit)? = null
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDraft)
                Color(0xFFFF6D00).copy(alpha = 0.10f)
            else
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)

                // Extract Quantization (e.g. Q4_K_M)
                val quantRegex = Regex("(Q[1-8]_[A-Z0-9_]+)", RegexOption.IGNORE_CASE)
                val quantMatch = model.quantization?.let { Regex("(Q[1-8]_[A-Z0-9_]+)", RegexOption.IGNORE_CASE).find(it) }
                    ?: quantRegex.find(model.path) ?: quantRegex.find(model.name)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val sizeLabel = if (model.sizeBytes > 0) StorageUtils.formatBytes(model.sizeBytes) else null
                    if (sizeLabel != null) {
                        Text(sizeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isDraft) {
                        ModelRoleBadge("DRAFT_MODEL", Color(0xFFB45309))
                    } else {
                        ModelRoleBadge("PRIMARY_MODEL", Color(0xFF1565C0))
                    }
                    if (quantMatch != null) {
                        Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                            Text(quantMatch.value.uppercase(),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 10.sp)
                        }
                    }
                    VerificationStatusBadge(model.verificationStatus)
                    if (tpsLabel != null) {
                        Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text(tpsLabel,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                fontSize = 10.sp)
                        }
                    }
                }
                if (model.verificationStatus == VerificationStatus.FAILED) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Verification failed. Rescan Model Hub, re-import this GGUF, or choose another model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (model.verificationStatus == VerificationStatus.UNKNOWN_HASH) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Hash is not in the trusted registry yet. Rescan after updates or re-import if you expected a verified model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onToggleRole != null) {
                TextButton(onClick = onToggleRole) {
                    Text(
                        if (isDraft) "→ Main" else "→ Draft",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
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

@Composable
fun StorageHeaderCard(
    stats: com.pocketnode.app.data.StorageStats,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${stats.modelCount} model${if (stats.modelCount != 1) "s" else ""} · " +
                    "${StorageUtils.formatBytes(stats.usedBytes)} used · " +
                    "${StorageUtils.formatBytes(stats.freeBytes)} free",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stats.modelsDirPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun OperatorDownloadCard(
    spec: ModelDownloadSpec?,
    downloadState: DownloadState,
    onDownload: (ModelDownloadSpec) -> Unit,
    onCancel: () -> Unit,
    onRetry: (ModelDownloadSpec) -> Unit,
    onUseExisting: (ModelDownloadSpec) -> Unit,
    onReplace: (ModelDownloadSpec) -> Unit,
    onDismissExists: () -> Unit
) {
    var showExistsDialog by remember { mutableStateOf(false) }
    LaunchedEffect(downloadState) {
        showExistsDialog = downloadState is DownloadState.FileExists
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        spec?.displayName ?: "PocketNode Operator",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (spec != null) spec.filename else "Source not configured",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    spec?.sizeBytes?.let { bytes ->
                        Spacer(Modifier.height(4.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                            Text(StorageUtils.formatBytes(bytes),
                                color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    when {
                        spec == null -> Icon(
                            Icons.Default.Lock,
                            contentDescription = "Not configured",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(24.dp)
                        )
                        downloadState is DownloadState.Idle ||
                        downloadState is DownloadState.Cancelled -> IconButton(
                            onClick = { onDownload(spec) },
                            modifier = Modifier.size(40.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Download",
                                modifier = Modifier.size(20.dp))
                        }
                        downloadState is DownloadState.Queued ||
                        downloadState is DownloadState.Downloading ||
                        downloadState is DownloadState.Verifying -> CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        downloadState is DownloadState.Complete -> Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Ready",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(32.dp)
                        )
                        downloadState is DownloadState.Error -> IconButton(
                            onClick = { onRetry(spec) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Error,
                                contentDescription = "Retry",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp))
                        }
                        else -> {}
                    }
                }
            }

            // Status / progress row
            when (val state = downloadState) {
                is DownloadState.Queued -> {
                    Spacer(Modifier.height(8.dp))
                    Text("Starting…", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is DownloadState.Downloading -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.totalBytes > 0)
                                "${StorageUtils.formatBytes(state.bytesDownloaded)} / ${StorageUtils.formatBytes(state.totalBytes)}"
                            else
                                "${StorageUtils.formatBytes(state.bytesDownloaded)} downloaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = onCancel,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text("Cancel", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                is DownloadState.Verifying -> {
                    Spacer(Modifier.height(8.dp))
                    Text("Verifying…", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is DownloadState.Complete -> {
                    Spacer(Modifier.height(4.dp))
                    Text("Ready — model is installed", style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50))
                }
                is DownloadState.Error -> {
                    Spacer(Modifier.height(4.dp))
                    Text(state.msg, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
                is DownloadState.Cancelled -> {
                    Spacer(Modifier.height(4.dp))
                    Text("Download cancelled", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {}
            }
        }
    }

    if (showExistsDialog && spec != null) {
        AlertDialog(
            onDismissRequest = { showExistsDialog = false; onDismissExists() },
            title = { Text("File Already Exists") },
            text = { Text("${spec.filename} is already in your models folder.") },
            confirmButton = {
                TextButton(onClick = { showExistsDialog = false; onReplace(spec) }) {
                    Text("Replace", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExistsDialog = false; onDismissExists() }) {
                        Text("Cancel")
                    }
                    TextButton(onClick = { showExistsDialog = false; onUseExisting(spec) }) {
                        Text("Use Existing")
                    }
                }
            }
        )
    }
}

