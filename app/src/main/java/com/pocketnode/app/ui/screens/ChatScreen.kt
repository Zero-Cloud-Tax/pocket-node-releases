package com.pocketnode.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketnode.app.data.model.ChatMessage
import com.pocketnode.app.data.model.KnowledgeChunk
import com.pocketnode.app.inference.BenchmarkState
import com.pocketnode.app.inference.BackendInfo
import com.pocketnode.app.inference.CompatibilityStatus
import com.pocketnode.app.inference.DocumentReader
import com.pocketnode.app.inference.InferenceStats
import com.pocketnode.app.ui.components.ChatBubble
import com.pocketnode.app.ui.components.BackendStatusChip
import com.pocketnode.app.ui.components.InferenceStatusCard
import com.pocketnode.app.ui.components.InferenceStatusCardState
import com.pocketnode.app.ui.components.MarkdownText
import com.pocketnode.app.ui.components.TypingIndicator
import com.pocketnode.app.session.SessionSnapshot
import com.pocketnode.app.session.SessionState
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    currentAssistantMessage: String,
    isGenerating: Boolean,
    isStopping: Boolean,
    isLoadingModel: Boolean,
    isModelReady: Boolean,
    modelName: String?,
    modelError: String?,
    backendName: String,
    selectedModelPath: String?,
    verificationStatus: String?,
    isDraftModel: Boolean,
    isPrimaryModel: Boolean,
    lastInferenceAtMillis: Long?,
    onSendMessage: (String, ByteArray?, Float, Float, Int) -> Unit,
    onClearChat: () -> Unit,
    onStopGeneration: () -> Unit,
    onDismissError: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToModels: () -> Unit = {},
    onOpenConversations: () -> Unit,
    benchmarkMode: Boolean = false,
    lastInferenceStats: InferenceStats? = null,
    compatibilityWarning: String? = null,
    compatibilityStatus: CompatibilityStatus = CompatibilityStatus.UNKNOWN,
    speculativeEnabled: Boolean = false,
    draftCount: Int = 0,
    benchmarkState: BenchmarkState = BenchmarkState.Idle,
    onTune: (() -> Unit)? = null,
    onDismissBenchmark: (() -> Unit)? = null,
    attachedChunks: List<KnowledgeChunk> = emptyList(),
    onRemoveChunk: (Long) -> Unit = {},
    onClearChunks: () -> Unit = {},
    onNavigateToKnowledge: (() -> Unit)? = null,
    sessionSnapshot: SessionSnapshot? = null,
    onResetSession: (() -> Unit)? = null
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    var attachedFileName by remember { mutableStateOf<String?>(null) }
    var attachedDocumentText by remember { mutableStateOf<String?>(null) }
    var attachedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isReadingDocument by remember { mutableStateOf(false) }
    var attachmentWarning by remember { mutableStateOf<String?>(null) }
    var statusExpanded by rememberSaveable { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                isReadingDocument = true
                coroutineScope.launch {
                    val mimeType = context.contentResolver.getType(it)
                    var name = "File"
                    context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst()) {
                            name = cursor.getString(nameIndex)
                        }
                    }
                    if (mimeType?.startsWith("image/") == true) {
                        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val inputStream = context.contentResolver.openInputStream(it)
                            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                            inputStream?.close()
                            if (bitmap != null) {
                                val maxDim = 512f
                                val scale = Math.min(maxDim / bitmap.width.toFloat(), maxDim / bitmap.height.toFloat())
                                val finalBitmap = if (scale < 1f) {
                                    android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                                } else bitmap
                                val outStream = java.io.ByteArrayOutputStream()
                                finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, outStream)
                                outStream.toByteArray()
                            } else null
                        }
                        attachedFileName = name
                        attachedImageBytes = bytes
                    } else {
                        val result = DocumentReader.extractText(context, it)
                        attachedFileName = name
                        attachedDocumentText = result.text
                        attachmentWarning = result.warning
                    }
                    isReadingDocument = false
                }
            }
        }
    )


    val listState = rememberLazyListState()
    val totalItems = messages.size + (if (isGenerating) 1 else 0)

    // Only auto-scroll when the user is already near the bottom; never yank them back up.
    val shouldAutoScroll by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(totalItems) {
        if (totalItems > 0) listState.animateScrollToItem(totalItems - 1)
    }
    LaunchedEffect(currentAssistantMessage) {
        if (shouldAutoScroll && totalItems > 0) listState.scrollToItem(totalItems - 1)
    }

    // Inset ownership for this screen:
    //  - status bar / top app bar / navigation bar: consumed once by the outer
    //    MainActivity Scaffold (MainActivity.kt) via its innerPadding, applied to the
    //    Surface that hosts the NavHost. This screen's own Scaffold zeroes out
    //    contentWindowInsets so those bars are never accounted for a second time.
    //  - IME: owned exclusively here, by Modifier.imePadding() on this Scaffold's root
    //    modifier, which shifts the whole Scaffold (bottomBar + content) above the
    //    keyboard together. ChatInputBar itself no longer applies imePadding().
    // Layout invariant: ChatInputBar (plus the knowledge bar) lives in `bottomBar`, so
    // Scaffold reserves its height before the content slot is measured — no sibling
    // (status card, banners, loading indicator) can push or clip the composer, in
    // portrait or landscape, collapsed or expanded.
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ── Knowledge attachment bar ──
                AnimatedVisibility(visible = attachedChunks.isNotEmpty()) {
                    KnowledgeBar(
                        chunks = attachedChunks,
                        onRemove = onRemoveChunk,
                        onClearAll = onClearChunks
                    )
                }

                // ── Input bar ──
                ChatInputBar(
                    text = inputText,
                    onTextChange = { inputText = it },
                    onSend = {
                        var finalPrompt = inputText
                        if (attachedDocumentText != null) {
                            finalPrompt = "Context Document ($attachedFileName):\n```\n$attachedDocumentText\n```\n\nUser Query: $inputText"
                        }
                        onSendMessage(finalPrompt, attachedImageBytes, 0.7f, 0.9f, 40)
                        inputText = ""
                        attachedFileName = null
                        attachedDocumentText = null
                        attachedImageBytes = null
                        attachmentWarning = null
                    },
                    isGenerating = isGenerating,
                    isStopping = isStopping,
                    onStop = onStopGeneration,
                    enabled = isModelReady && !isLoadingModel && !isStopping,
                    onAttach = { documentLauncher.launch(arrayOf("application/pdf", "text/plain", "image/*")) },
                    attachedFileName = attachedFileName,
                    onRemoveAttachment = {
                        attachedFileName = null
                        attachedDocumentText = null
                        attachedImageBytes = null
                        attachmentWarning = null
                    },
                    isReadingDocument = isReadingDocument
                )
            }
        }
    ) { contentPadding ->
        // Bounded content region: only the message LazyColumn is flexible/scrollable.
        // If non-scrollable siblings (header, expanded status card, banners) ever
        // exceed the remaining height, only this region is affected — the composer,
        // reserved above in bottomBar, is never displaced.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {

            // ── Header row ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClearChat) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Chat",
                        tint = MaterialTheme.colorScheme.error)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        modelName ?: "Pocket Node",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    // GPU/CPU indicator chip
                    if (isModelReady) {
                        Box(modifier = Modifier.padding(top = 2.dp)) {
                            BackendStatusChip(backendName)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // ── Session status chip ──
                    // Diagnostic only, no prompt/response content. Placed in the
                    // header row (never in bottomBar) so it can never displace or
                    // cover ChatInputBar — see the layout invariant documented above.
                    if (sessionSnapshot != null && onResetSession != null) {
                        SessionStatusChip(
                            snapshot = sessionSnapshot,
                            onResetSession = onResetSession,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    if (onNavigateToKnowledge != null) {
                        IconButton(onClick = onNavigateToKnowledge) {
                            Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = "Knowledge",
                                tint = if (attachedChunks.isNotEmpty())
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onOpenConversations) {
                        Icon(Icons.Default.Forum, contentDescription = "Conversations",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            AnimatedVisibility(visible = isModelReady) {
                InferenceStatusCard(
                    state = InferenceStatusCardState(
                        selectedModelName = modelName,
                        resolvedModelPath = selectedModelPath,
                        verificationStatus = verificationStatus,
                        isDraftModel = isDraftModel,
                        isPrimaryModel = isPrimaryModel,
                        backendName = backendName,
                        lastInferenceAtMillis = lastInferenceAtMillis,
                        modelLoaded = isModelReady
                    ),
                    expanded = statusExpanded,
                    onToggleExpanded = { statusExpanded = !statusExpanded },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // ── Error banner ──
            AnimatedVisibility(visible = modelError != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                modelError ?: "",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            IconButton(onClick = onDismissError, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp))
                            }
                        }
                        val shouldShowModelHubAction =
                            modelError?.contains("not found", ignoreCase = true) == true ||
                                modelError?.contains("verification", ignoreCase = true) == true ||
                                modelError?.contains("re-import", ignoreCase = true) == true ||
                                modelError?.contains("rescan", ignoreCase = true) == true
                        if (shouldShowModelHubAction) {
                            TextButton(
                                onClick = onNavigateToModels,
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "Return to Models",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = attachmentWarning != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = attachmentWarning.orEmpty(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            // ── Model loading indicator ──
            if (isLoadingModel) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // ── Messages ──
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty() && !isGenerating) {
                    item(key = "empty_state") {
                        EmptyChatState(
                            onSuggestionClick = { inputText = it },
                            modifier = Modifier.fillParentMaxSize()
                        )
                    }
                }

                val displayMessages = if (isGenerating && messages.isNotEmpty() && messages.last().role == "assistant") {
                    messages.dropLast(1)
                } else {
                    messages
                }

                items(displayMessages, key = { it.id }) { message ->
                    ChatBubble(message)
                }
                if (isGenerating && currentAssistantMessage.isNotEmpty()) {
                    item {
                        ChatBubble(
                            message = ChatMessage(
                                conversationId = 0,
                                role = "assistant",
                                content = currentAssistantMessage,
                                timestamp = System.currentTimeMillis()
                            ),
                            renderMarkdown = true
                        )
                    }
                }
                if (isGenerating && currentAssistantMessage.isEmpty()) {
                    item { TypingIndicator() }
                }
                // Benchmark stats row – shown below last AI response when not generating
                if (!isGenerating && benchmarkMode && lastInferenceStats != null) {
                    item {
                        StatsRow(
                            stats = lastInferenceStats,
                            compatibilityWarning = compatibilityWarning,
                            compatibilityStatus = compatibilityStatus,
                            speculativeEnabled = speculativeEnabled,
                            draftCount = draftCount,
                            onTune = onTune
                        )
                    }
                }
            }

            // ── Benchmark dialog ──
            if (benchmarkState is BenchmarkState.Done) {
                BenchmarkDialog(
                    state = benchmarkState,
                    onDismiss = { onDismissBenchmark?.invoke() }
                )
            }
        }
    }
}


@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    isStopping: Boolean,
    onStop: () -> Unit,
    enabled: Boolean,
    onAttach: () -> Unit,
    attachedFileName: String?,
    onRemoveAttachment: () -> Unit,
    isReadingDocument: Boolean
) {
    // IME padding is owned by the chat-level Scaffold in ChatScreen() (single owner —
    // not re-applied here to avoid double IME inset).
    Column(modifier = Modifier.fillMaxWidth()) {
        AnimatedVisibility(visible = isStopping) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        "Stopping...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        if (attachedFileName != null || isReadingDocument) {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Description, contentDescription = "Document",
                        modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isReadingDocument) {
                        Text("Reading document...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    } else {
                        Text(attachedFileName ?: "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = onRemoveAttachment, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onAttach, enabled = enabled && !isGenerating && !isReadingDocument) {
                    Icon(Icons.Default.Add, contentDescription = "Attach Document", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextField(
                    value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f).clip(CircleShape),
                placeholder = { Text("Message Pocket Node...") },
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                enabled = enabled && !isGenerating && !isStopping,
                maxLines = 4
            )

            Spacer(modifier = Modifier.width(12.dp))

            FloatingActionButton(
                onClick = if (isGenerating) onStop else onSend,
                modifier = Modifier.size(48.dp),
                containerColor = when {
                    isStopping -> MaterialTheme.colorScheme.secondaryContainer
                    isGenerating -> MaterialTheme.colorScheme.error
                    text.isNotBlank() && enabled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = when {
                    isStopping -> MaterialTheme.colorScheme.onSecondaryContainer
                    isGenerating -> MaterialTheme.colorScheme.onError
                    text.isNotBlank() && enabled -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
                shape = CircleShape
            ) {
                if (isStopping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                } else if (isGenerating) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(20.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
}

// ── Benchmark stats row ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsRow(
    stats: InferenceStats,
    compatibilityWarning: String? = null,
    compatibilityStatus: CompatibilityStatus = CompatibilityStatus.UNKNOWN,
    speculativeEnabled: Boolean = false,
    draftCount: Int = 0,
    onTune: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            // Row 1: TPS / TTFT / backend — no clipping risk
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatChip("%.1f TPS".format(stats.tps))
                StatChip("${stats.ttftMs}ms TTFT")
                Text(
                    stats.backendName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            // Row 2: Speculative stats — only when spec is ON
            if (speculativeEnabled) {
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompatChip(compatibilityStatus)
                    StatChip("${(stats.draftAcceptRate * 100).toInt()}% accept")
                    StatChip("Draft $draftCount")
                    if (onTune != null) {
                        TextButton(
                            onClick = onTune,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("Tune ▶", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            // Expanded detail
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Prompt: %.1f TPS • Tokens: %d\n" +
                    "Backend: %s (ReqGpuLayers: %d)\n" +
                    "Threads: %d • Template: %s".format(
                        stats.promptEvalTps, stats.totalTokens,
                        stats.backendName, stats.reqGpuLayers,
                        stats.threads, stats.templateName
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (compatibilityWarning != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = compatibilityWarning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun CompatChip(status: CompatibilityStatus) {
    val (label, chipColor) = when (status) {
        CompatibilityStatus.GOOD    -> "✓ OK"  to Color(0xFF4CAF50)
        CompatibilityStatus.BAD     -> "✗ BAD" to Color(0xFFE53935)
        CompatibilityStatus.UNKNOWN -> "?  —"  to Color(0xFF9E9E9E)
    }
    Surface(
        color = chipColor.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = chipColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StatChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BenchmarkDialog(state: BenchmarkState.Done, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speculative Benchmark", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.entries.forEach { entry ->
                    val isHighlighted = entry.draftCount == state.bestDraftCount && entry.draftCount > 0
                    val label = if (entry.draftCount == 0) "CPU-only" else "Draft ${entry.draftCount}"
                    val acceptStr = if (entry.draftCount == 0) "—" else "${(entry.acceptRate * 100).toInt()}%"
                    Surface(
                        color = if (isHighlighted)
                            Color(0xFF4CAF50).copy(alpha = 0.12f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal)
                            Text("%.1f TPS".format(entry.tps), style = MaterialTheme.typography.bodySmall)
                            Text(acceptStr, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    state.recommendation,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

@Composable
private fun KnowledgeBar(
    chunks: List<KnowledgeChunk>,
    onRemove: (Long) -> Unit,
    onClearAll: () -> Unit
) {
    val totalTokens = chunks.sumOf { it.tokenEstimate }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Knowledge attached — ~$totalTokens tokens",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                TextButton(
                    onClick = onClearAll,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("Clear all", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            chunks.forEach { chunk ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "• ${chunk.documentTitle}  (~${chunk.tokenEstimate} tok)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = { onRemove(chunk.id) },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove chunk",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

private val suggestionPrompts = listOf(
    "Explain local AI in one sentence.",
    "Write a checklist for setting up a homelab.",
    "Summarize why zero-cloud AI matters.",
    "Draft a short product description for Pocket Node."
)

@Composable
private fun EmptyChatState(onSuggestionClick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Pocket Node is ready",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Running locally on this device with the recommended CPU profile.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        suggestionPrompts.forEach { prompt ->
            SuggestionChip(
                onClick = { onSuggestionClick(prompt) },
                label = { Text(prompt, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
            )
        }
    }
}

/**
 * Compact, non-intrusive session diagnostic — short session id, and lifecycle
 * state when it's not the unremarkable steady state. Never shows prompt or
 * response text. Tap to reveal recovery actions; a stale/reset-required state
 * must never be silently swallowed, so its label always differs visibly from
 * the normal "Session xxxxxxxx" label.
 */
@Composable
private fun SessionStatusChip(
    snapshot: SessionSnapshot,
    onResetSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val shortId = snapshot.sessionId.take(8)
    val (label, color) = when (snapshot.state) {
        SessionState.RESET_REQUIRED -> "Reset required" to MaterialTheme.colorScheme.error
        SessionState.STALE -> "Session stale" to MaterialTheme.colorScheme.error
        SessionState.INTERRUPTED -> "Interrupted" to MaterialTheme.colorScheme.tertiary
        SessionState.SENDING, SessionState.STREAMING -> "Session $shortId · active" to MaterialTheme.colorScheme.primary
        SessionState.COMPLETED, SessionState.IDLE -> "Session $shortId" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(modifier = modifier) {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            colors = AssistChipDefaults.assistChipColors(labelColor = color)
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Reset session context") },
                onClick = {
                    expanded = false
                    onResetSession()
                }
            )
        }
    }
}
