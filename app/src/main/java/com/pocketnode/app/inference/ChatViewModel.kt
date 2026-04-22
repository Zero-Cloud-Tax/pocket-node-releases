package com.pocketnode.app.inference

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.app.InferenceSession
import com.pocketnode.app.MainApplication
import com.pocketnode.app.data.ChatRepository
import com.pocketnode.app.data.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChatViewModel(
    private val inference: LlamaInference,
    private val repository: ChatRepository,
    private val app: MainApplication
) : ViewModel() {

    companion object {
        private const val DEFAULT_CONVERSATION_ID = 1L
        private const val DEFAULT_CONTEXT_SIZE = 4096
    }

    private var modelPtr = 0L
    private var contextPtr = 0L
    private var loadedModelPath: String? = null
    // Raw FD for models opened from content:// URIs via /proc/self/fd; -1 = not in use
    private var rawFd = -1

    val messages = mutableStateListOf<ChatMessage>()
    val isGenerating = mutableStateOf(false)
    val currentAssistantMessage = mutableStateOf("")
    val isLoadingModel = mutableStateOf(false)
    val isModelReady = mutableStateOf(false)
    val modelName = mutableStateOf<String?>(null)
    val modelError = mutableStateOf<String?>(null)
    val backendName = mutableStateOf("CPU")

    init {
        viewModelScope.launch {
            repository.getMessages(DEFAULT_CONVERSATION_ID).collectLatest { history ->
                messages.clear()
                messages.addAll(history)
            }
        }
        backendName.value = try { inference.nativeGetBackendName() } catch (_: Throwable) { "CPU" }
    }

    fun loadModel(
        modelPath: String,
        contextSize: Int = DEFAULT_CONTEXT_SIZE,
        threadCount: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
        nGpuLayers: Int = 0
    ) {
        if (loadedModelPath == modelPath && contextPtr != 0L) return

        viewModelScope.launch(Dispatchers.IO) {
            // Resolve content:// URI → /proc/self/fd/<N>, or use the path directly
            val isContentUri = modelPath.startsWith("content://")
            val (effectivePath, newFd) = if (isContentUri) {
                val pfd = app.contentResolver.openFileDescriptor(Uri.parse(modelPath), "r")
                    ?: run {
                        withContext(Dispatchers.Main) {
                            modelError.value = "Cannot open model file descriptor."
                            isLoadingModel.value = false
                        }
                        return@launch
                    }
                val fd = pfd.detachFd()
                Pair("/proc/self/fd/$fd", fd)
            } else {
                Pair(modelPath, -1)
            }

            val displayName = if (isContentUri) {
                DocumentFile.fromSingleUri(app, Uri.parse(modelPath))?.name
                    ?.removeSuffix(".gguf") ?: "Model"
            } else {
                File(modelPath).nameWithoutExtension
            }

            withContext(Dispatchers.Main) {
                isLoadingModel.value = true
                isModelReady.value = false
                modelError.value = null
                modelName.value = displayName
            }

            var nextModelPtr = 0L
            var nextContextPtr = 0L

            try {
                if (contextPtr != 0L) {
                    inference.nativeFreeContext(contextPtr)
                    contextPtr = 0L
                }
                if (modelPtr != 0L) {
                    inference.nativeFreeModel(modelPtr)
                    modelPtr = 0L
                }
                // Close previous FD if switching from a content:// URI model
                closeFdIfNeeded()
                rawFd = newFd
                app.activeSession = null

                nextModelPtr = inference.nativeLoadModel(effectivePath, nGpuLayers)
                if (nextModelPtr == 0L) {
                    throw RuntimeException(
                        inference.nativeGetLastError().ifBlank { "Unable to load the selected model." }
                    )
                }

                nextContextPtr = inference.nativeCreateContext(nextModelPtr, contextSize, threadCount)
                if (nextContextPtr == 0L) {
                    throw RuntimeException(
                        inference.nativeGetLastError().ifBlank { "Unable to create inference context." }
                    )
                }

                modelPtr = nextModelPtr
                contextPtr = nextContextPtr
                loadedModelPath = modelPath

                app.activeSession = InferenceSession(contextPtr, displayName)

                withContext(Dispatchers.Main) {
                    isModelReady.value = true
                }
            } catch (e: OutOfMemoryError) {
                cleanupFailedLoad(nextModelPtr, nextContextPtr, newFd)
                withContext(Dispatchers.Main) {
                    modelError.value = "Out of memory — try a smaller model or close other apps."
                }
            } catch (e: Throwable) {
                cleanupFailedLoad(nextModelPtr, nextContextPtr, newFd)
                withContext(Dispatchers.Main) {
                    modelError.value = e.message ?: "Failed to load the selected model."
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isLoadingModel.value = false
                }
            }
        }
    }

    private fun closeFdIfNeeded() {
        if (rawFd >= 0) {
            inference.nativeCloseFd(rawFd)
            rawFd = -1
        }
    }

    private fun cleanupFailedLoad(nextModelPtr: Long, nextContextPtr: Long, newFd: Int) {
        if (nextContextPtr != 0L) inference.nativeFreeContext(nextContextPtr)
        if (nextModelPtr != 0L) inference.nativeFreeModel(nextModelPtr)
        // Close the new FD that failed — don't close rawFd (still owned by previous model)
        if (newFd >= 0 && newFd != rawFd) inference.nativeCloseFd(newFd)
        loadedModelPath = null
    }

    fun sendMessage(
        text: String,
        conversationId: Long,
        temp: Float = 0.7f,
        topP: Float = 0.9f,
        topK: Int = 40,
        maxTokens: Int = 512,
        systemPrompt: String = "",
        template: PromptTemplate = PromptTemplate.ChatML
    ) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank() || contextPtr == 0L || isGenerating.value) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.saveMessage(
                    ChatMessage(conversationId = conversationId, role = "user", content = trimmedText)
                )

                withContext(Dispatchers.Main) {
                    isGenerating.value = true
                    currentAssistantMessage.value = ""
                    modelError.value = null
                }

                val fullPrompt = repository.buildContextString(
                    messages = messages.toList() + ChatMessage(
                        conversationId = conversationId,
                        role = "user",
                        content = trimmedText
                    ),
                    systemPrompt = systemPrompt,
                    template = template
                )

                val callback = object : LlamaCallback {
                    override fun onToken(token: String) {
                        viewModelScope.launch(Dispatchers.Main) {
                            currentAssistantMessage.value += token
                        }
                    }
                }

                inference.nativeGenerate(
                    contextPtr, fullPrompt, maxTokens, temp, topP, topK, 1.1f, callback
                )

                repository.saveMessage(
                    ChatMessage(
                        conversationId = conversationId,
                        role = "assistant",
                        content = currentAssistantMessage.value
                    )
                )
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    modelError.value = "Out of memory during generation — try reducing context size."
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    modelError.value = e.message ?: "Generation failed."
                }
            } finally {
                withContext(Dispatchers.Main) {
                    currentAssistantMessage.value = ""
                    isGenerating.value = false
                }
            }
        }
    }

    fun stopGeneration() {
        if (contextPtr != 0L) inference.nativeStopGeneration(contextPtr)
    }

    fun dismissError() {
        modelError.value = null
    }

    fun clearChat(conversationId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            if (contextPtr != 0L) {
                inference.nativeClearCache(contextPtr)
            }
            repository.clearConversation(conversationId)
            withContext(Dispatchers.Main) {
                messages.clear()
                currentAssistantMessage.value = ""
                isGenerating.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        app.activeSession = null
        if (contextPtr != 0L) inference.nativeFreeContext(contextPtr)
        if (modelPtr != 0L) inference.nativeFreeModel(modelPtr)
        closeFdIfNeeded()
    }
}
