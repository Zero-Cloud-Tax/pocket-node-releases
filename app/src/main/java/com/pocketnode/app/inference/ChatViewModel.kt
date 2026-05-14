package com.pocketnode.app.inference

import android.content.Context
import android.net.Uri
import android.util.Log
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class InferenceStats(
    val tps: Float,
    val ttftMs: Long,
    val draftAcceptRate: Float,
    val totalTokens: Int,
    val promptEvalTps: Float,
    val backendName: String
)

class ChatViewModel(
    private val inference: LlamaInference,
    private val repository: ChatRepository,
    private val app: MainApplication,
    private val defaultConversationId: Long = DEFAULT_CONVERSATION_ID
) : ViewModel() {

    companion object {
        const val DEFAULT_CONVERSATION_ID = 1L
        const val ASK_IMAGE_CONVERSATION_ID = 2L
        const val PROMPT_LAB_CONVERSATION_ID = 3L
        private const val DEFAULT_CONTEXT_SIZE = 4096
        private const val VISION_PROJECTOR_FILE_NAME = "mmproj-model-f16.gguf"
    }

    private var modelPtr = 0L
    private var contextPtr = 0L
    private var loadedModelPath: String? = null
    private var loadedContextSize = 0
    private var loadedThreadCount = 0
    private var loadedGpuLayers = 0

    // Draft model state (speculative decoding)
    private var draftModelPtr = 0L
    private var draftContextPtr = 0L
    private var loadedDraftModelPath: String? = null

    private var activeConversationId = defaultConversationId
    private var generatingConversationId: Long? = null
    private var messagesJob: Job? = null
    private val nativeSessionMutex = Mutex()
    // Raw FD for models opened from content:// URIs via /proc/self/fd; -1 = not in use
    private var rawFd = -1

    val messages = mutableStateListOf<ChatMessage>()
    val currentConversationId = mutableStateOf(defaultConversationId)
    val isGenerating = mutableStateOf(false)
    val currentAssistantMessage = mutableStateOf("")
    val visibleIsGenerating = mutableStateOf(false)
    val visibleAssistantMessage = mutableStateOf("")
    val isLoadingModel = mutableStateOf(false)
    val isModelReady = mutableStateOf(false)
    val modelName = mutableStateOf<String?>(null)
    val modelError = mutableStateOf<String?>(null)
    val backendName = mutableStateOf("CPU")
    val lastInferenceStats = mutableStateOf<InferenceStats?>(null)

    init {
        bindConversation(defaultConversationId)
        backendName.value = try { inference.nativeGetBackendName() } catch (_: Throwable) { "CPU" }
    }

    fun bindConversation(conversationId: Long) {
        if (activeConversationId == conversationId && messagesJob != null) return

        activeConversationId = conversationId
        currentConversationId.value = conversationId
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            repository.ensureConversation(conversationId, defaultTitleForConversation(conversationId))
            repository.getMessages(conversationId).collectLatest { history ->
                messages.clear()
                messages.addAll(history)
            }
        }
        syncVisibleGenerationState()
        modelError.value = null
    }

    fun loadModel(
        modelPath: String,
        contextSize: Int = DEFAULT_CONTEXT_SIZE,
        threadCount: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
        nGpuLayers: Int = 0,
        reloadIfConfigChanged: Boolean = true
    ) {
        if (loadedModelPath == modelPath && contextPtr != 0L && !reloadIfConfigChanged) {
            return
        }

        if (
            loadedModelPath == modelPath &&
            loadedContextSize == contextSize &&
            loadedThreadCount == threadCount &&
            loadedGpuLayers == nGpuLayers &&
            contextPtr != 0L
        ) return

        viewModelScope.launch(Dispatchers.IO) {
            // Resolve content:// URI → /proc/self/fd/<N>, or use the path directly
            val isContentUri = modelPath.startsWith("content://")
            val (effectivePath, newFd) = if (isContentUri) {
                val pfd = try {
                    app.contentResolver.openFileDescriptor(Uri.parse(modelPath), "r")
                } catch (_: SecurityException) {
                    withContext(Dispatchers.Main) {
                        modelError.value = "Android no longer allows access to this imported model. Re-import it from Model Hub so Pocket Node can copy it locally."
                        isLoadingModel.value = false
                    }
                    return@launch
                } ?: run {
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

            // RAM Validation
            val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            
            // If less than 800MB available, warn and prevent load
            if (memInfo.availMem < 800L * 1024 * 1024) {
                if (newFd >= 0) inference.nativeCloseFd(newFd)
                withContext(Dispatchers.Main) {
                    modelError.value = "Not enough RAM available. Please close other apps or use a smaller quantization model."
                    isLoadingModel.value = false
                }
                return@launch
            }

            var nextModelPtr = 0L
            var nextContextPtr = 0L

            try {
                nativeSessionMutex.withLock {
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
                    loadedContextSize = contextSize
                    loadedThreadCount = threadCount
                    loadedGpuLayers = nGpuLayers

                    app.activeSession = InferenceSession(contextPtr, displayName)
                }

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

    fun isLoadedModel(modelPath: String?): Boolean {
        return !modelPath.isNullOrBlank() && loadedModelPath == modelPath && contextPtr != 0L
    }

    /**
     * Load a draft model for speculative decoding.
     * [draftModelPath]  absolute path to the draft GGUF
     * [mainFamily]      family string of the main model (e.g. "SmolLM3") for compat check
     * [draftFamily]     family string of the draft model
     * [mainTokenizerHash] / [draftTokenizerHash] — SHA256 of each tokenizer.json if known
     */
    fun loadDraftModel(
        draftModelPath: String,
        mainContextSize: Int,
        threadCount: Int,
        nGpuLayers: Int = 0,
        mainFamily: String? = null,
        draftFamily: String? = null,
        mainTokenizerHash: String? = null,
        draftTokenizerHash: String? = null
    ) {
        // Tokenizer/family compatibility check
        if (mainTokenizerHash != null && draftTokenizerHash != null
            && mainTokenizerHash != draftTokenizerHash) {
            modelError.value = "Draft model has a different tokenizer than the main model. Speculative decoding requires matching tokenizers."
            return
        }
        if (mainFamily != null && draftFamily != null && mainFamily != draftFamily) {
            modelError.value = "Warning: draft model family ($draftFamily) differs from main model family ($mainFamily). Acceptance rate may be low."
            // Allow but warn; do not block
        }

        if (loadedDraftModelPath == draftModelPath && draftContextPtr != 0L) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                nativeSessionMutex.withLock {
                    // Free previous draft if any
                    if (draftContextPtr != 0L) {
                        inference.nativeFreeDraftContext(draftContextPtr)
                        draftContextPtr = 0L
                    }
                    if (draftModelPtr != 0L) {
                        inference.nativeFreeDraftModel(draftModelPtr)
                        draftModelPtr = 0L
                    }

                    val ptr = inference.nativeLoadDraftModel(draftModelPath, nGpuLayers)
                    if (ptr == 0L) throw RuntimeException(
                        inference.nativeGetLastError().ifBlank { "Unable to load draft model." }
                    )

                    // Draft context: match main up to 2048 for best acceptance rate
                    val draftCtxSize = minOf(mainContextSize, 2048)
                    val ctxPtr = inference.nativeCreateDraftContext(ptr, draftCtxSize, threadCount)
                    if (ctxPtr == 0L) throw RuntimeException(
                        inference.nativeGetLastError().ifBlank { "Unable to create draft context." }
                    )

                    draftModelPtr = ptr
                    draftContextPtr = ctxPtr
                    loadedDraftModelPath = draftModelPath
                    Log.i("PocketNode", "Draft model loaded: $draftModelPath (ctx=$draftCtxSize)")
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    modelError.value = "Draft model error: ${e.message}"
                }
            }
        }
    }

    fun unloadDraftModel() {
        viewModelScope.launch(Dispatchers.IO) {
            nativeSessionMutex.withLock {
                if (draftContextPtr != 0L) {
                    inference.nativeFreeDraftContext(draftContextPtr)
                    draftContextPtr = 0L
                }
                if (draftModelPtr != 0L) {
                    inference.nativeFreeDraftModel(draftModelPtr)
                    draftModelPtr = 0L
                }
                loadedDraftModelPath = null
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
        loadedContextSize = 0
        loadedThreadCount = 0
        loadedGpuLayers = 0
    }

    fun sendMessage(
        text: String,
        imageBytes: ByteArray? = null,
        conversationId: Long = defaultConversationId,
        clearConversationFirst: Boolean = false,
        temp: Float = 0.7f,
        topP: Float = 0.9f,
        topK: Int = 40,
        maxTokens: Int = 512,
        systemPrompt: String = "",
        template: PromptTemplate = PromptTemplate.ChatML,
        // Speculative decoding params (0 / false = disabled)
        speculativeEnabled: Boolean = false,
        nDraft: Int = 5,
        batchSize: Int = 512,
        ubatchSize: Int = 128,
        benchmarkMode: Boolean = false
    ) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank() || contextPtr == 0L || isGenerating.value) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (clearConversationFirst) {
                    if (contextPtr != 0L && conversationId == activeConversationId) {
                        inference.nativeClearCache(contextPtr)
                    }
                    repository.clearConversation(conversationId)
                }

                repository.saveMessage(
                    ChatMessage(conversationId = conversationId, role = "user", content = trimmedText)
                )

                withContext(Dispatchers.Main) {
                    generatingConversationId = conversationId
                    isGenerating.value = true
                    currentAssistantMessage.value = ""
                    syncVisibleGenerationState()
                    modelError.value = null
                }

                val conversationHistory = repository.getMessagesSnapshot(conversationId)
                val fullPrompt = repository.buildContextString(
                    messages = conversationHistory,
                    systemPrompt = systemPrompt,
                    template = template
                )

                // Save an empty assistant message first to get its ID
                val assistantMsgId = repository.saveMessage(
                    ChatMessage(conversationId = conversationId, role = "assistant", content = "")
                )
                
                var partialMessage = ""
                var lastUiUpdateTime = 0L
                var lastDbSaveTime = 0L

                val callback = object : LlamaCallback {
                    override fun onToken(token: String) {
                        partialMessage += token
                        val now = System.currentTimeMillis()

                        // Throttle UI updates to reduce recomposition and rendering pressure.
                        if (now - lastUiUpdateTime > 150) {
                            lastUiUpdateTime = now
                            viewModelScope.launch(Dispatchers.Main) {
                                currentAssistantMessage.value = partialMessage
                                syncVisibleGenerationState()
                            }
                        }

                        // Periodically persist to DB every 2000ms
                        if (now - lastDbSaveTime > 2000) {
                            lastDbSaveTime = now
                            viewModelScope.launch(Dispatchers.IO) {
                                repository.updateMessage(
                                    ChatMessage(id = assistantMsgId, conversationId = conversationId, role = "assistant", content = partialMessage)
                                )
                            }
                        }
                    }

                    override fun onStats(
                        tps: Float,
                        ttftMs: Long,
                        draftAcceptRate: Float,
                        totalTokens: Int,
                        promptEvalTps: Float,
                        backendName: String
                    ) {
                        val stats = InferenceStats(tps, ttftMs, draftAcceptRate, totalTokens, promptEvalTps, backendName)
                        if (benchmarkMode) {
                            Log.d("PocketNode-Bench",
                                "tps=%.1f ttft=%dms draft_accept=%.2f tokens=%d prompt_tps=%.1f backend=%s"
                                    .format(tps, ttftMs, draftAcceptRate, totalTokens, promptEvalTps, backendName))
                        }
                        viewModelScope.launch(Dispatchers.Main) {
                            lastInferenceStats.value = stats
                        }
                    }
                }

                var clipCtxPtr = 0L
                var imageEmbedPtr = 0L

                try {
                    nativeSessionMutex.withLock {
                        if (contextPtr == 0L) {
                            throw IllegalStateException("Model context is no longer available.")
                        }

                        if (imageBytes != null) {
                            val mmprojFile = resolveVisionProjectorFile()
                                ?: throw IllegalStateException(
                                    "Missing vision projector file. Download or import mmproj-model-f16.gguf from Model Hub before using Ask Image."
                                )

                            clipCtxPtr = inference.nativeLoadMmproj(mmprojFile.absolutePath)
                            if (clipCtxPtr == 0L) {
                                throw IllegalStateException(
                                    inference.nativeGetLastError().ifBlank { "Failed to load the vision projector model." }
                                )
                            }

                            imageEmbedPtr = inference.nativeMakeImageEmbed(clipCtxPtr, imageBytes)
                            if (imageEmbedPtr == 0L) {
                                throw IllegalStateException(
                                    inference.nativeGetLastError().ifBlank { "Failed to encode the selected image for this model." }
                                )
                            }
                        }

                        val effectiveDraftCtx = if (speculativeEnabled && draftContextPtr != 0L)
                            draftContextPtr else 0L

                        inference.nativeGenerate(
                            contextPtr, fullPrompt, imageEmbedPtr, maxTokens, temp, topP, topK, 1.1f,
                            effectiveDraftCtx, nDraft, batchSize, ubatchSize, callback
                        )
                    }
                } finally {
                    if (imageEmbedPtr != 0L) inference.nativeFreeImageEmbed(imageEmbedPtr)
                    if (clipCtxPtr != 0L) inference.nativeFreeMmproj(clipCtxPtr)
                }

                // Final save to DB
                repository.updateMessage(
                    ChatMessage(
                        id = assistantMsgId,
                        conversationId = conversationId,
                        role = "assistant",
                        content = partialMessage
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
                    generatingConversationId = null
                    currentAssistantMessage.value = ""
                    isGenerating.value = false
                    syncVisibleGenerationState()
                }
            }
        }
    }

    private fun syncVisibleGenerationState() {
        val isVisibleConversation = generatingConversationId == activeConversationId
        visibleIsGenerating.value = isGenerating.value && isVisibleConversation
        visibleAssistantMessage.value = if (isVisibleConversation) currentAssistantMessage.value else ""
    }

    fun stopGeneration() {
        if (contextPtr != 0L) inference.nativeStopGeneration(contextPtr)
    }

    fun dismissError() {
        modelError.value = null
    }

    fun clearChat(conversationId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            if (contextPtr != 0L && conversationId == activeConversationId) {
                inference.nativeClearCache(contextPtr)
            }
            repository.clearConversation(conversationId)
            withContext(Dispatchers.Main) {
                if (conversationId == activeConversationId) {
                    messages.clear()
                }
                currentAssistantMessage.value = ""
                isGenerating.value = false
            }
        }
    }

    fun getConversations() = repository.getConversations()

    fun createConversation(title: String, modelId: String = "unknown", onCreated: (Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = repository.createConversation(title = title, modelId = modelId)
            withContext(Dispatchers.Main) { onCreated(id) }
        }
    }

    fun renameConversation(conversationId: Long, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateConversationTitle(conversationId, title)
        }
    }

    private fun defaultTitleForConversation(conversationId: Long): String = when (conversationId) {
        DEFAULT_CONVERSATION_ID -> "Chat"
        ASK_IMAGE_CONVERSATION_ID -> "Ask Image"
        PROMPT_LAB_CONVERSATION_ID -> "Prompt Lab"
        else -> "Conversation"
    }

    private fun resolveVisionProjectorFile(): File? {
        val downloadsDir = app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        val modelsDir = app.getExternalFilesDir(null)?.let { File(it, "models") }
        val searchDirs = listOfNotNull(downloadsDir, modelsDir)

        searchDirs.forEach { dir ->
            val directMatch = File(dir, VISION_PROJECTOR_FILE_NAME)
            if (directMatch.exists()) {
                return directMatch
            }
        }

        return searchDirs
            .asSequence()
            .flatMap { dir -> dir.listFiles().orEmpty().asSequence() }
            .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .firstOrNull { file ->
                val normalizedName = file.name.lowercase()
                normalizedName == VISION_PROJECTOR_FILE_NAME.lowercase() ||
                    normalizedName.contains("mmproj") ||
                    normalizedName.contains("projector")
            }
    }

    override fun onCleared() {
        super.onCleared()
        app.activeSession = null
        if (draftContextPtr != 0L) inference.nativeFreeDraftContext(draftContextPtr)
        if (draftModelPtr != 0L) inference.nativeFreeDraftModel(draftModelPtr)
        if (contextPtr != 0L) inference.nativeFreeContext(contextPtr)
        if (modelPtr != 0L) inference.nativeFreeModel(modelPtr)
        loadedModelPath = null
        loadedDraftModelPath = null
        loadedContextSize = 0
        loadedThreadCount = 0
        loadedGpuLayers = 0
        messagesJob?.cancel()
        closeFdIfNeeded()
    }
}
