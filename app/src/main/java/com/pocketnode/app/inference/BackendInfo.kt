package com.pocketnode.app.inference

object BackendInfo {
    fun normalize(nativeBackendName: String?): String {
        val label = nativeBackendName?.trim().orEmpty()
        return if (label.isBlank()) "CPU" else label
    }

    fun isVulkanFamily(backendName: String): Boolean =
        backendName.contains("Vulkan", ignoreCase = true)
}
