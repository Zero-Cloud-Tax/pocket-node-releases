package com.pocketnode.app.inference

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.app.data.ChatDao
import com.pocketnode.app.data.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChatViewModel(
    private val inference: LlamaInference,
    private val chatDao: ChatDao
) : ViewModel() {

    companion object {
        private const val DEFAULT_CONVERSATION_ID = 1L
        private const val DEFAULT_CONTEXT_SIZE = 4096
    }

    var modelPtr = 0L
    var contextPtr = 0L
    private var loadedModelPath: String? = null

    val messages = mutableStateListOf<ChatMessage>()
    val isGenerating = mutableStateOf(false)
    val currentAssistantMessage = mutableStateOf("")
    val isLoadingModel = mutableStateOf(false)
    val isModelReady = mutableStateOf(false)
    val modelName = mutableStateOf<String?>(null)
    val modelError = mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            chatDao.getMessagesForConversation(DEFAULT_CONVERSATION_ID).collectLatest { history ->
                messages.clear()
                messages.addAll(history)
            }
        }
    }

    fun loadModel(
        modelPath: String,
        contextSize: Int = DEFAULT_CONTEXT_SIZE,
        threadCount: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    ) {
        if (loadedModelPath == modelPath && contextPtr != 0L) return

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isLoadingModel.value = true
                isModelReady.value = false
                modelError.value = null
                modelName.value = File(modelPath).nameWithoutExtension
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

                nextModelPtr = inference.nativeLoadModel(modelPath, 0)
                check(nextModelPtr != 0L) { "Unable to load the selected model." }

                nextContextPtr = inference.nativeCreateContext(nextModelPtr, contextSize, threadCount)
                check(nextContextPtr != 0L) { "Unable to create an inference context for this model." }

                modelPtr = nextModelPtr
                contextPtr = nextContextPtr
                loadedModelPath = modelPath

                withContext(Dispatchers.Main) {
                    isModelReady.value = true
                }
            } catch (error: Throwable) {
                if (nextContextPtr != 0L) {
                    inference.nativeFreeContext(nextContextPtr)
                }
                if (nextModelPtr != 0L) {
                    inference.nativeFreeModel(nextModelPtr)
                }
                loadedModelPath = null

                withContext(Dispatchers.Main) {
                    modelError.value = error.message ?: "Failed to load the selected model."
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isLoadingModel.value = false
                }
            }
        }
    }

    fun sendMessage(text: String, conversationId: Long, temp: Float = 0.7f, topP: Float = 0.9f, topK: Int = 40) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank() || contextPtr == 0L || isGenerating.value) return

        viewModelScope.launch(Dispatchers.IO) {
            chatDao.insertMessage(
                ChatMessage(
                    conversationId = conversationId,
                    role = "user",
                    content = trimmedText
                )
            )

            withContext(Dispatchers.Main) {
                isGenerating.value = true
                currentAssistantMessage.value = ""
            }

            val callback = object : LlamaCallback {
                override fun onToken(token: String) {
                    viewModelScope.launch(Dispatchers.Main) {
                        currentAssistantMessage.value += token
                    }
                }
            }

            inference.nativeGenerate(
                contextPtr, trimmedText, 512, temp, topP, topK, 1.1f, callback
            )

            val assistantMsg = ChatMessage(
                conversationId = conversationId,
                role = "assistant",
                content = currentAssistantMessage.value
            )
            chatDao.insertMessage(assistantMsg)

            withContext(Dispatchers.Main) {
                currentAssistantMessage.value = ""
                isGenerating.value = false
            }
        }
    }

    fun clearChat(conversationId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            if (contextPtr != 0L) {
                inference.nativeClearCache(contextPtr)
            }
            chatDao.deleteMessagesForConversation(conversationId)
            chatDao.deleteConversation(conversationId)
            withContext(Dispatchers.Main) {
                messages.clear()
                currentAssistantMessage.value = ""
                isGenerating.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (contextPtr != 0L) inference.nativeFreeContext(contextPtr)
        if (modelPtr != 0L) inference.nativeFreeModel(modelPtr)
    }
}
