package com.pocketnode.app.setup

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.app.MainApplication
import com.pocketnode.app.ui.screens.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FirstRunViewModel(
    private val app: MainApplication,
    private val settingsVm: SettingsViewModel
) : ViewModel() {

    private val _state = mutableStateOf<FirstRunState>(FirstRunState.Loading)
    val state: State<FirstRunState> = _state

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = DeviceDetector.detect()
            val modelFile = findBestModel()
            _state.value = if (modelFile != null)
                FirstRunState.ModelFound(modelFile.absolutePath, profile)
            else
                FirstRunState.ModelMissing(profile)
        }
    }

    fun scanEnvironment() = rescan()

    fun resetToMissing() {
        val profile = DeviceDetector.detect()
        _state.value = FirstRunState.ModelMissing(profile)
    }

    fun rescan() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = FirstRunState.Loading
            val profile = DeviceDetector.detect()
            val modelFile = findBestModel()
            _state.value = if (modelFile != null)
                FirstRunState.ModelFound(modelFile.absolutePath, profile)
            else
                FirstRunState.ModelMissing(profile)
        }
    }

    private fun findBestModel(): File? {
        val modelDir = File(app.getExternalFilesDir(null), "models")
        val files = modelDir.listFiles { f -> f.extension.equals("gguf", ignoreCase = true) }
            ?: return null
        // Prefer the operator model; fall back to any installed GGUF
        return files.firstOrNull { it.name.startsWith("PocketNode_Operator") }
            ?: files.firstOrNull()
    }

    suspend fun applyRecommendedProfile(profile: RecommendedProfile) {
        withContext(Dispatchers.Main) {
            settingsVm.setThreadCount(profile.threads)
            settingsVm.setGpuLayers(profile.gpuLayers)
            settingsVm.setSpeculativeEnabled(profile.speculativeEnabled)
            settingsVm.setTemplateName(profile.templateName)
            settingsVm.setBenchmarkMode(false)
            settingsVm.setFirstRunComplete(true)
        }
        // Await DataStore flush before returning so caller can navigate safely
        settingsVm.firstRunComplete.first { it == true }
    }
}
