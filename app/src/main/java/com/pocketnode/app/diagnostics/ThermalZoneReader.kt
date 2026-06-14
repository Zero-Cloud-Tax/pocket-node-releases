package com.pocketnode.app.diagnostics

import java.io.File

/**
 * Entry for a single /sys/class/thermal/thermal_zone<N> zone.
 * readError is non-null if this zone could not be read.
 */
data class ThermalZoneEntry(
    val index: Int,
    val path: String,
    val type: String,
    val rawTempMdegC: Long,
    val tempC: Double,
    val isCpuZone: Boolean,
    val isGpuZone: Boolean,
    val readError: String?
)

/**
 * Aggregate snapshot of all thermal zones on the device.
 * Peak values are null if no readable zones of that category exist.
 * availabilityError is non-null if /sys/class/thermal is entirely inaccessible.
 */
data class ThermalZoneSnapshot(
    val zones: List<ThermalZoneEntry>,
    val readableCount: Int,
    val errorCount: Int,
    val peakC: Double?,
    val peakType: String?,
    val peakCpuC: Double?,
    val peakCpuType: String?,
    val peakGpuC: Double?,
    val peakGpuType: String?,
    val availabilityError: String?
)

/**
 * Reads OS-level thermal zone temperatures from /sys/class/thermal/thermal_zoneN/.
 *
 * Zone classification is DYNAMIC based on each zone's type string.
 * Device hints (NOT hard requirements):
 *   Fold 6 / Snapdragon 8 Gen 3:
 *     zone10 (cpu-2-2-1) peaked at 64.1C in B.2 thermal event
 *     zone32 (gpuss-0)   peaked at 53.6C in B.2 thermal event
 * Discovery always scans all zones dynamically regardless of device.
 *
 * Safe contract:
 *   - Never throws
 *   - Silently skips zones that cannot be read
 *   - Returns availabilityError if /sys/class/thermal itself is inaccessible
 *   - All temperature values are in degrees Celsius (kernel reports millidegrees)
 */
object ThermalZoneReader {

    private const val THERMAL_BASE = "/sys/class/thermal"

    // CPU zone type keywords -- case-insensitive substring match against the zone's type file
    private val CPU_TYPE_KEYWORDS = listOf(
        "cpu", "big", "little", "prime", "cluster", "gold", "silver", "soc", "core", "cpuss"
    )

    // GPU zone type keywords
    private val GPU_TYPE_KEYWORDS = listOf("gpu", "gpuss", "adreno", "graphics")

    /** Reads all thermal zones. Never throws. */
    fun readSnapshot(): ThermalZoneSnapshot {
        val baseDir = File(THERMAL_BASE)

        if (!baseDir.exists() || !baseDir.isDirectory) {
            return ThermalZoneSnapshot(
                zones = emptyList(), readableCount = 0, errorCount = 0,
                peakC = null, peakType = null,
                peakCpuC = null, peakCpuType = null,
                peakGpuC = null, peakGpuType = null,
                availabilityError = "thermal_base_unavailable: $THERMAL_BASE"
            )
        }

        val zoneDirs = try {
            baseDir.listFiles { f ->
                f.isDirectory && f.name.startsWith("thermal_zone")
            }?.sortedBy { extractZoneIndex(it.name) } ?: emptyList()
        } catch (e: Exception) {
            return ThermalZoneSnapshot(
                zones = emptyList(), readableCount = 0, errorCount = 0,
                peakC = null, peakType = null,
                peakCpuC = null, peakCpuType = null,
                peakGpuC = null, peakGpuType = null,
                availabilityError = "zone_list_error: ${e.message?.take(100)}"
            )
        }

        val entries = mutableListOf<ThermalZoneEntry>()

        for (dir in zoneDirs) {
            val index = extractZoneIndex(dir.name)
            val typeName = readSysFile(File(dir, "type"))
            val rawTempStr = readSysFile(File(dir, "temp"))

            if (typeName == null && rawTempStr == null) {
                entries += ThermalZoneEntry(
                    index = index, path = dir.absolutePath,
                    type = "unknown", rawTempMdegC = 0L, tempC = 0.0,
                    isCpuZone = false, isGpuZone = false,
                    readError = "both_type_and_temp_unreadable"
                )
                continue
            }

            if (rawTempStr == null) {
                entries += ThermalZoneEntry(
                    index = index, path = dir.absolutePath,
                    type = typeName ?: "unknown", rawTempMdegC = 0L, tempC = 0.0,
                    isCpuZone = false, isGpuZone = false,
                    readError = "temp_unreadable"
                )
                continue
            }

            val rawTemp = rawTempStr.trim().toLongOrNull()
            if (rawTemp == null) {
                entries += ThermalZoneEntry(
                    index = index, path = dir.absolutePath,
                    type = typeName ?: "unknown", rawTempMdegC = 0L, tempC = 0.0,
                    isCpuZone = false, isGpuZone = false,
                    readError = "temp_parse_failed: '${rawTempStr.take(20)}'"
                )
                continue
            }

            // Kernel reports millidegrees C.
            val tempC = when {
                rawTemp in 1L..150_000L -> rawTemp / 1000.0
                rawTemp == 0L -> 0.0
                else -> rawTemp / 1000.0
            }

            val typeLower = (typeName ?: "").lowercase()
            entries += ThermalZoneEntry(
                index = index, path = dir.absolutePath,
                type = typeName ?: "unknown", rawTempMdegC = rawTemp, tempC = tempC,
                isCpuZone = CPU_TYPE_KEYWORDS.any { typeLower.contains(it) },
                isGpuZone = GPU_TYPE_KEYWORDS.any { typeLower.contains(it) },
                readError = null
            )
        }

        val readable = entries.filter { it.readError == null }
        val errored  = entries.filter { it.readError != null }

        val peak    = readable.maxByOrNull { it.tempC }
        val peakCpu = readable.filter { it.isCpuZone }.maxByOrNull { it.tempC }
        val peakGpu = readable.filter { it.isGpuZone }.maxByOrNull { it.tempC }

        return ThermalZoneSnapshot(
            zones            = entries,
            readableCount    = readable.size,
            errorCount       = errored.size,
            peakC            = peak?.tempC,
            peakType         = peak?.type,
            peakCpuC         = peakCpu?.tempC,
            peakCpuType      = peakCpu?.type,
            peakGpuC         = peakGpu?.tempC,
            peakGpuType      = peakGpu?.type,
            availabilityError = null
        )
    }

    private fun readSysFile(file: File): String? = try {
        if (!file.exists() || !file.canRead()) null
        else file.readText().trim().ifEmpty { null }
    } catch (ignored: Exception) { null }

    private fun extractZoneIndex(name: String): Int =
        name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE
}
