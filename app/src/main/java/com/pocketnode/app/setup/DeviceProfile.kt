package com.pocketnode.app.setup

data class RecommendedProfile(
    val threads: Int,
    val gpuLayers: Int,
    val speculativeEnabled: Boolean,
    val templateName: String,
    val reasonCopy: String
)

sealed class FirstRunState {
    object Loading : FirstRunState()
    data class ModelFound(val modelPath: String, val profile: RecommendedProfile) : FirstRunState()
    data class ModelMissing(val profile: RecommendedProfile) : FirstRunState()
}
