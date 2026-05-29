package com.pocketnode.app.inference

data class ModelMetadata(
    val architecture: String,
    val name: String,
    val tokenizerModel: String,
    val vocabSize: Int,
    val chatTemplate: String
)

enum class CompatibilityStatus {
    GOOD, BAD, UNKNOWN
}
