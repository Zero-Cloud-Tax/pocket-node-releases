package com.pocketnode.app.ui.screens

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.app.inference.PromptTemplate
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private object Keys {
    val TEMPERATURE = floatPreferencesKey("temperature")
    val TOP_P = floatPreferencesKey("top_p")
    val TOP_K = intPreferencesKey("top_k")
    val MAX_TOKENS = intPreferencesKey("max_tokens")
    val CONTEXT_SIZE = intPreferencesKey("context_size")
    val THREAD_COUNT = intPreferencesKey("thread_count")
    val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
    val TEMPLATE_NAME = stringPreferencesKey("template_name")
    val EDGE_API_ENABLED = booleanPreferencesKey("edge_api_enabled")
    val GPU_LAYERS = intPreferencesKey("gpu_layers")
    val API_KEY = stringPreferencesKey("api_key")
    // Speculative decoding
    val SPECULATIVE_ENABLED     = booleanPreferencesKey("speculative_enabled")
    val DRAFT_MODEL_ID          = stringPreferencesKey("draft_model_id")
    val SPECULATIVE_DRAFT_COUNT = intPreferencesKey("speculative_draft_count")
    val BATCH_SIZE              = intPreferencesKey("batch_size")
    val UBATCH_SIZE             = intPreferencesKey("ubatch_size")
    val BENCHMARK_MODE          = booleanPreferencesKey("benchmark_mode")
}

class SettingsViewModel(private val dataStore: DataStore<Preferences>) : ViewModel() {

    private val defaultThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    private val _prefs = dataStore.data.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyPreferences()
    )

    val temperature: StateFlow<Float> = _prefs.map { it[Keys.TEMPERATURE] ?: 0.7f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.7f)

    val topP: StateFlow<Float> = _prefs.map { it[Keys.TOP_P] ?: 0.9f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.9f)

    val topK: StateFlow<Int> = _prefs.map { it[Keys.TOP_K] ?: 40 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 40)

    val maxTokens: StateFlow<Int> = _prefs.map { it[Keys.MAX_TOKENS] ?: 512 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 512)

    val contextSize: StateFlow<Int> = _prefs.map { it[Keys.CONTEXT_SIZE] ?: 4096 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 4096)

    val threadCount: StateFlow<Int> = _prefs.map { it[Keys.THREAD_COUNT] ?: defaultThreads }
        .stateIn(viewModelScope, SharingStarted.Eagerly, defaultThreads)

    val systemPrompt: StateFlow<String> = _prefs.map { it[Keys.SYSTEM_PROMPT] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val templateName: StateFlow<String> = _prefs.map { it[Keys.TEMPLATE_NAME] ?: "ChatML" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "ChatML")

    val edgeApiEnabled: StateFlow<Boolean> = _prefs.map { it[Keys.EDGE_API_ENABLED] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val gpuLayers: StateFlow<Int> = _prefs.map { it[Keys.GPU_LAYERS] ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val apiKey: StateFlow<String> = _prefs.map { it[Keys.API_KEY] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // Speculative decoding
    val speculativeEnabled: StateFlow<Boolean> = _prefs.map { it[Keys.SPECULATIVE_ENABLED] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val draftModelId: StateFlow<String> = _prefs.map { it[Keys.DRAFT_MODEL_ID] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val speculativeDraftCount: StateFlow<Int> = _prefs.map { it[Keys.SPECULATIVE_DRAFT_COUNT] ?: 5 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 5)

    val batchSize: StateFlow<Int> = _prefs.map { it[Keys.BATCH_SIZE] ?: 512 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 512)

    val ubatchSize: StateFlow<Int> = _prefs.map { it[Keys.UBATCH_SIZE] ?: 128 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 128)

    val benchmarkMode: StateFlow<Boolean> = _prefs.map { it[Keys.BENCHMARK_MODE] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val selectedTemplate: StateFlow<PromptTemplate> = templateName.map { name ->
        when (name) {
            "Llama3" -> PromptTemplate.Llama3
            "Alpaca" -> PromptTemplate.Alpaca
            else -> PromptTemplate.ChatML
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PromptTemplate.ChatML)

    fun setTemperature(v: Float) = save { it[Keys.TEMPERATURE] = v }
    fun setTopP(v: Float) = save { it[Keys.TOP_P] = v }
    fun setTopK(v: Int) = save { it[Keys.TOP_K] = v }
    fun setMaxTokens(v: Int) = save { it[Keys.MAX_TOKENS] = v }
    fun setContextSize(v: Int) = save { it[Keys.CONTEXT_SIZE] = v }
    fun setThreadCount(v: Int) = save { it[Keys.THREAD_COUNT] = v }
    fun setSystemPrompt(v: String) = save { it[Keys.SYSTEM_PROMPT] = v }
    fun setTemplateName(v: String) = save { it[Keys.TEMPLATE_NAME] = v }
    fun setEdgeApiEnabled(v: Boolean) = save { it[Keys.EDGE_API_ENABLED] = v }
    fun setGpuLayers(v: Int) = save { it[Keys.GPU_LAYERS] = v.coerceAtLeast(0) }
    fun setApiKey(v: String) = save { it[Keys.API_KEY] = v }
    fun setSpeculativeEnabled(v: Boolean) = save { it[Keys.SPECULATIVE_ENABLED] = v }
    fun setDraftModelId(v: String) = save { it[Keys.DRAFT_MODEL_ID] = v }
    fun setSpeculativeDraftCount(v: Int) = save { it[Keys.SPECULATIVE_DRAFT_COUNT] = v.coerceIn(1, 16) }
    fun setBatchSize(v: Int) = save { it[Keys.BATCH_SIZE] = v.coerceIn(128, 1024) }
    fun setUbatchSize(v: Int) = save { it[Keys.UBATCH_SIZE] = v.coerceIn(32, 256) }
    fun setBenchmarkMode(v: Boolean) = save { it[Keys.BENCHMARK_MODE] = v }

    private fun save(block: (MutablePreferences) -> Unit) {
        viewModelScope.launch { dataStore.edit(block) }
    }
}
