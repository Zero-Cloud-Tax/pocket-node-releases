package com.pocketnode.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.app.diagnostics.DiagnosticsViewModel
import com.pocketnode.app.diagnostics.ServiceHealthLog
import com.pocketnode.app.inference.InferenceStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    vm: DiagnosticsViewModel,
    lastInferenceStats: InferenceStats?,
    activeModelName: String?,
    backendName: String,
    activeModelPath: String?,
    verificationStatus: String?,
    isDraftModel: Boolean,
    isPrimaryModel: Boolean,
    lastInferenceAtMillis: Long?,
    modelLoaded: Boolean
) {
    val hardware by vm.hardware.collectAsState()
    val models   by vm.models.collectAsState()
    val serviceEvents by vm.serviceEvents.collectAsState()
    val settings = vm.settingsVm

    val threads           by settings.threadCount.collectAsState()
    val gpuLayers         by settings.gpuLayers.collectAsState()
    val speculativeEnabled by settings.speculativeEnabled.collectAsState()
    val templateName      by settings.templateName.collectAsState()
    val contextSize       by settings.contextSize.collectAsState()

    val activeModel = remember(models, activeModelName) {
        models.firstOrNull { it.name == activeModelName }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── A. Engine ──────────────────────────────────────────────────────────
        DiagCard("Engine") {
            val stats = lastInferenceStats
            if (stats != null) {
                DiagRow("TPS",   "%.1f tok/s".format(stats.tps))
                DiagRow("TTFT",  "${stats.ttftMs} ms")
                DiagRow("Total tokens", "${stats.totalTokens}")
            } else {
                DiagRow("Last inference stats", "Not available yet")
            }
            DiagRow("Backend",  backendName)
            DiagRow("Threads",  "$threads")
            DiagRow("GPU layers", "$gpuLayers")
            DiagRow("Template", templateName)
            DiagRow("Speculative", if (speculativeEnabled) "On" else "Off")
            if (speculativeEnabled && lastInferenceStats != null) {
                val s = lastInferenceStats
                val rate = if (s.nDrafted > 0) "%.0f%%".format(s.draftAcceptRate * 100) else "—"
                DiagRow("Draft accept rate", rate)
                DiagRow("Drafted / accepted", "${s.nDrafted} / ${s.nAccepted}")
            }
        }

        // ── B. Memory ──────────────────────────────────────────────────────────
        DiagCard("Memory") {
            if (hardware.isLoaded) {
                DiagRow("JVM used",  "%.1f MB".format(hardware.jvmUsedMb))
                DiagRow("JVM max",   "%.1f MB".format(hardware.jvmMaxMb))
                DiagRow("JVM fill",  "%.0f%%".format(hardware.jvmUsedMb / hardware.jvmMaxMb * 100))
                DiagRow("Native allocated", "%.1f MB".format(hardware.nativeAllocatedMb))
                DiagRow("Native heap size", "%.1f MB".format(hardware.nativeHeapSizeMb))
                DiagRow("Native heap free", "%.1f MB".format(hardware.nativeHeapFreeMb))
            } else {
                DiagRow("Memory", "Loading…")
            }
        }

        // ── C. Hardware ────────────────────────────────────────────────────────
        DiagCard("Hardware") {
            if (hardware.isLoaded) {
                DiagRow("Manufacturer", hardware.manufacturer)
                DiagRow("Model",    hardware.model)
                DiagRow("Device",   hardware.device)
                DiagRow("Hardware", hardware.hardware)
                DiagRow("Cores",    "${hardware.availableCores}")
                DiagRow("ABIs",     hardware.supportedAbis.joinToString(", "))
                DiagRow("Thermal",  hardware.thermalLabel,
                    valueColor = thermalColor(hardware.thermalStatus))
            } else {
                DiagRow("Hardware", "Loading…")
            }
        }

        // ── D. Model ───────────────────────────────────────────────────────────
        DiagCard("Model") {
            DiagRow("Active model", activeModelName ?: "None")
            DiagRow("Resolved file", activeModelPath ?: "Unavailable", mono = true)
            DiagRow("Loaded", if (modelLoaded) "true" else "false")
            DiagRow("Verification", verificationStatus ?: activeModel?.verificationStatus ?: "Unknown")
            DiagRow("Role", when {
                isDraftModel -> "Draft model"
                isPrimaryModel -> "Primary model"
                else -> "Unknown"
            })
            DiagRow("Last inference", formatDiagTime(lastInferenceAtMillis))
            if (activeModel != null) {
                val sizeMb = if (activeModel.sizeBytes > 0)
                    "%.0f MB".format(activeModel.sizeBytes / (1024f * 1024f))
                else "Unknown"
                DiagRow("Size", sizeMb)
                DiagRow("SHA-256",
                    activeModel.sha256?.take(16)?.plus("…") ?: "Not computed",
                    mono = true)
            } else if (activeModelName != null) {
                DiagRow("Metadata", "Not in database yet")
            }
        }

        // ── E. Context ─────────────────────────────────────────────────────────
        DiagCard("Context") {
            val stats = lastInferenceStats
            if (stats != null && stats.nCtx > 0) {
                val fillPct = stats.nPast.toFloat() / stats.nCtx * 100f
                DiagRow("Max context", "${stats.nCtx} tokens")
                DiagRow("Used", "${stats.nPast} tokens")
                DiagRow("Fill", "%.1f%%".format(fillPct))
                LinearProgressIndicator(
                    progress = { (stats.nPast.toFloat() / stats.nCtx).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            } else {
                DiagRow("Context", "No active context")
            }
        }

        // ── F. Service Health ──────────────────────────────────────────────────
        DiagCard("Service Health") {
            if (serviceEvents.isEmpty()) {
                DiagRow("Events", "None recorded this session")
            } else {
                serviceEvents.take(10).forEach { event ->
                    val time = vm.formatEventTime(event.timestampMs)
                    val label = event.type.name
                        .lowercase()
                        .replace('_', ' ')
                    val isBad = event.type == ServiceHealthLog.EventType.STOP_DRAIN_TIMEOUT ||
                        event.type == ServiceHealthLog.EventType.BOOT_START_DENIED ||
                        event.type == ServiceHealthLog.EventType.NATIVE_FREE_SKIPPED
                    DiagRow(
                        label = time,
                        value = if (event.detail.isNotEmpty()) "$label — ${event.detail}" else label,
                        valueColor = if (isBad) Color(0xFFF44336) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DiagCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            content()
        }
    }
}

@Composable
private fun DiagRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    mono: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = if (mono)
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            else
                MaterialTheme.typography.bodySmall,
            color = valueColor,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun thermalColor(status: Int): Color = when (status) {
    0, 1 -> Color(0xFF4CAF50) // green — None / Light
    2    -> Color(0xFFFFC107) // amber — Moderate
    else -> Color(0xFFF44336) // red   — Severe and above
}

private fun formatDiagTime(lastInferenceAtMillis: Long?): String {
    if (lastInferenceAtMillis == null) return "Not available yet"
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return formatter.format(Date(lastInferenceAtMillis))
}
