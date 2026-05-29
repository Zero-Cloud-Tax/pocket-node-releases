package com.pocketnode.app.diagnostics

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager

object HardwareMetricsProvider {

    fun snapshot(context: Context): DiagnosticMetrics {
        val rt = Runtime.getRuntime()
        val jvmUsed = (rt.totalMemory() - rt.freeMemory()).toFloat() / (1024 * 1024)
        val jvmMax  = rt.maxMemory().toFloat() / (1024 * 1024)
        val nativeAlloc = Debug.getNativeHeapAllocatedSize().toFloat() / (1024 * 1024)
        val nativeSize  = Debug.getNativeHeapSize().toFloat() / (1024 * 1024)
        val nativeFree  = Debug.getNativeHeapFreeSize().toFloat() / (1024 * 1024)

        val (thermalStatus, thermalLabel) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("NewApi")
            val s = pm.currentThermalStatus
            s to thermalLabel(s)
        } else {
            -1 to "API < 29"
        }

        return DiagnosticMetrics(
            jvmUsedMb         = jvmUsed,
            jvmMaxMb          = jvmMax,
            nativeAllocatedMb = nativeAlloc,
            nativeHeapSizeMb  = nativeSize,
            nativeHeapFreeMb  = nativeFree,
            thermalStatus     = thermalStatus,
            thermalLabel      = thermalLabel,
            manufacturer      = Build.MANUFACTURER,
            model             = Build.MODEL,
            device            = Build.DEVICE,
            hardware          = Build.HARDWARE,
            supportedAbis     = Build.SUPPORTED_ABIS.toList(),
            availableCores    = rt.availableProcessors(),
            isLoaded          = true
        )
    }

    // Integer literals map to PowerManager.THERMAL_STATUS_* constants (API 29+)
    private fun thermalLabel(status: Int): String = when (status) {
        0 -> "None"
        1 -> "Light"
        2 -> "Moderate"
        3 -> "Severe"
        4 -> "Critical"
        5 -> "Emergency"
        6 -> "Shutdown"
        else -> "Unknown ($status)"
    }
}
