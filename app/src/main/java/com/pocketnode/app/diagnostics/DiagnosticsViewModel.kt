package com.pocketnode.app.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.app.MainApplication
import com.pocketnode.app.data.ModelManager
import com.pocketnode.app.data.model.LocalModel
import com.pocketnode.app.inference.ApiServer
import com.pocketnode.app.ui.screens.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only, operator-facing summary of the Edge API's serving state — derived
 * from the same sources ApiServer/GenerationService already use internally
 * (ApiServer.currentEligibility/currentStatusSummary, MainApplication.activeSession,
 * and the most recent ServiceHealthLog event), not a new source of truth.
 * P29 RC3.3.
 */
data class ServiceSnapshot(
    val serverAlive: Boolean = false,
    val modelLoaded: Boolean = false,
    val activeSessionModelName: String? = null,
    val eligible: Boolean = false,
    val ineligibleReason: String? = null,
    val uptimeMs: Long = 0L,
    val lastInferenceAt: String? = null,
    val lastError: String? = null,
    val mostRecentEvent: ServiceHealthLog.EventType? = null
) {
    /** Coarse label mirroring GenerationService's own ServingState, without
     * depending on that (private, instance-bound) enum. */
    val stateLabel: String get() = when {
        mostRecentEvent == ServiceHealthLog.EventType.SERVICE_STOPPED ||
            mostRecentEvent == ServiceHealthLog.EventType.STOP_ACTION_RECEIVED -> "Stopped"
        !serverAlive -> "Stopped"
        !modelLoaded -> "Loading model…"
        eligible -> "Serving"
        else -> "Unavailable" + (ineligibleReason?.let { ": $it" } ?: "")
    }
}

class DiagnosticsViewModel(
    private val app: MainApplication,
    val settingsVm: SettingsViewModel,
    modelManager: ModelManager
) : ViewModel() {

    private val _hardware = MutableStateFlow(DiagnosticMetrics())
    val hardware: StateFlow<DiagnosticMetrics> = _hardware

    private val _serviceSnapshot = MutableStateFlow(ServiceSnapshot())
    val serviceSnapshot: StateFlow<ServiceSnapshot> = _serviceSnapshot

    val models: StateFlow<List<LocalModel>> = modelManager.getModels()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val serviceEvents: StateFlow<List<ServiceHealthLog.ServiceEvent>> =
        ServiceHealthLog.events.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    fun formatEventTime(timestampMs: Long): String = timeFormat.format(Date(timestampMs))

    /** Re-reads the same live sources the automatic 1s refresh already polls.
     * Exposed for an explicit operator-triggered "Refresh now" action. */
    fun refreshNow() {
        _hardware.value = HardwareMetricsProvider.snapshot(app)
        _serviceSnapshot.value = buildServiceSnapshot()
    }

    private fun buildServiceSnapshot(): ServiceSnapshot {
        val (eligible, reason) = ApiServer.currentEligibility(app)
        val status = ApiServer.currentStatusSummary()
        val session = app.activeSession
        return ServiceSnapshot(
            serverAlive = status.serverAlive,
            modelLoaded = session != null,
            activeSessionModelName = session?.modelName,
            eligible = eligible,
            ineligibleReason = reason,
            uptimeMs = status.uptimeMs,
            lastInferenceAt = status.lastInferenceAt,
            lastError = status.lastError,
            mostRecentEvent = serviceEvents.value.firstOrNull()?.type
        )
    }

    init {
        viewModelScope.launch {
            while (true) {
                _hardware.value = HardwareMetricsProvider.snapshot(app)
                _serviceSnapshot.value = buildServiceSnapshot()
                delay(1000)
            }
        }
    }
}
