package com.pocketnode.app.inference

object BackendInfo {
    fun normalize(nativeBackendName: String?): String {
        val label = nativeBackendName?.trim().orEmpty()
        val hasVulkan = label.contains("Vulkan", ignoreCase = true)
        val hasOpenCl = label.contains("OpenCL", ignoreCase = true)
        val hasCpu = label.contains("CPU", ignoreCase = true)
        return when {
            hasVulkan && hasOpenCl -> "Vulkan,OpenCL"
            hasVulkan -> "Vulkan"
            hasOpenCl -> "OpenCL"
            hasCpu -> "CPU"
            label.isBlank() -> "Unknown"
            else -> label
        }
    }

    fun isVulkanFamily(backendName: String): Boolean =
        backendName.contains("Vulkan", ignoreCase = true)

    fun isAccelerated(backendName: String): Boolean =
        backendName.contains("Vulkan", ignoreCase = true) ||
            backendName.contains("OpenCL", ignoreCase = true)

    fun displayLabel(backendName: String?): String =
        normalize(backendName)
}
