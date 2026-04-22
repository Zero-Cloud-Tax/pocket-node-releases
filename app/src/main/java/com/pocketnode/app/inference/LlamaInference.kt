package com.pocketnode.app.inference

interface LlamaCallback {
    fun onToken(token: String)
}

class LlamaInference {

    companion object {
        init {
            System.loadLibrary("pocketnode")
        }
    }

    external fun nativeLoadModel(modelPath: String, nGpuLayers: Int): Long
    external fun nativeFreeModel(modelPtr: Long)
    external fun nativeCreateContext(modelPtr: Long, contextSize: Int, nThreads: Int): Long
    external fun nativeFreeContext(ctxPtr: Long)
    external fun nativeGenerate(
        ctxPtr: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        callback: LlamaCallback
    )
    external fun nativeStopGeneration(ctxPtr: Long)
    external fun nativeClearCache(ctxPtr: Long)
    external fun nativeGetTokenCount(modelPtr: Long, text: String): Int
    external fun nativeGetContextLength(modelPtr: Long): Int
    external fun nativeGetEmbeddingSize(modelPtr: Long): Int
    external fun nativeGetVocabSize(modelPtr: Long): Int
    external fun nativeGetLastError(): String
    external fun nativeGetBackendName(): String
    external fun nativeCloseFd(fd: Int)
}
