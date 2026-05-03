package com.pocketnode.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketnode.app.inference.ChatViewModel
import com.pocketnode.app.inference.PromptTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskImageScreen(
    modelPath: String,
    contextSize: Int,
    threadCount: Int,
    gpuLayers: Int,
    maxTokens: Int,
    chatVm: ChatViewModel,
    onNavigateBack: () -> Unit
) {
    val isModelReady by chatVm.isModelReady
    val isGenerating by chatVm.visibleIsGenerating
    val response by chatVm.visibleAssistantMessage
    val modelError by chatVm.modelError
    
    var prompt by remember { mutableStateOf("Describe this image in detail.") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            coroutineScope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    if (bitmap != null) {
                        selectedBitmap = bitmap
                        // Scale down for inference if needed
                        val maxDim = 384f
                        val scale = Math.min(maxDim / bitmap.width.toFloat(), maxDim / bitmap.height.toFloat())
                        val finalBitmap = if (scale < 1f) {
                            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                        } else bitmap
                        val outStream = ByteArrayOutputStream()
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outStream)
                        outStream.toByteArray()
                    } else null
                }
                selectedImageBytes = bytes
            }
        }
    }

    LaunchedEffect(modelPath, contextSize, threadCount, gpuLayers) {
        chatVm.bindConversation(ChatViewModel.ASK_IMAGE_CONVERSATION_ID)
        if (modelPath.isNotBlank()) {
            chatVm.loadModel(
                modelPath = modelPath,
                contextSize = contextSize,
                threadCount = threadCount,
                nGpuLayers = gpuLayers,
                reloadIfConfigChanged = false
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Ask Image") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isModelReady) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Loading vision model...", style = MaterialTheme.typography.bodySmall)
            }

            if (modelError != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = modelError.orEmpty(),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        IconButton(onClick = { chatVm.dismissError() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss error",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
            
            // Image Preview Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (selectedBitmap != null) {
                    Image(
                        bitmap = selectedBitmap!!.asImageBitmap(),
                        contentDescription = "Selected Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = "Add Photo",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tap to select an image",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Overlay button to pick image
                Button(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.matchParentSize(),
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                ) {}
            }
            
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Ask about this image") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
            
            Button(
                onClick = {
                    if (prompt.isNotBlank() && selectedImageBytes != null && isModelReady) {
                        chatVm.sendMessage(
                            text = prompt,
                            imageBytes = selectedImageBytes,
                            conversationId = ChatViewModel.ASK_IMAGE_CONVERSATION_ID,
                            clearConversationFirst = true,
                            temp = 0.7f,
                            topP = 0.9f,
                            topK = 40,
                            maxTokens = 512,
                            systemPrompt = "You are a helpful visual assistant.",
                            template = PromptTemplate.ChatML
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = prompt.isNotBlank() && selectedImageBytes != null && isModelReady && !isGenerating,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isGenerating) "Analyzing..." else "Ask Image")
            }
            
            if (response.isNotEmpty() || chatVm.messages.isNotEmpty()) {
                val fullResponse = if (isGenerating) response else chatVm.messages.lastOrNull { it.role == "assistant" }?.content ?: ""
                
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Analysis", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (isGenerating) {
                            DisableSelection {
                                Text(
                                    text = fullResponse,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            SelectionContainer {
                                MarkdownText(markdown = fullResponse, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
