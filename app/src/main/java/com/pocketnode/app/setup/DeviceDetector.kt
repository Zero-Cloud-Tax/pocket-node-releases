package com.pocketnode.app.setup

import android.os.Build

object DeviceDetector {
    fun detect(): RecommendedProfile {
        val cores = Runtime.getRuntime().availableProcessors()

        val isSamsungFold6 = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            && (Build.MODEL.startsWith("SM-F956")
                || Build.DEVICE.startsWith("q6q")
                || Build.HARDWARE.startsWith("qcom") && Build.MODEL.startsWith("SM-F9"))

        // Snapdragon 8 Gen 3 (SM8650 / platform codename "pineapple").
        // Primary signal: Build.SOC_MODEL (available API 31+, guaranteed on any device shipping
        // with this chip). Fallback: DEVICE codename, which OEMs sometimes expose.
        // Covers Galaxy S24 series, OnePlus 12, Xiaomi 14, and other SM8650 devices.
        val isSnapdragon8Gen3 = run {
            val bySocModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.equals("SM8650", ignoreCase = true)
            } else false
            val byDevice = Build.DEVICE.startsWith("pineapple", ignoreCase = true)
            bySocModel || byDevice
        }

        // Tensor G3 (Pixel 8, Pixel 8 Pro, Pixel 8a).
        // Primary signal: Build.SOC_MODEL == "Tensor G3" (API 31+).
        // Fallback: Google manufacturer + known device codenames (husky/shiba/akita).
        // NOTE: Vulkan/GPU offload via llama.cpp on Arm Mali is functional but receives
        // less upstream testing than Adreno. Conservative gpuLayers used accordingly.
        val isTensorG3 = run {
            val bySocModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.equals("Tensor G3", ignoreCase = true)
            } else false
            val byDevice = Build.MANUFACTURER.equals("Google", ignoreCase = true) &&
                (Build.DEVICE.equals("husky", ignoreCase = true) ||   // Pixel 8 Pro
                 Build.DEVICE.equals("shiba", ignoreCase = true) ||   // Pixel 8
                 Build.DEVICE.equals("akita", ignoreCase = true))     // Pixel 8a
            bySocModel || byDevice
        }

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
            isSnapdragon8Gen3 ->
                RecommendedProfile(
                    threads = 4,
                    gpuLayers = 20,
                    speculativeEnabled = false,
                    templateName = "ChatML",
                    reasonCopy = "Snapdragon 8 Gen 3 (Adreno 750): 20 GPU layers offloaded via " +
                        "Vulkan. Conservative default — increase if inference is stable."
                )
            isTensorG3 ->
                RecommendedProfile(
                    threads = 4,
                    gpuLayers = 10,
                    speculativeEnabled = false,
                    templateName = "ChatML",
                    reasonCopy = "Tensor G3 (Pixel 8 family): 10 GPU layers via Vulkan. " +
                        "Conservative default — Arm Mali Vulkan support in llama.cpp is " +
                        "functional but receives less upstream testing than Adreno."
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
