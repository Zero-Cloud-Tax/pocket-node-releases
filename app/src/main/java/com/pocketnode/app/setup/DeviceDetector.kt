package com.pocketnode.app.setup

import android.os.Build

object DeviceDetector {
    fun detect(): RecommendedProfile {
        val cores = Runtime.getRuntime().availableProcessors()

        val isSamsungFold6 = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            && (Build.MODEL.startsWith("SM-F956")
                || Build.DEVICE.startsWith("q6q")
                || Build.HARDWARE.startsWith("qcom") && Build.MODEL.startsWith("SM-F9"))

        return when {
            isSamsungFold6 ->
                RecommendedProfile(
                    threads = 4,
                    gpuLayers = 0,
                    speculativeEnabled = false,
                    templateName = "ChatML",
                    reasonCopy = "CPU-only reached 24.6 TPS on this device. " +
                        "Speculative decoding added overhead and was slower."
                )
            Build.SUPPORTED_ABIS.contains("arm64-v8a") ->
                RecommendedProfile(
                    threads = minOf(4, cores),
                    gpuLayers = 0,
                    speculativeEnabled = false,
                    templateName = "ChatML",
                    reasonCopy = "CPU-only is the most stable profile for ARM64 devices."
                )
            else ->
                RecommendedProfile(
                    threads = minOf(4, cores),
                    gpuLayers = 0,
                    speculativeEnabled = false,
                    templateName = "ChatML",
                    reasonCopy = "CPU-only recommended for first-run stability."
                )
        }
    }
}
