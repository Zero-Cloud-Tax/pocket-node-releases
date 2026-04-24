package com.pocketnode.app.ui.screens

import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketnode.app.data.model.ChatMessage
import com.pocketnode.app.inference.DocumentReader
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    currentAssistantMessage: String,
    isGenerating: Boolean,
    isLoadingModel: Boolean,
    isModelReady: Boolean,
    modelName: String?,
    modelError: String?,
    backendName: String,
    onSendMessage: (String, ByteArray?, Float, Float, Int) -> Unit,
    onClearChat: () -> Unit,
    onStopGeneration: () -> Unit,
    onDismissError: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var attachedFileName by remember { mutableStateOf<String?>(null) }
    var attachedDocumentText by remember { mutableStateOf<String?>(null) }
    var attachedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isReadingDocument by remember { mutableStateOf(false) }

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
                        val text = DocumentReader.extractText(context, it)
                        attachedFileName = name
                        attachedDocumentText = text
                    }
                    isReadingDocument = false
                }
            }
        }
    )


    val listState = rememberLazyListState()
    val totalItems = messages.size + (if (isGenerating) 1 else 0)

    LaunchedEffect(totalItems, currentAssistantMessage) {
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {

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
                        Surface(
                            shape = CircleShape,
                            color = if (backendName == "Vulkan")
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                backendName,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (backendName == "Vulkan")
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── Error banner ──
            AnimatedVisibility(visible = modelError != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                items(messages) { message ->
                    ChatBubble(message)
                }
                if (isGenerating && currentAssistantMessage.isNotEmpty()) {
                    item {
                        ChatBubble(
                            ChatMessage(
                                conversationId = 0,
                                role = "assistant",
                                content = currentAssistantMessage,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
                if (isGenerating && currentAssistantMessage.isEmpty()) {
                    item { TypingIndicator() }
                }
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
                },
                isGenerating = isGenerating,
                onStop = onStopGeneration,
                enabled = isModelReady && !isLoadingModel,
                onAttach = { documentLauncher.launch(arrayOf("application/pdf", "text/plain", "image/*")) },
                attachedFileName = attachedFileName,
                onRemoveAttachment = {
                    attachedFileName = null
                    attachedDocumentText = null
                    attachedImageBytes = null
                },
                isReadingDocument = isReadingDocument
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val clipboardManager = LocalClipboardManager.current

    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val timeLabel = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }

    val bubbleColor = if (isUser) {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.secondaryContainer
            )
        )
    }

    val shape = if (isUser)
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    else
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (!isUser) {
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text("AI",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold, fontSize = 8.sp
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(shape)
                    .background(bubbleColor)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { clipboardManager.setText(AnnotatedString(message.content)) }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (!isUser) {
                    MarkdownText(
                        markdown = message.content,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                } else {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        Text(
            text = timeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(
                start = if (!isUser) 36.dp else 0.dp,
                top = 3.dp, bottom = 2.dp
            )
        )
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dot1Alpha by infiniteTransition.animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(600, delayMillis = 0), RepeatMode.Reverse), "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(600, delayMillis = 200), RepeatMode.Reverse), "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(600, delayMillis = 400), RepeatMode.Reverse), "dot3"
    )

    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text("AI",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.ExtraBold, fontSize = 8.sp
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(dot1Alpha, dot2Alpha, dot3Alpha).forEach { alpha ->
                Box(
                    modifier = Modifier.size(8.dp).alpha(alpha).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSecondaryContainer)
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
    onStop: () -> Unit,
    enabled: Boolean,
    onAttach: () -> Unit,
    attachedFileName: String?,
    onRemoveAttachment: () -> Unit,
    isReadingDocument: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
                enabled = enabled && !isGenerating,
                maxLines = 4
            )

            Spacer(modifier = Modifier.width(12.dp))

            FloatingActionButton(
                onClick = if (isGenerating) onStop else onSend,
                modifier = Modifier.size(48.dp),
                containerColor = when {
                    isGenerating -> MaterialTheme.colorScheme.error
                    text.isNotBlank() && enabled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = when {
                    isGenerating -> MaterialTheme.colorScheme.onError
                    text.isNotBlank() && enabled -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
                shape = CircleShape
            ) {
                if (isGenerating) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(20.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
}

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, color: Color) {
    val context = LocalContext.current
    val markwon = remember { Markwon.create(context) }

    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(android.graphics.Color.argb(
                    (color.alpha * 255).toInt(),
                    (color.red * 255).toInt(),
                    (color.green * 255).toInt(),
                    (color.blue * 255).toInt()
                ))
                textSize = 16f
            }
        },
        update = { textView -> markwon.setMarkdown(textView, markdown) },
        modifier = modifier
    )
}
