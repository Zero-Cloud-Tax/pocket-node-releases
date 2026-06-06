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
        nAccepted: Int,           // total draft tokens accepted by main model
        nCtx: Int,                // runtime context size (n_ctx set at context creation)
        nPast: Int                // tokens currently occupying the KV cache
    )
}

interface InferenceEngine {
    fun nativeLoadModel(modelPath: String, nGpuLayers: Int): Long
    fun nativeFreeModel(modelPtr: Long)
    fun nativeCreateContext(modelPtr: Long, contextSize: Int, nThreads: Int): Long
    fun nativeFreeContext(ctxPtr: Long)

    fun nativeLoadDraftModel(modelPath: String, nGpuLayers: Int): Long
    fun nativeFreeDraftModel(draftModelPtr: Long)
    fun nativeCreateDraftContext(draftModelPtr: Long, contextSize: Int, nThreads: Int): Long
    fun nativeFreeDraftContext(draftCtxPtr: Long)

    fun nativeGenerate(
        ctxPtr: Long,
        prompt: String,
        imageEmbedPtr: Long,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        draftCtxPtr: Long,
        nDraft: Int,
        batchSize: Int,
        ubatchSize: Int,
        callback: LlamaCallback
    )
    fun nativeStopGeneration(ctxPtr: Long)
    fun nativeClearCache(ctxPtr: Long)

    fun nativeGetTokenCount(modelPtr: Long, text: String): Int
    fun nativeGetContextLength(modelPtr: Long): Int
    fun nativeGetEmbeddingSize(modelPtr: Long): Int
    fun nativeGetVocabSize(modelPtr: Long): Int
    fun nativeGetLastError(): String
    fun nativeGetBackendName(): String
    fun nativeGetModelMetadata(contextPtr: Long): Array<String>?
    fun nativeCloseFd(fd: Int)

    fun nativeLoadMmproj(mmprojPath: String): Long
    fun nativeFreeMmproj(ctxPtr: Long)
    fun nativeMakeImageEmbed(ctxPtr: Long, imageBytes: ByteArray): Long
    fun nativeFreeImageEmbed(embedPtr: Long)
}

class LlamaInference : InferenceEngine {

    companion object {
        init {
            System.loadLibrary("pocketnode")
        }
    }

    // ── Main model ──────────────────────────────────────────────────────────
    override external fun nativeLoadModel(modelPath: String, nGpuLayers: Int): Long
    override external fun nativeFreeModel(modelPtr: Long)
    override external fun nativeCreateContext(modelPtr: Long, contextSize: Int, nThreads: Int): Long
    override external fun nativeFreeContext(ctxPtr: Long)

    // ── Draft model (speculative decoding) ──────────────────────────────────
    override external fun nativeLoadDraftModel(modelPath: String, nGpuLayers: Int): Long
    override external fun nativeFreeDraftModel(draftModelPtr: Long)
    override external fun nativeCreateDraftContext(draftModelPtr: Long, contextSize: Int, nThreads: Int): Long
    override external fun nativeFreeDraftContext(draftCtxPtr: Long)

    // ── Generation ──────────────────────────────────────────────────────────
    override external fun nativeGenerate(
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
    override external fun nativeStopGeneration(ctxPtr: Long)
    override external fun nativeClearCache(ctxPtr: Long)

    // ── Model info ──────────────────────────────────────────────────────────
    override external fun nativeGetTokenCount(modelPtr: Long, text: String): Int
    override external fun nativeGetContextLength(modelPtr: Long): Int
    override external fun nativeGetEmbeddingSize(modelPtr: Long): Int
    override external fun nativeGetVocabSize(modelPtr: Long): Int
    override external fun nativeGetLastError(): String
    override external fun nativeGetBackendName(): String
    override external fun nativeGetModelMetadata(contextPtr: Long): Array<String>?
    override external fun nativeCloseFd(fd: Int)

    // ── Multi-Modal ─────────────────────────────────────────────────────────
    override external fun nativeLoadMmproj(mmprojPath: String): Long
    override external fun nativeFreeMmproj(ctxPtr: Long)
    override external fun nativeMakeImageEmbed(ctxPtr: Long, imageBytes: ByteArray): Long
    override external fun nativeFreeImageEmbed(embedPtr: Long)
}
