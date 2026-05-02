package com.pocketnode.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketnode.app.inference.ChatViewModel
import com.pocketnode.app.inference.PromptTemplate
import com.pocketnode.app.ui.ViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptLabScreen(
    modelPath: String,
    factory: ViewModelFactory,
    onNavigateBack: () -> Unit
) {
    val chatVm: ChatViewModel = viewModel(factory = factory)
    
    // Parameters
    var systemPrompt by remember { mutableStateOf("You are a helpful AI assistant.") }
    var userPrompt by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf(0.7f) }
    var topP by remember { mutableStateOf(0.9f) }
    var topK by remember { mutableStateOf(40f) }
    var maxTokens by remember { mutableStateOf(512f) }
    
    val isModelReady by chatVm.isModelReady
    val isGenerating by chatVm.isGenerating
    val response by chatVm.currentAssistantMessage
    
    val clipboardManager = LocalClipboardManager.current
    
    LaunchedEffect(modelPath) {
        if (modelPath.isNotBlank()) {
            chatVm.loadModel(modelPath, 2048, 4, 0) // Default settings for lab initially
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Prompt Lab") },
                actions = {
                    if (isGenerating) {
                        IconButton(onClick = { chatVm.stopGeneration() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(onClick = { 
                            if (userPrompt.isNotBlank() && isModelReady) {
                                // For prompt lab, we can clear chat and send as a fresh conversation each time
                                chatVm.clearChat(0L)
                                chatVm.sendMessage(
                                    text = userPrompt,
                                    imageBytes = null,
                                    conversationId = 0L,
                                    temp = temperature,
                                    topP = topP,
                                    topK = topK.toInt(),
                                    maxTokens = maxTokens.toInt(),
                                    systemPrompt = systemPrompt,
                                    template = PromptTemplate.ChatML // could also be configurable
                                )
                            }
                        }, enabled = userPrompt.isNotBlank() && isModelReady) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Run Prompt")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!isModelReady) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Loading model...", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Model: ${modelPath.split("/").last()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System Prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Temp: ${String.format("%.2f", temperature)}", style = MaterialTheme.typography.labelSmall)
                    Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..2f)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Top-P: ${String.format("%.2f", topP)}", style = MaterialTheme.typography.labelSmall)
                    Slider(value = topP, onValueChange = { topP = it }, valueRange = 0f..1f)
                }
            }
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Top-K: ${topK.toInt()}", style = MaterialTheme.typography.labelSmall)
                    Slider(value = topK, onValueChange = { topK = it }, valueRange = 1f..100f)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Max Tokens: ${maxTokens.toInt()}", style = MaterialTheme.typography.labelSmall)
                    Slider(value = maxTokens, onValueChange = { maxTokens = it }, valueRange = 1f..2048f)
                }
            }

            OutlinedTextField(
                value = userPrompt,
                onValueChange = { userPrompt = it },
                label = { Text("User Prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                placeholder = { Text("Enter the test input...") }
            )
            
            if (response.isNotEmpty() || chatVm.messages.isNotEmpty()) {
                val fullResponse = if (isGenerating) response else chatVm.messages.lastOrNull { it.role == "assistant" }?.content ?: ""
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Output", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            IconButton(
                                onClick = { clipboardManager.setText(AnnotatedString(fullResponse)) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        SelectionContainer {
                            MarkdownText(markdown = fullResponse, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
