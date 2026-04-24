package com.pocketnode.app.data.model

data class RemoteModel(
    val name: String,
    val description: String,
    val huggingFaceUrl: String,
    val size: String
)

val RECOMMENDED_MODELS = listOf(
    RemoteModel(
        "Llama 3.2 3B",
        "Strong instruction following, great for general chat.",
        "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        "2.0 GB"
    ),
    RemoteModel(
        "Phi-3.5 Mini",
        "Excellent reasoning in a small package.",
        "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
        "2.2 GB"
    ),
    RemoteModel(
        "Qwen 2.5 1.5B",
        "Very fast, good for simple logic.",
        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
        "1.0 GB"
    ),
    RemoteModel(
        "LLaVA 1.5 7B (Vision)",
        "Vision model. Understands images when you attach them.",
        "https://huggingface.co/mys/ggml_llava-v1.5-7b/resolve/main/ggml-model-q4_k.gguf",
        "4.0 GB"
    ),
    RemoteModel(
        "LLaVA 1.5 Projector",
        "Required companion for LLaVA. Download this to process images.",
        "https://huggingface.co/mys/ggml_llava-v1.5-7b/resolve/main/mmproj-model-f16.gguf",
        "600 MB"
    )
)
