package com.pocketnode.app.data

import com.pocketnode.app.BuildConfig

data class ModelDownloadSpec(
    val id: String,
    val displayName: String,
    val filename: String,
    val url: String,
    val expectedSha256: String? = null,
    val sizeBytes: Long? = null
)

// Null when POCKETNODE_OPERATOR_URL env var is not set — UI must handle null gracefully.
val OPERATOR_SPEC: ModelDownloadSpec? = BuildConfig.POCKETNODE_OPERATOR_URL
    .takeIf { it.isNotBlank() }
    ?.let {
        ModelDownloadSpec(
            id = "pocketnode_operator",
            displayName = "PocketNode Operator",
            filename = "PocketNode_Operator_Q4_0.gguf",
            url = it,
            expectedSha256 = "b1de55dff5815fc0dd898491295b064e7fea07368d603c82740288f8d3bb50ba",
            sizeBytes = 1_805_819_328L
        )
    }
