package com.pocketnode.app.inference

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.app.InferenceSession
import com.pocketnode.app.MainApplication
import com.pocketnode.app.data.AppDatabase
import com.pocketnode.app.data.ChatRepository
import com.pocketnode.app.data.HashUtils
import com.pocketnode.app.data.VerificationStatus
import com.pocketnode.app.data.model.ChatMessage
import com.pocketnode.app.data.model.KnowledgeChunk
import com.pocketnode.app.data.model.LocalModel
import com.pocketnode.app.data.model.ModelRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Clock

data class InferenceStats(
    val tps: Float,
    val ttftMs: Long,
    val draftAcceptRate: Float,
    val totalTokens: Int,
    val promptEvalTps: Float,
    val backendName: String,
    val templateName: String = "",
    val reqGpuLayers: Int = 0,
    val threads: Int = 0,
    val nDrafted: Int = 0,
    val nAccepted: Int = 0,
    val nCtx: Int = 0,
    val nPast: Int = 0
)

data class BenchmarkEntry(val draftCount: Int, val tps: Float, val acceptRate: Float, val nDrafted: Int, val nAccepted: Int)

sealed class BenchmarkState {
    object Idle : BenchmarkState()
    object Running : BenchmarkState()
    data class Done(val entries: List<BenchmarkEntry>, val recommendation: String, val bestDraftCount: Int) : BenchmarkState()
}

class ChatViewModel(
    private val inference: InferenceEngine,
    private val repository: ChatRepository,
    private val app: MainApplication,
    private val defaultConversationId: Long = DEFAULT_CONVERSATION_ID,
    private val groundingClock: Clock = Clock.systemDefaultZone(),
    private val healthSummaryOverride: (() -> String)? = null,
    private val resolveModelRecordOverride: (suspend (String) -> LocalModel?)? = null,
    private val availableMemoryBytesOverride: (() -> Long)? = null
) : ViewModel() {

    companion object {
        const val DEFAULT_CONVERSATION_ID = 1L
        const val ASK_IMAGE_CONVERSATION_ID = 2L
        const val PROMPT_LAB_CONVERSATION_ID = 3L
        private const val DEFAULT_CONTEXT_SIZE = 4096
        private const val VISION_PROJECTOR_FILE_NAME = "mmproj-model-f16.gguf"
        const val MAX_ATTACHED_CHUNKS = 5
        private const val RESPONSE_RESERVE_TOKENS = 512
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
    private var generationJob: Job? = null
    @Volatile
    private var stopRequested = false
    private val nativeSessionMutex = Mutex()
    // Raw FD for models opened from content:// URIs via /proc/self/fd; -1 = not in use
    private var rawFd = -1
    private var loadedModelSelection: ResolvedModelSelection? = null

    val messages = mutableStateListOf<ChatMessage>()
    val currentConversationId = mutableStateOf(defaultConversationId)
    val isGenerating = mutableStateOf(false)
    val isStopping = mutableStateOf(false)
    val currentAssistantMessage = mutableStateOf("")
    val visibleIsGenerating = mutableStateOf(false)
    val visibleAssistantMessage = mutableStateOf("")
    val isLoadingModel = mutableStateOf(false)
    val isModelReady = mutableStateOf(false)
    val modelName = mutableStateOf<String?>(null)
    val modelError = mutableStateOf<String?>(null)
    val backendName = mutableStateOf("CPU")
    val selectedModelPath = mutableStateOf<String?>(null)
    val selectedModelVerificationStatus = mutableStateOf<String?>(null)
    val selectedModelIsDraft = mutableStateOf(false)
    val selectedModelIsPrimary = mutableStateOf(false)
    val lastSuccessfulInferenceAtMillis = mutableStateOf<Long?>(null)

    val mainModelMetadata = mutableStateOf<ModelMetadata?>(null)
    val draftModelMetadata = mutableStateOf<ModelMetadata?>(null)

    val benchmarkState = mutableStateOf<BenchmarkState>(BenchmarkState.Idle)

    // Last-used generation params — updated by sendMessage, consumed by runSpeculativeBenchmark
    private var lastTemplate: com.pocketnode.app.inference.PromptTemplate? = null
    private var lastTemp: Float = 0.7f
    private var lastBatchSize: Int = 512
    private var lastUbatchSize: Int = 512

    val draftCompatibilityStatus = derivedStateOf {
        val main = mainModelMetadata.value
        val draft = draftModelMetadata.value
        if (main == null || draft == null) return@derivedStateOf CompatibilityStatus.UNKNOWN
        // Vocab size is the correct compatibility signal — architecture names differ across
        // families (e.g. "smollm3" vs "llama") even when they share the same 128k vocabulary.
        if (main.vocabSize == draft.vocabSize) CompatibilityStatus.GOOD else CompatibilityStatus.BAD
    }
    
    val lastInferenceStats = mutableStateOf<InferenceStats?>(null)

    // ── Attached knowledge chunks (cleared after each send) ───────────────────
    private val _attachedChunks = mutableStateListOf<KnowledgeChunk>()
    val attachedChunks: List<KnowledgeChunk> get() = _attachedChunks

    fun attachChunk(chunk: KnowledgeChunk) {
        if (_attachedChunks.size < MAX_ATTACHED_CHUNKS && _attachedChunks.none { it.id == chunk.id }) {
            _attachedChunks.add(chunk)
        }
    }

    fun removeChunk(chunkId: Long) { _attachedChunks.removeAll { it.id == chunkId } }

    fun clearAttachedChunks() { _attachedChunks.clear() }

    fun runSpeculativeBenchmark() {
        if (contextPtr == 0L || draftContextPtr == 0L) return
        if (isGenerating.value || benchmarkState.value is BenchmarkState.Running) return

        viewModelScope.launch(Dispatchers.IO) {
            benchmarkState.value = BenchmarkState.Running

            val template = lastTemplate ?: PromptTemplate.ChatML
            val temp = lastTemp
            val batchSize = lastBatchSize
            val ubatchSize = lastUbatchSize
            val benchPrompt = template.format("", emptyList(), "What is 2+2? Answer in one word.")
            val entries = mutableListOf<BenchmarkEntry>()

            nativeSessionMutex.withLock {
                // CPU-only baseline (no draft ctx)
                var cpuTps = 0f
                val cpuCallback = object : LlamaCallback {
                    override fun onToken(token: String) {}
                    override fun onStats(tps: Float, ttftMs: Long, draftAcceptRate: Float, totalTokens: Int, promptEvalTps: Float, backendName: String, nDrafted: Int, nAccepted: Int, nCtx: Int, nPast: Int) {
                        cpuTps = tps
                    }
                }
                inference.nativeGenerate(contextPtr, benchPrompt, 0L, 50, temp, 0.9f, 40, 1.1f, 0L, 0, batchSize, ubatchSize, cpuCallback)

                // Draft counts 1, 2, 3
                for (n in 1..3) {
                    var entryTps = 0f; var entryAccept = 0f; var entryDrafted = 0; var entryAccepted = 0
                    val cb = object : LlamaCallback {
                        override fun onToken(token: String) {}
                        override fun onStats(tps: Float, ttftMs: Long, draftAcceptRate: Float, totalTokens: Int, promptEvalTps: Float, backendName: String, nDrafted: Int, nAccepted: Int, nCtx: Int, nPast: Int) {
                            entryTps = tps; entryAccept = draftAcceptRate; entryDrafted = nDrafted; entryAccepted = nAccepted
                        }
                    }
                    inference.nativeGenerate(contextPtr, benchPrompt, 0L, 50, temp, 0.9f, 40, 1.1f, draftContextPtr, n, batchSize, ubatchSize, cb)
                    entries += BenchmarkEntry(n, entryTps, entryAccept, entryDrafted, entryAccepted)
                }

                val bestSpec = entries.maxByOrNull { it.tps }
                val recommendation: String
                val bestDraftCount: Int
                if (bestSpec != null && bestSpec.tps > cpuTps) {
                    recommendation = "Draft ${"Count ${bestSpec.draftCount}"} wins: ${"%.1f".format(bestSpec.tps)} TPS vs CPU ${"%.1f".format(cpuTps)} TPS"
                    bestDraftCount = bestSpec.draftCount
                } else {
                    recommendation = "CPU-only wins: ${"%.1f".format(cpuTps)} TPS (spec adds overhead)"
                    bestDraftCount = 0
                }

                // Include CPU baseline as a display entry (draftCount=0)
                val allEntries = listOf(BenchmarkEntry(0, cpuTps, 0f, 0, 0)) + entries
                benchmarkState.value = BenchmarkState.Done(allEntries, recommendation, bestDraftCount)
            }
        }
    }

    fun dismissBenchmark() {
        benchmarkState.value = BenchmarkState.Idle
    }

    init {
        bindConversation(defaultConversationId)
        backendName.value = try { BackendInfo.normalize(inference.nativeGetBackendName()) } catch (_: Throwable) { "CPU" }
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

        validateModelFile(modelPath)?.let { error ->
            modelError.value = error
            return
        }

        logInfo(
            "Model load config: path=$modelPath ctx=$contextSize threads=$threadCount gpu_layers=$nGpuLayers speculative=false"
        )

        viewModelScope.launch(Dispatchers.IO) {
            val selectedModel = resolveModelRecord(modelPath)
            val selectedIdentity = resolveSelection(modelPath, selectedModel)
            logSelectedModelResolution("preload", selectedIdentity)

            validatePrimaryModelSelection(selectedIdentity)?.let { error ->
                withContext(Dispatchers.Main) {
                    applySelectedModelUiState(selectedIdentity)
                    modelError.value = error
                    isModelReady.value = false
                }
                return@launch
            }

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

            val displayName = selectedIdentity.selectedModelName

            withContext(Dispatchers.Main) {
                isLoadingModel.value = true
                isModelReady.value = false
                modelError.value = null
                applySelectedModelUiState(selectedIdentity)
                isStopping.value = false
            }

            // RAM Validation
            val availableMemoryBytes = availableMemoryBytesOverride?.invoke() ?: run {
                val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)
                memInfo.availMem
            }
            
            // If less than 800MB available, warn and prevent load
            if (availableMemoryBytes < 800L * 1024 * 1024) {
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
                    loadedModelSelection = selectedIdentity
                    
                    val metaArray = inference.nativeGetModelMetadata(contextPtr)
                    if (metaArray != null && metaArray.size >= 5) {
                        val metadata = ModelMetadata(
                            architecture = metaArray[0],
                            name = metaArray[1],
                            tokenizerModel = metaArray[2],
                            vocabSize = metaArray[3].toIntOrNull() ?: 0,
                            chatTemplate = metaArray[4]
                        )
                        validateLoadedModelMetadata(selectedIdentity, metadata)?.let { mismatch ->
                            throw IllegalStateException(mismatch)
                        }
                        mainModelMetadata.value = metadata
                    } else {
                        mainModelMetadata.value = null
                    }

                    app.activeSession = InferenceSession(contextPtr, displayName)
                }

                withContext(Dispatchers.Main) {
                    applySelectedModelUiState(selectedIdentity)
                    isModelReady.value = true
                    backendName.value = try {
                        BackendInfo.normalize(inference.nativeGetBackendName())
                    } catch (_: Throwable) {
                        "CPU"
                    }
                }
                logInfo(
                    "Model resolution[loaded]: selectedModelId=${selectedIdentity.selectedModelId} selectedModelName=${selectedIdentity.selectedModelName} verificationStatus=${selectedIdentity.verificationStatus ?: "unknown"} backendLabel=${backendName.value} contextPtrState=${if (contextPtr != 0L) "ready" else "null"}"
                )
            } catch (e: OutOfMemoryError) {
                cleanupFailedLoad(nextModelPtr, nextContextPtr, newFd)
                withContext(Dispatchers.Main) {
                    modelError.value = "Out of memory — try a smaller model or close other apps."
                }
            } catch (e: Throwable) {
                cleanupFailedLoad(nextModelPtr, nextContextPtr, newFd)
                withContext(Dispatchers.Main) {
                    modelError.value = e.message ?: "Failed to load the selected model."
                    isModelReady.value = false
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
        validateModelFile(draftModelPath)?.let { error ->
            modelError.value = "Draft model error: $error"
            return
        }

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

        Log.i(
            "PocketNode",
            "Draft load config: path=$draftModelPath ctx=${minOf(mainContextSize, 2048)} threads=$threadCount gpu_layers=$nGpuLayers speculative=true"
        )

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
                    
                    val metaArray = inference.nativeGetModelMetadata(draftContextPtr)
                    if (metaArray != null && metaArray.size >= 5) {
                        draftModelMetadata.value = ModelMetadata(
                            architecture = metaArray[0],
                            name = metaArray[1],
                            tokenizerModel = metaArray[2],
                            vocabSize = metaArray[3].toIntOrNull() ?: 0,
                            chatTemplate = metaArray[4]
                        )
                    } else {
                        draftModelMetadata.value = null
                    }
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
                draftModelMetadata.value = null
            }
        }
    }

    fun validateModelFile(path: String): String? {
        if (path.startsWith("content://")) {
            val uri = runCatching { Uri.parse(path) }.getOrNull()
                ?: return "Model file path is invalid."
            val document = runCatching { DocumentFile.fromSingleUri(app, uri) }.getOrNull()
                ?: return "Model file is no longer available. Re-import it from Model Hub."
            val displayName = document.name ?: return "Model file is missing a filename."
            if (!displayName.endsWith(".gguf", ignoreCase = true)) {
                return "Not a GGUF model file."
            }
            val length = document.length()
            if (length in 1 until 10_000_000L) {
                return "Model file appears corrupted or too small (< 10 MB)."
            }
            return null
        }
        val file = File(path)
        if (!file.exists() || !file.isFile)
            return "Model file not found. The configured model is no longer available locally."
        if (file.name.endsWith(".part", ignoreCase = true))
            return "Incomplete download detected. Delete this file and download again."
        if (!file.name.endsWith(".gguf", ignoreCase = true))
            return "Not a GGUF model file."
        if (file.length() < 10_000_000L)
            return "Model file appears corrupted or too small (< 10 MB)."
        return null
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
        // Zero class fields so sendMessageInternal's contextPtr == 0L guard fires correctly.
        // If validation threw after the class fields were already assigned (post-metadata check),
        // the fields still hold the now-freed pointer values without this reset.
        contextPtr = 0L
        modelPtr = 0L
        loadedModelPath = null
        loadedContextSize = 0
        loadedThreadCount = 0
        loadedGpuLayers = 0
        loadedModelSelection = null
        mainModelMetadata.value = null
        app.activeSession = null
    }

    private suspend fun resolveModelRecord(modelPath: String): LocalModel? {
        resolveModelRecordOverride?.let { return it(modelPath) }
        return runCatching {
            AppDatabase.getInstance(app).modelDao().getModelByPath(modelPath)
        }.getOrNull()
    }

    private fun resolveSelection(modelPath: String, model: LocalModel?): ResolvedModelSelection {
        val file = if (!modelPath.startsWith("content://")) File(modelPath) else null
        val fileName = when {
            model != null -> File(model.path).name
            file != null -> file.name
            else -> modelPath.substringAfterLast('/')
        }
        val sizeBytes = when {
            model != null && model.sizeBytes > 0L -> model.sizeBytes
            file != null && file.exists() -> file.length()
            else -> 0L
        }
        val shaPrefix = when {
            model?.sha256 != null -> model.sha256.take(12)
            file != null && file.exists() && file.length() in 10_000_000L..150_000_000L ->
                runCatching { sha256Prefix(file) }.getOrNull()
            else -> null
        }
        return ResolvedModelSelection(
            selectedModelId = model?.name ?: fileName.removeSuffix(".gguf"),
            selectedModelName = model?.name ?: fileName.removeSuffix(".gguf"),
            selectedModelDbId = model?.id,
            resolvedModelPath = model?.path ?: modelPath,
            resolvedFileName = fileName,
            fileSizeBytes = sizeBytes,
            sha256Prefix = shaPrefix,
            isDraft = model?.role == ModelRole.DRAFT.name,
            isPrimary = model?.role != ModelRole.DRAFT.name,
            verificationStatus = model?.verificationStatus
        )
    }

    private fun applySelectedModelUiState(selection: ResolvedModelSelection) {
        selectedModelPath.value = selection.resolvedModelPath
        selectedModelVerificationStatus.value = selection.verificationStatus
        selectedModelIsDraft.value = selection.isDraft
        selectedModelIsPrimary.value = selection.isPrimary
        modelName.value = selection.selectedModelName
    }

    private fun validatePrimaryModelSelection(selection: ResolvedModelSelection): String? {
        if (selection.isDraft) {
            logInfo(
                "Model resolution[blocked]: selectedModelId=${selection.selectedModelId} selectedModelName=${selection.selectedModelName} verificationStatus=${selection.verificationStatus ?: "unknown"} reason=draft_selected_for_primary"
            )
            return "Draft model selected for chat: ${selection.selectedModelName}. Choose a Primary model from Chat Models or move this draft back to the draft slot."
        }
        if (selection.verificationStatus == VerificationStatus.FAILED && selection.isPrimary) {
            logInfo(
                "Model resolution[blocked]: selectedModelId=${selection.selectedModelId} selectedModelName=${selection.selectedModelName} verificationStatus=${selection.verificationStatus} reason=failed_verification"
            )
            return "Primary model failed verification: ${selection.selectedModelName}. Rescan Model Hub, re-import the GGUF, or select another verified model before chatting."
        }
        val knownOperatorHash = HashUtils.KNOWN_HASHES[selection.selectedModelName]
        if (knownOperatorHash != null && selection.sha256Prefix != null) {
            val expectedPrefix = knownOperatorHash.take(selection.sha256Prefix.length)
            if (!selection.sha256Prefix.equals(expectedPrefix, ignoreCase = true)) {
                logInfo(
                    "Model resolution[blocked]: selectedModelId=${selection.selectedModelId} selectedModelName=${selection.selectedModelName} verificationStatus=${selection.verificationStatus ?: "unknown"} reason=sha_prefix_mismatch"
                )
                return "Primary model identity mismatch: ${selection.selectedModelName} does not match the expected Pocket Node artifact. Rescan, re-import, or select another model."
            }
        }
        return null
    }

    private fun validateLoadedModelMetadata(
        selection: ResolvedModelSelection,
        metadata: ModelMetadata
    ): String? {
        logInfo(
            "Post-load metadata: selectedModelId=${selection.selectedModelId} selectedModelName=${selection.selectedModelName} verificationStatus=${selection.verificationStatus ?: "unknown"} metadata_name=${metadata.name} metadata_arch=${metadata.architecture} metadata_chatTemplate=${metadata.chatTemplate.take(120).replace("\n", "\\n")}"
        )
        if (selection.isDraft) {
            return "Draft model was loaded into the primary chat slot: ${selection.selectedModelName}"
        }

        val metadataName = metadata.name.lowercase()
        val selectedName = selection.selectedModelName.lowercase()
        val selectedLooksLikeDraft =
            selectedName.contains("draft") ||
                selection.resolvedFileName.lowercase().contains("draft")
        val explicitDraftMetadata =
            metadataName.contains("draft") ||
                metadataName.contains("smollm2 135m") ||
                metadataName.contains("smollm2-135m")

        if (explicitDraftMetadata && !selectedLooksLikeDraft) {
            logInfo(
                "Post-load metadata validation: selectedModelId=${selection.selectedModelId} selectedModelName=${selection.selectedModelName} result=blocked_draft_signature"
            )
            return "Loaded model metadata does not match the selected primary model. Selected ${selection.selectedModelName}, but native metadata reports ${metadata.name}."
        }
        logInfo(
            "Post-load metadata validation: selectedModelId=${selection.selectedModelId} selectedModelName=${selection.selectedModelName} result=pass"
        )
        return null
    }

    private fun logSelectedModelResolution(stage: String, selection: ResolvedModelSelection) {
        logInfo(
            "Model resolution[$stage]: selectedModelId=${selection.selectedModelId} selectedModelName=${selection.selectedModelName} selectedModelDbId=${selection.selectedModelDbId ?: "unknown"} resolvedModelPath=${selection.resolvedModelPath} resolvedFileName=${selection.resolvedFileName} fileSizeBytes=${selection.fileSizeBytes} sha256Prefix=${selection.sha256Prefix ?: "unknown"} isDraft=${selection.isDraft} isPrimary=${selection.isPrimary} verificationStatus=${selection.verificationStatus ?: "unknown"}"
        )
    }

    private fun sha256Prefix(file: File, prefixChars: Int = 12): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(prefixChars)
    }

    private data class ResolvedModelSelection(
        val selectedModelId: String,
        val selectedModelName: String,
        val selectedModelDbId: String?,
        val resolvedModelPath: String,
        val resolvedFileName: String,
        val fileSizeBytes: Long,
        val sha256Prefix: String?,
        val isDraft: Boolean,
        val isPrimary: Boolean,
        val verificationStatus: String?
    )

    private fun logInfo(message: String) {
        runCatching { Log.i("PocketNode", message) }
    }

    private fun logTemplateResolution(
        requestedTemplate: PromptTemplate,
        effectiveTemplate: PromptTemplate,
        resolverResult: TemplateResolution,
        isManualOverride: Boolean
    ) {
        val meta = mainModelMetadata.value
        val selectedName = loadedModelSelection?.selectedModelName ?: ""
        val reason = if (isManualOverride) "manual_override_${requestedTemplate.name}" else resolverResult.reason
        logInfo(
            "PromptTemplateResolver: selectedModelName=$selectedName" +
            " metadata_name=${meta?.name ?: "unknown"}" +
            " metadata_arch=${meta?.architecture ?: "unknown"}" +
            " chatTemplatePresent=${meta?.chatTemplate?.isNotEmpty() == true}" +
            " manualOverride=$isManualOverride" +
            " decision=${effectiveTemplate.name}" +
            " reason=$reason"
        )
    }

    private fun preview(text: String?, maxChars: Int = 300): String {
        if (text.isNullOrBlank()) return ""
        val flattened = text.replace(Regex("\\s+"), " ").trim()
        return if (flattened.length <= maxChars) flattened else flattened.take(maxChars) + "..."
    }

    private val internalPromptLeakMarkers = listOf(
        "Grounding facts for this turn:",
        "Current device date/time:",
        "Current device timezone:",
        "Pocket Node local health:",
        "Policy: Do not invent live node/service status.",
        "User message:",
        "<POCKET_NODE_CONTEXT>",
        "</POCKET_NODE_CONTEXT>",
        "Do not repeat, quote, or reveal the context block",
        "Use the following context silently."
    )

    private data class SanitizedAssistantOutput(
        val visibleText: String,
        val leakDetected: Boolean
    )

    private fun sanitizeAssistantOutput(rawOutput: String, finalPass: Boolean): SanitizedAssistantOutput {
        val trimmedStart = rawOutput.trimStart()
        val leakDetected = internalPromptLeakMarkers.any { trimmedStart.startsWith(it) } ||
            internalPromptLeakMarkers.count { marker ->
                trimmedStart.take(800).contains(marker)
            } >= 2

        if (!leakDetected) {
            return SanitizedAssistantOutput(rawOutput, leakDetected = false)
        }

        return if (finalPass) {
            SanitizedAssistantOutput(
                visibleText = "I had an internal prompt-formatting error on that turn. Please resend your message.",
                leakDetected = true
            )
        } else {
            SanitizedAssistantOutput(visibleText = "", leakDetected = true)
        }
    }

    private fun buildPocketNodeHealthSummary(): String {
        val batteryManager = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val charging = batteryManager.isCharging

        val thermalCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("NewApi")
            powerManager.currentThermalStatus
        } else {
            0
        }

        val thermalStatus = thermalStatusString(thermalCode)
        val modelLoaded = app.activeSession != null
        val eligible = modelLoaded && (batteryPercent >= 30 || charging) && thermalCode < 3
        val backend = try {
            BackendInfo.normalize(inference.nativeGetBackendName())
        } catch (_: Throwable) {
            backendName.value
        }
        val reason = when {
            !modelLoaded -> "model_not_loaded"
            batteryPercent < 30 && !charging -> "battery_below_threshold"
            thermalCode >= 3 -> "thermal_severe"
            else -> null
        }

        return buildString {
            append("service_alive=").append(true)
            append(" model_loaded=").append(modelLoaded)
            append(" backend=").append(backend)
            append(" battery=").append(batteryPercent).append('%')
            append(" charging=").append(charging)
            append(" thermal=").append(thermalStatus)
            append(" eligible_for_inference=").append(eligible)
            if (reason != null) {
                append(" reason_if_not_eligible=").append(reason)
            }
        }
    }

    internal fun setLoadedContextForTesting(
        modelPtr: Long,
        contextPtr: Long,
        modelName: String = "Test Model",
        backend: String = "CPU"
    ) {
        this.modelPtr = modelPtr
        this.contextPtr = contextPtr
        this.modelName.value = modelName
        this.backendName.value = backend
        this.isModelReady.value = true
        this.selectedModelPath.value = "test-model.gguf"
        this.selectedModelVerificationStatus.value = VerificationStatus.VERIFIED
        this.selectedModelIsDraft.value = false
        this.selectedModelIsPrimary.value = true
        loadedModelPath = "test-model.gguf"
        loadedContextSize = DEFAULT_CONTEXT_SIZE
        loadedThreadCount = 4
        loadedGpuLayers = 0
        app.activeSession = InferenceSession(contextPtr, modelName)
    }

    private fun thermalStatusString(code: Int): String = when (code) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "none"
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
        benchmarkMode: Boolean = false,
        serviceStateSummary: String? = null
    ) {
        val priorGenerationJob = generationJob
        generationJob = viewModelScope.launch(Dispatchers.IO) {
            if (priorGenerationJob != null) {
                logInfo("Chat generation: waiting for prior job cleanup before starting a new prompt")
                priorGenerationJob.join()
            }
            stopRequested = false
            withContext(Dispatchers.Main) {
                isStopping.value = false
            }
            logInfo(
                "Chat generation: new generation requested isGenerating=${isGenerating.value} stopRequested=$stopRequested"
            )
            sendMessageInternal(
                text = text,
                imageBytes = imageBytes,
                conversationId = conversationId,
                clearConversationFirst = clearConversationFirst,
                temp = temp,
                topP = topP,
                topK = topK,
                maxTokens = maxTokens,
                systemPrompt = systemPrompt,
                template = template,
                speculativeEnabled = speculativeEnabled,
                nDraft = nDraft,
                batchSize = batchSize,
                ubatchSize = ubatchSize,
                benchmarkMode = benchmarkMode,
                serviceStateSummary = serviceStateSummary
            )
        }
    }

    internal suspend fun sendMessageInternal(
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
        speculativeEnabled: Boolean = false,
        nDraft: Int = 5,
        batchSize: Int = 512,
        ubatchSize: Int = 128,
        benchmarkMode: Boolean = false,
        serviceStateSummary: String? = null
    ) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank() || isGenerating.value) return
        if (contextPtr == 0L) {
            modelError.value = modelError.value ?: "Primary chat model is not loaded."
            return
        }

        // Context budget guard — checked on main thread before coroutine launch.
        val chunksForSend = _attachedChunks.toList()
        if (chunksForSend.isNotEmpty()) {
            val effectiveCtx = if (loadedContextSize > 0) loadedContextSize else DEFAULT_CONTEXT_SIZE
            val knowledgeToks = chunksForSend.sumOf { it.tokenEstimate }
            val promptToks = trimmedText.length / 4
            val nPast = lastInferenceStats.value?.nPast ?: 0
            if (nPast + knowledgeToks + promptToks + RESPONSE_RESERVE_TOKENS > effectiveCtx) {
                modelError.value =
                    "Selected knowledge exceeds available context. Remove some chunks."
                return
            }
        }
        _attachedChunks.clear()

        // Resolve effective template: Auto defers to metadata-driven auto-selection; explicit choice is manual override.
        val resolverResult = PromptTemplateResolver.resolve(mainModelMetadata.value, loadedModelSelection?.selectedModelName ?: "")
        val isManualOverride = template !is PromptTemplate.Auto
        val effectiveTemplate = if (isManualOverride) template else resolverResult.template
        logTemplateResolution(template, effectiveTemplate, resolverResult, isManualOverride)

        lastTemplate = effectiveTemplate
        lastTemp = temp
        lastBatchSize = batchSize
        lastUbatchSize = ubatchSize

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
            logInfo(
                "Chat persistence[user]: rawUserMessage=\"${preview(trimmedText)}\" savedVisible=true conversationId=$conversationId"
            )

            withContext(Dispatchers.Main) {
                generatingConversationId = conversationId
                isGenerating.value = true
                currentAssistantMessage.value = ""
                syncVisibleGenerationState()
                modelError.value = null
            }

            val conversationHistory = repository.getMessagesSnapshot(conversationId)
            val knowledgeBlock = buildKnowledgeBlock(chunksForSend)
            val groundedTurn = PromptGrounding.buildGroundedTurnPrompt(
                baseSystemPrompt = systemPrompt,
                rawUserPrompt = trimmedText,
                deviceTime = PromptGrounding.currentDeviceDateTime(groundingClock),
                pocketNodeHealthSummary = healthSummaryOverride?.invoke() ?: buildPocketNodeHealthSummary(),
                serviceStateSummary = serviceStateSummary
            )
            val fullPrompt = repository.buildContextString(
                messages = conversationHistory,
                systemPrompt = groundedTurn.systemPrompt,
                template = effectiveTemplate,
                knowledgeContext = knowledgeBlock,
                promptOverride = groundedTurn.userPrompt
            )
            logPromptGrounding(trimmedText, groundedTurn, fullPrompt)
            logInfo(
                "Chat send: rawUserMessage=\"${preview(trimmedText)}\" promptOverrideUsed=${groundedTurn.userPrompt != trimmedText} groundedPromptPreview=\"${preview(groundedTurn.groundedContextPreview)}\""
            )

            // Save an empty assistant message first to get its ID
            val assistantMsgId = repository.saveMessage(
                ChatMessage(conversationId = conversationId, role = "assistant", content = "")
            )
            logInfo("Chat generation: assistant placeholder created id=$assistantMsgId")

            var partialMessage = ""
            var lastUiUpdateTime = 0L
            var lastDbSaveTime = 0L

            val callback = object : LlamaCallback {
                override fun onToken(token: String) {
                    partialMessage += token
                    val sanitized = sanitizeAssistantOutput(partialMessage, finalPass = false)
                    val now = System.currentTimeMillis()

                    // Throttle UI updates to reduce recomposition and rendering pressure.
                    if (now - lastUiUpdateTime > 150) {
                        lastUiUpdateTime = now
                        viewModelScope.launch(Dispatchers.Main) {
                            currentAssistantMessage.value = sanitized.visibleText
                            syncVisibleGenerationState()
                        }
                    }

                    // Periodically persist to DB every 2000ms
                    if (now - lastDbSaveTime > 2000) {
                        lastDbSaveTime = now
                        viewModelScope.launch(Dispatchers.IO) {
                            repository.updateMessage(
                                ChatMessage(
                                    id = assistantMsgId,
                                    conversationId = conversationId,
                                    role = "assistant",
                                    content = sanitized.visibleText
                                )
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
                    backendName: String,
                    nDrafted: Int,
                    nAccepted: Int,
                    nCtx: Int,
                    nPast: Int
                ) {
                    val actualBackend = BackendInfo.normalize(backendName)
                    val stats = InferenceStats(tps, ttftMs, draftAcceptRate, totalTokens, promptEvalTps, actualBackend, effectiveTemplate.name, loadedGpuLayers, loadedThreadCount, nDrafted, nAccepted, nCtx, nPast)
                    if (benchmarkMode && com.pocketnode.app.BuildConfig.DEBUG) {
                        Log.d("PocketNode-Bench",
                            "tps=%.1f ttft=%dms draft_accept=%.2f tokens=%d prompt_tps=%.1f backend=%s template=%s reqGpuLayers=%d threads=%d"
                                .format(tps, ttftMs, draftAcceptRate, totalTokens, promptEvalTps, actualBackend, effectiveTemplate.name, loadedGpuLayers, loadedThreadCount))
                        Log.d("PocketNode-Bench", "Full prompt sent to model:\n$fullPrompt")
                    }
                    viewModelScope.launch(Dispatchers.Main) {
                        this@ChatViewModel.backendName.value = actualBackend
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
                if (!stopRequested) {
                    withContext(Dispatchers.Main) {
                        lastSuccessfulInferenceAtMillis.value = System.currentTimeMillis()
                    }
                }
            } finally {
                if (imageEmbedPtr != 0L) inference.nativeFreeImageEmbed(imageEmbedPtr)
                if (clipCtxPtr != 0L) inference.nativeFreeMmproj(clipCtxPtr)
            }

            logInfo(
                "Chat generation[raw-output]: conversationId=$conversationId rawNativeOutputPreview=\"${preview(partialMessage)}\""
            )
            val finalAssistantOutput = sanitizeAssistantOutput(partialMessage, finalPass = true)

            // Final save to DB
            repository.updateMessage(
                ChatMessage(
                    id = assistantMsgId,
                    conversationId = conversationId,
                    role = "assistant",
                    content = finalAssistantOutput.visibleText
                )
            )
            logInfo(
                "Chat persistence[assistant]: conversationId=$conversationId finalAssistantPreview=\"${preview(finalAssistantOutput.visibleText)}\" savedVisible=true"
            )
        } catch (e: CancellationException) {
            logInfo(
                "Chat generation cancelled: stopRequested=$stopRequested conversationId=$conversationId"
            )
            throw e
        } catch (e: OutOfMemoryError) {
            withContext(Dispatchers.Main) {
                modelError.value = "Out of memory during generation — try reducing context size."
            }
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) {
                modelError.value = e.message ?: "Generation failed."
            }
        } finally {
            // NonCancellable ensures this cleanup block runs even if generationJob was cancelled
            // via stopGeneration(). Without it, withContext(Dispatchers.Main) would throw
            // CancellationException and generatingConversationId would never be cleared.
            withContext(NonCancellable + Dispatchers.IO) {
                if (stopRequested) {
                    val abortedAssistant = repository
                        .getMessagesSnapshot(conversationId)
                        .lastOrNull { it.role == "assistant" && it.content.isBlank() }
                    if (abortedAssistant != null) {
                        repository.deleteMessage(abortedAssistant.id)
                        logInfo(
                            "Stop recovery: removed empty assistant placeholder id=${abortedAssistant.id} conversationId=$conversationId"
                        )
                    } else {
                        logInfo("Stop recovery: no empty assistant placeholder found for conversationId=$conversationId")
                    }
                }
            }
            withContext(NonCancellable + Dispatchers.Main) {
                generatingConversationId = null
                currentAssistantMessage.value = ""
                isGenerating.value = false
                isStopping.value = false
                syncVisibleGenerationState()
            }
        }
    }

    private fun syncVisibleGenerationState() {
        val isVisibleConversation = generatingConversationId == activeConversationId
        visibleIsGenerating.value = isGenerating.value && isVisibleConversation
        visibleAssistantMessage.value = if (isVisibleConversation) currentAssistantMessage.value else ""
    }

    fun stopGeneration() {
        if (generationJob == null || stopRequested) return
        logInfo("Stop: Kotlin stop requested")
        stopRequested = true
        isStopping.value = true
        // Set the native abort flag first — before any coroutine cancellation —
        // so the in-flight nativeGenerate call sees the flag at its next check point.
        if (contextPtr != 0L) {
            inference.nativeStopGeneration(contextPtr)
            logInfo("Stop: nativeStopGeneration called")
        }
        // Reset streamed output immediately, but keep a visible stopping state
        // until coroutine cleanup completes.
        isGenerating.value = false
        visibleIsGenerating.value = false
        currentAssistantMessage.value = ""
        visibleAssistantMessage.value = ""
        syncVisibleGenerationState()
        // Cancel the coroutine so post-generation DB saves are skipped for aborted output.
        generationJob?.cancel()
        logInfo("Stop: UI entered stopping state")
    }

    fun dismissError() {
        modelError.value = null
    }

    private fun logPromptGrounding(
        rawUserPrompt: String,
        groundedTurn: GroundedTurnPrompt,
        fullPrompt: String
    ) {
        val rawTokenCount = safeTokenCount(rawUserPrompt)
        val fullPromptTokenCount = safeTokenCount(fullPrompt)
        runCatching {
            Log.i(
                "PromptGrounding",
                buildString {
                    append("Applied before native generation")
                    append(" raw_chars=").append(rawUserPrompt.length)
                    append(" raw_tokens=").append(rawTokenCount ?: "unavailable")
                    append(" grounded_chars=").append(groundedTurn.groundedContextPreview.length)
                    append(" full_prompt_chars=").append(fullPrompt.length)
                    append(" full_prompt_tokens=").append(fullPromptTokenCount ?: "unavailable")
                    append(" service_state_present=").append(groundedTurn.serviceStatePresent)
                    append(" sections=").append(groundedTurn.sectionFlags.joinToString(","))
                }
            )
        }
    }

    private fun safeTokenCount(text: String): Int? {
        if (modelPtr == 0L) return null
        return try {
            inference.nativeGetTokenCount(modelPtr, text)
        } catch (_: Throwable) {
            null
        }
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

    private fun buildKnowledgeBlock(chunks: List<KnowledgeChunk>): String {
        if (chunks.isEmpty()) return ""
        val sb = StringBuilder("<knowledge>\n")
        chunks.forEachIndexed { i, chunk ->
            if (i > 0) sb.append("---\n")
            sb.append("Source: ${chunk.documentTitle}\n")
            sb.append(chunk.text)
            sb.append("\n")
        }
        sb.append("</knowledge>")
        return sb.toString()
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
        isStopping.value = false
        closeFdIfNeeded()
    }
}
