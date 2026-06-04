package com.pocketnode.app.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.app.MainApplication
import com.pocketnode.app.data.ModelManager
import com.pocketnode.app.data.model.LocalModel
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

class DiagnosticsViewModel(
    private val app: MainApplication,
    val settingsVm: SettingsViewModel,
    modelManager: ModelManager
) : ViewModel() {

    private val _hardware = MutableStateFlow(DiagnosticMetrics())
    val hardware: StateFlow<DiagnosticMetrics> = _hardware

    val models: StateFlow<List<LocalModel>> = modelManager.getModels()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val serviceEvents: StateFlow<List<ServiceHealthLog.ServiceEvent>> =
        ServiceHealthLog.events.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    fun formatEventTime(timestampMs: Long): String = timeFormat.format(Date(timestampMs))

    init {
        viewModelScope.launch {
            while (true) {
                _hardware.value = HardwareMetricsProvider.snapshot(app)
                delay(1000)
            }
        }
    }
}
