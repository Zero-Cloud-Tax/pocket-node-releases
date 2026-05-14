package com.pocketnode.app.data.model

enum class ModelRole {
    MAIN,       // default chat model
    DRAFT,      // speculative decoding draft
    VISION,     // multimodal projector
    EMBEDDING   // embedding-only model
}
