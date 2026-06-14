package com.pocketnode.app.diagnostics

data class DiagnosticMetrics(
    // JVM memory
    val jvmUsedMb: Float = 0f,
    val jvmMaxMb: Float = 0f,
    // Native memory
    val nativeAllocatedMb: Float = 0f,
    val nativeHeapSizeMb: Float = 0f,
    val nativeHeapFreeMb: Float = 0f,
    // PowerManager thermal (existing — do not rename)
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
    val isLoaded: Boolean = false,
    // B.3: OS thermal-zone data (null = zones not yet read or unavailable)
    val peakThermalZoneC: Double? = null,
    val peakThermalZoneType: String? = null,
    val peakCpuZoneC: Double? = null,
    val peakCpuZoneType: String? = null,
    val peakGpuZoneC: Double? = null,
    val peakGpuZoneType: String? = null,
    val thermalZoneReadableCount: Int = 0,
    val thermalZoneErrorCount: Int = 0,
    // Battery temperature from ACTION_BATTERY_CHANGED (null if unavailable)
    val batteryTemperatureC: Double? = null
)
