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

class DiagnosticsViewModel(
    private val app: MainApplication,
    val settingsVm: SettingsViewModel,
    modelManager: ModelManager
) : ViewModel() {

    private val _hardware = MutableStateFlow(DiagnosticMetrics())
    val hardware: StateFlow<DiagnosticMetrics> = _hardware

    // Full model list — screen matches against active model name to surface metadata
    val models: StateFlow<List<LocalModel>> = modelManager.getModels()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            while (true) {
                _hardware.value = HardwareMetricsProvider.snapshot(app)
                delay(1000)
            }
        }
    }
}
