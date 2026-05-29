package com.pocketnode.app.inference

interface LlamaCallback {
    fun onToken(token: String)
    fun onStats(
        tps: Float,
        ttftMs: Long,
        draftAcceptRate: Float,   // 0.0f if non-speculative
        totalTokens: Int,
        promptEvalTps: Float,
        backendName: String,
        nDrafted: Int,            // total tokens proposed by draft model (0 if non-speculative)
        nAccepted: Int            // total draft tokens accepted by main model
    )
}

class LlamaInference {

    companion object {
        init {
            System.loadLibrary("pocketnode")
        }
    }

    // ── Main model ──────────────────────────────────────────────────────────
    external fun nativeLoadModel(modelPath: String, nGpuLayers: Int): Long
    external fun nativeFreeModel(modelPtr: Long)
    external fun nativeCreateContext(modelPtr: Long, contextSize: Int, nThreads: Int): Long
    external fun nativeFreeContext(ctxPtr: Long)

    // ── Draft model (speculative decoding) ──────────────────────────────────
    external fun nativeLoadDraftModel(modelPath: String, nGpuLayers: Int): Long
    external fun nativeFreeDraftModel(draftModelPtr: Long)
    external fun nativeCreateDraftContext(draftModelPtr: Long, contextSize: Int, nThreads: Int): Long
    external fun nativeFreeDraftContext(draftCtxPtr: Long)

    // ── Generation ──────────────────────────────────────────────────────────
    external fun nativeGenerate(
        ctxPtr: Long,
        prompt: String,
        imageEmbedPtr: Long,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        draftCtxPtr: Long,   // 0 = no speculative decoding
        nDraft: Int,         // tokens to draft per step
        batchSize: Int,      // verification batch size
        ubatchSize: Int,     // micro-batch size (controls TTFT)
        callback: LlamaCallback
    )
    external fun nativeStopGeneration(ctxPtr: Long)
    external fun nativeClearCache(ctxPtr: Long)

    // ── Model info ──────────────────────────────────────────────────────────
    external fun nativeGetTokenCount(modelPtr: Long, text: String): Int
    external fun nativeGetContextLength(modelPtr: Long): Int
    external fun nativeGetEmbeddingSize(modelPtr: Long): Int
    external fun nativeGetVocabSize(modelPtr: Long): Int
    external fun nativeGetLastError(): String
    external fun nativeGetBackendName(): String
    external fun nativeGetModelMetadata(contextPtr: Long): Array<String>?
    external fun nativeCloseFd(fd: Int)

    // ── Multi-Modal ─────────────────────────────────────────────────────────
    external fun nativeLoadMmproj(mmprojPath: String): Long
    external fun nativeFreeMmproj(ctxPtr: Long)
    external fun nativeMakeImageEmbed(ctxPtr: Long, imageBytes: ByteArray): Long
    external fun nativeFreeImageEmbed(embedPtr: Long)
}
