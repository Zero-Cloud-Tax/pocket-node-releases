package com.pocketnode.app.diagnostics

data class DiagnosticMetrics(
    // JVM memory
    val jvmUsedMb: Float = 0f,
    val jvmMaxMb: Float = 0f,
    // Native memory
    val nativeAllocatedMb: Float = 0f,
    val nativeHeapSizeMb: Float = 0f,
    val nativeHeapFreeMb: Float = 0f,
    // Thermal
    val thermalStatus: Int = -1,
    val thermalLabel: String = "Unknown",
    // Device
    val manufacturer: String = "",
    val model: String = "",
    val device: String = "",
    val hardware: String = "",
    val supportedAbis: List<String> = emptyList(),
    val availableCores: Int = 0,
    // True once the first snapshot has been collected
    val isLoaded: Boolean = false
)
