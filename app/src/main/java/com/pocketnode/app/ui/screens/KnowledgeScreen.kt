package com.pocketnode.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketnode.app.data.model.KnowledgeChunk
import com.pocketnode.app.data.model.KnowledgeSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun KnowledgeScreen(
    vm: KnowledgeViewModel,
    onAttachChunk: (KnowledgeChunk) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToChat: () -> Unit = {}
) {
    val context = LocalContext.current
    val sources by vm.sources.collectAsState()
    val importState by vm.importState
    val searchQuery by vm.searchQuery
    val searchResults by vm.searchResults
    val isSearching by vm.isSearching

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val isBusy = importState is KnowledgeViewModel.ImportState.Loading ||
                 importState is KnowledgeViewModel.ImportState.FolderIndexing

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { vm.importDocument(context, it) } }
    )

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) { }
                vm.importFolder(context, uri)
            }
        }
    )

    var deleteConfirmSource by remember { mutableStateOf<KnowledgeSource?>(null) }

    deleteConfirmSource?.let { source ->
        AlertDialog(
            onDismissRequest = { deleteConfirmSource = null },
            title = { Text("Delete source?") },
            text = { Text("\"${source.displayName}\" and all its chunks will be permanently removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteSource(source.id)
                        deleteConfirmSource = null
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmSource = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
    ) {

        // ── Import status banner ──────────────────────────────────────────────
        AnimatedVisibility(visible = importState !is KnowledgeViewModel.ImportState.Idle) {
            val bannerColor = when (importState) {
                is KnowledgeViewModel.ImportState.Error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(8.dp),
                color = bannerColor
            ) {
                when (val s = importState) {
                    is KnowledgeViewModel.ImportState.Loading -> {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Indexing…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                    }
                    is KnowledgeViewModel.ImportState.FolderIndexing -> {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                val label = if (s.total > 0)
                                    "${s.indexed + 1}/${s.total}: ${s.currentFile.takeLast(36)}"
                                else
                                    s.currentFile
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (s.total > 0) {
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { s.indexed.toFloat() / s.total.toFloat() },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = vm::cancelImport,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Cancel", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    is KnowledgeViewModel.ImportState.Done -> {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "\"${s.title}\" indexed — ${s.chunkCount} chunks",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = vm::dismissImport, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    is KnowledgeViewModel.ImportState.FolderDone -> {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val msg = buildString {
                                if (s.indexed > 0) append("${s.indexed} indexed")
                                if (s.skipped > 0) { if (isNotEmpty()) append("  •  "); append("${s.skipped} unchanged") }
                                if (s.failed > 0) { if (isNotEmpty()) append("  •  "); append("${s.failed} failed") }
                                if (s.totalChunks > 0) { if (isNotEmpty()) append("  —  "); append("${s.totalChunks} chunks") }
                                if (isEmpty()) append("Nothing to index")
                            }
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = vm::dismissImport, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    is KnowledgeViewModel.ImportState.Error -> {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                s.message,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = vm::dismissImport, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    else -> { }
                }
            }
        }

        // ── Search bar ────────────────────────────────────────────────────────
        OutlinedTextField(
            value = searchQuery,
            onValueChange = vm::search,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search chunks…") },
            leadingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Search, contentDescription = null)
                }
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { vm.search("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(12.dp))

        // ── Add buttons ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { fileLauncher.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown", "*/*")) },
                modifier = Modifier.weight(1f),
                enabled = !isBusy
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add File")
            }
            OutlinedButton(
                onClick = { folderLauncher.launch(null) },
                modifier = Modifier.weight(1f),
                enabled = !isBusy
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add Folder")
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Search results or sources list ────────────────────────────────────
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            if (searchQuery.isNotBlank()) {
                if (searchResults.isEmpty() && !isSearching) {
                    item {
                        Text(
                            "No results for \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                } else {
                    item {
                        Text(
                            "${searchResults.size} result${if (searchResults.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(searchResults, key = { it.id }) { chunk ->
                        ChunkResultCard(chunk = chunk, onAttach = {
                            onAttachChunk(chunk)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = "Added to chat context",
                                    actionLabel = "Open Chat",
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    onNavigateToChat()
                                }
                            }
                        })
                    }
                }
            } else {
                if (sources.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "No documents indexed yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Add a .md / .txt file or a folder / vault above.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    item {
                        Text(
                            "INDEXED SOURCES",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(sources, key = { it.id }) { source ->
                        SourceCard(
                            source = source,
                            onReindex = { vm.reindexSource(context, source) },
                            onDelete = { deleteConfirmSource = source },
                            reindexEnabled = !isBusy
                        )
                    }
                }
            }
        }
    } // Column
    } // Scaffold
}

@Composable
private fun SourceCard(
    source: KnowledgeSource,
    onReindex: () -> Unit,
    onDelete: () -> Unit,
    reindexEnabled: Boolean
) {
    val dateStr = remember(source.lastIndexedAt) {
        if (source.lastIndexedAt > 0)
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(Date(source.lastIndexedAt))
        else "—"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type icon
            Icon(
                imageVector = if (source.isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = if (source.isFolder) "Folder" else "File",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    source.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val meta = buildString {
                    if (source.isFolder) append("${source.documentCount} docs  •  ")
                    append("${source.chunkCount} chunks  •  indexed $dateStr")
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Reindex button
            IconButton(
                onClick = onReindex,
                enabled = reindexEnabled,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Reindex",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Delete button
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete source",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ChunkResultCard(chunk: KnowledgeChunk, onAttach: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                chunk.documentTitle,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                chunk.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "~${chunk.tokenEstimate} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onAttach,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Add to Chat", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
