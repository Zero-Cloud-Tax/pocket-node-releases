package com.pocketnode.app.inference

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.pocketnode.app.diagnostics.ThermalZoneReader
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketnode.app.MainApplication
import com.pocketnode.app.diagnostics.ServiceHealthLog
import com.pocketnode.app.diagnostics.ServiceHealthLog.EventType
import com.pocketnode.app.ui.screens.settingsDataStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class GenerateRequest(
    val prompt: String,
    val max_tokens: Int = 512,
    val temperature: Float = 0.7f,
    val top_p: Float = 0.9f,
    val top_k: Int = 40,
    val repeat_penalty: Float = 1.1f
)

@Serializable
data class ChatMessageRequest(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val messages: List<ChatMessageRequest>,
    val max_tokens: Int = 512,
    val temperature: Float = 0.7f,
    val top_p: Float = 0.9f,
    val top_k: Int = 40,
    val repeat_penalty: Float = 1.1f
)

@Serializable
private data class TokenChunk(val token: String)

@Serializable
private data class DoneChunk(val done: Boolean = true)

@Serializable
private data class ErrorChunk(val error: String)

@Serializable
private data class OaiChatRequest(
    val model: String? = null,
    val messages: List<ChatMessageRequest>,
    val stream: Boolean? = null,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null
)

@Serializable
private data class OaiDelta(val content: String? = null)

@Serializable
private data class OaiChoice(
    val index: Int,
    val delta: OaiDelta,
    val finish_reason: String? = null
)

@Serializable
private data class CapabilitiesResponse(
    // ── Existing fields (DO NOT rename or remove — backward compat) ───────────
    val node: String,
    val role: String,
    val service_alive: Boolean,
    val model_loaded: Boolean,
    val battery_percent: Int,
    val charging: Boolean,
    val thermal_status: String,
    val foreground_service: Boolean,
    val eligible_for_inference: Boolean,
    val reason_if_not_eligible: String?,
    val last_inference_at: String?,
    val last_error: String?,
    // ── B.3: OS thermal-zone telemetry fields ─────────────────────────────────
    // Hottest zone across all readable /sys/class/thermal/thermal_zone* zones
    val peak_thermal_zone_c: Double?,
    val peak_thermal_zone_type: String?,
    // Hottest CPU-classified zone (type contains cpu/cluster/gold/silver/etc.)
    val peak_cpu_zone_c: Double?,
    val peak_cpu_zone_type: String?,
    // Hottest GPU-classified zone (type contains gpu/gpuss/adreno/etc.)
    val peak_gpu_zone_c: Double?,
    val peak_gpu_zone_type: String?,
    // How many zones were successfully read vs. had errors
    val thermal_zone_readable_count: Int,
    val thermal_zone_error_count: Int,
    // Non-null if the OS-zone gate is the reason inference is blocked/warned
    val thermal_zone_gate_reason: String?,
    // Battery junction temperature from ACTION_BATTERY_CHANGED (null if unavailable)
    val battery_temperature_c: Double?
)

@Serializable
private data class NodeUnavailableResponse(
    val error: String,
    val reason: String,
    val fallback_recommended: Boolean
)

@Serializable
private data class OaiChunk(
    val id: String,
    @SerialName("object") val obj: String,
    val created: Long,
    val model: String,
    val choices: List<OaiChoice>
)

// Non-streaming ("chat.completion") response types
@Serializable
private data class OaiNonStreamMessage(val role: String, val content: String)

@Serializable
private data class OaiNonStreamChoice(
    val index: Int,
    val message: OaiNonStreamMessage,
    val finish_reason: String
)

@Serializable
private data class OaiChatCompletion(
    val id: String,
    @SerialName("object") val obj: String,
    val created: Long,
    val model: String,
    val choices: List<OaiNonStreamChoice>
)

object ApiServer {

    // How long stop() will wait for an in-flight nativeGenerate to finish before
    // giving up. On timeout the caller skips nativeFreeContext (bounded leak).
    // Increase if you observe frequent STOP_DRAIN_TIMEOUT events during normal use.
    private const val STOP_DRAIN_TIMEOUT_MS = 5_000L

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val inferenceMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var serverStartTime = 0L
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cachedApiKey = MutableStateFlow("")
    private var keyCollectorJob: Job? = null

    // ── B.3: OS thermal-zone gate thresholds ──────────────────────────────────
    // Empirical defaults from B.2 benchmarking on Samsung Z Fold 6 / Snapdragon 8 Gen 3.
    // NOT validated hardware safety limits — adjust with per-device profiling.
    private const val THERMAL_ZONE_WARN_C           = 55.0  // telemetry only; inference not blocked
    private const val THERMAL_ZONE_SOFT_BLOCK_CPU_C = 60.0  // block if peak CPU zone >= 60°C
    private const val THERMAL_ZONE_SOFT_BLOCK_GPU_C = 60.0  // block if peak GPU zone >= 60°C
    private const val THERMAL_ZONE_HARD_BLOCK_C     = 65.0  // hard block if ANY zone >= 65°C
    private const val THERMAL_ZONE_COOLDOWN_C       = 58.0  // hard block lifts only when ALL zones < 58°C

    @Volatile private var isStarted = false
    @Volatile private var isStopping = false
    // Hysteresis flag: true when a hard-block threshold was crossed; clears when all zones cool below COOLDOWN_C
    @Volatile private var thermalZoneHardBlocked = false

    // Set to true when a stop() drain times out. Never reset — the process holds
    // leaked native allocations and must not attempt to restart the server.
    @Volatile private var contaminated = false
    @Volatile private var lastInferenceAt: String? = null
    @Volatile private var lastInferenceError: String? = null
    @Volatile private var debugForceBlock = false

    /**
     * True when a previous stop() call timed out draining inference.
     * The process may hold leaked native allocations; no further start
     * attempts should be made until the process is fully recycled.
     * Checked by MainActivity before calling startForegroundService.
     */
    val isContaminated: Boolean get() = contaminated

    fun start(app: MainApplication, port: Int = 11434) {
        if (isStarted || isStopping) return

        keyCollectorJob?.cancel()
        keyCollectorJob = serverScope.launch {
            app.settingsDataStore.data
                .map { it[stringPreferencesKey("api_key")] ?: "" }
                .collect { key -> cachedApiKey.update { key } }
        }

        server = embeddedServer(io.ktor.server.cio.CIO, port = port) {
            routing {

                options("/{...}") {
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.response.headers.append("Access-Control-Allow-Headers", "Content-Type, Authorization")
                    call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    call.respond(HttpStatusCode.OK)
                }

                get("/") {
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    val session = app.activeSession
                    val body = if (session != null) {
                        val backend = try { app.inference.nativeGetBackendName() } catch (_: Throwable) { "CPU" }
                        """{"status":"ok","model":"${session.modelName}","backend":"$backend"}"""
                    } else {
                        """{"status":"idle","model":null}"""
                    }
                    call.respondText(body, ContentType.Application.Json)
                }

                get("/capabilities") {
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    val elig = readEligibility(app)
                    val nodeName = Settings.Global.getString(app.contentResolver, "device_name")
                        ?: Build.MODEL
                    call.respondText(
                        json.encodeToString(CapabilitiesResponse(
                            node = nodeName,
                            role = "edge_llm",
                            service_alive = true,
                            model_loaded = elig.modelLoaded,
                            battery_percent = elig.batteryPercent,
                            charging = elig.charging,
                            thermal_status = elig.thermalStatus,
                            foreground_service = true,
                            eligible_for_inference = elig.eligible,
                            reason_if_not_eligible = elig.reason,
                            last_inference_at = lastInferenceAt,
                            last_error = lastInferenceError,
                            // B.3: OS thermal-zone telemetry
                            peak_thermal_zone_c          = elig.peakThermalZoneC,
                            peak_thermal_zone_type       = elig.peakThermalZoneType,
                            peak_cpu_zone_c              = elig.peakCpuZoneC,
                            peak_cpu_zone_type           = elig.peakCpuZoneType,
                            peak_gpu_zone_c              = elig.peakGpuZoneC,
                            peak_gpu_zone_type           = elig.peakGpuZoneType,
                            thermal_zone_readable_count  = elig.thermalZoneReadableCount,
                            thermal_zone_error_count     = elig.thermalZoneErrorCount,
                            thermal_zone_gate_reason     = elig.thermalZoneGateReason,
                            battery_temperature_c        = elig.batteryTemperatureC
                        )),
                        ContentType.Application.Json
                    )
                }

                get("/health") {
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    val modelLoaded = app.activeSession != null
                    val uptimeMs = if (serverStartTime > 0L) System.currentTimeMillis() - serverStartTime else 0L
                    call.respondText("""{"status":"ok","node":"pocket-node","device":"android","model_loaded":$modelLoaded,"uptime_ms":$uptimeMs}""", ContentType.Application.Json)
                }

                if (com.pocketnode.app.BuildConfig.DEBUG) {
                    post("/debug/eligibility/force_block") {
                        call.response.headers.append("Access-Control-Allow-Origin", "*")
                        debugForceBlock = true
                        call.respondText("""{"ok":true,"debug_force_block":true}""", ContentType.Application.Json)
                    }

                    post("/debug/eligibility/clear") {
                        call.response.headers.append("Access-Control-Allow-Origin", "*")
                        debugForceBlock = false
                        call.respondText("""{"ok":true,"debug_force_block":false}""", ContentType.Application.Json)
                    }
                }

                post("/api/generate") {
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    if (!authorize(call.request.headers[HttpHeaders.Authorization])) {
                        call.respond(HttpStatusCode.Unauthorized, "{\"error\":\"unauthorized\"}")
                        return@post
                    }

                    val req = try {
                        json.decodeFromString<GenerateRequest>(call.receiveText())
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, "{\"error\":\"invalid json body\"}")
                        return@post
                    }

                    streamResponse(call, app, req.prompt, req.max_tokens, req.temperature, req.top_p, req.top_k, req.repeat_penalty)
                }

                post("/api/chat") {
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    if (!authorize(call.request.headers[HttpHeaders.Authorization])) {
                        call.respond(HttpStatusCode.Unauthorized, "{\"error\":\"unauthorized\"}")
                        return@post
                    }

                    val req = try {
                        json.decodeFromString<ChatRequest>(call.receiveText())
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, "{\"error\":\"invalid json body\"}")
                        return@post
                    }

                    val chatSession = app.activeSession
                    val prompt = if (chatSession != null) {
                        val meta = app.inference.nativeGetModelMetadata(chatSession.contextPtr)
                        applyTemplate(req.messages.map { ChatMessageRequest(it.role, it.content) }, meta)
                    } else {
                        req.messages.joinToString("\n") { "${it.role}: ${it.content}" }
                    }
                    streamResponse(call, app, prompt, req.max_tokens, req.temperature, req.top_p, req.top_k, req.repeat_penalty)
                }

                post("/v1/chat/completions") {
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    if (!authorize(call.request.headers[HttpHeaders.Authorization])) {
                        call.respond(HttpStatusCode.Unauthorized, "{\"error\":\"unauthorized\"}")
                        return@post
                    }

                    val req = try {
                        json.decodeFromString<OaiChatRequest>(call.receiveText())
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, "{\"error\":\"invalid json body\"}")
                        return@post
                    }

                    val oaiSession = app.activeSession
                    val prompt = if (oaiSession != null) {
                        val meta = app.inference.nativeGetModelMetadata(oaiSession.contextPtr)
                        applyTemplate(req.messages, meta)
                    } else {
                        req.messages.joinToString("\n") { "${it.role}: ${it.content}" }
                    }
                    val topP = req.top_p ?: 0.9f
                    val topK = req.top_k ?: 40
                    if (req.stream == true) {
                        streamOaiResponse(call, app, prompt, req.max_tokens ?: 512, req.temperature ?: 0.7f, topP, topK, req.model ?: "pocket-node")
                    } else {
                        // stream == false or omitted — return a single complete response object
                        nonStreamOaiResponse(call, app, prompt, req.max_tokens ?: 512, req.temperature ?: 0.7f, topP, topK, req.model ?: "pocket-node")
                    }
                }
            }
        }
        serverStartTime = System.currentTimeMillis()
        server!!.start(wait = false)
        isStarted = true
    }

    private suspend fun streamResponse(
        call: ApplicationCall,
        app: MainApplication,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float
    ) {
        if (isStopping) {
            call.respond(HttpStatusCode.ServiceUnavailable, "{\"error\":\"server stopping\"}")
            return
        }

        val elig = readEligibility(app)
        if (!elig.eligible) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                json.encodeToString(NodeUnavailableResponse("node_unavailable", elig.reason!!, true))
            )
            return
        }

        val session = app.activeSession
        if (session == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, "{\"error\":\"no model loaded\"}")
            return
        }

        if (!inferenceMutex.tryLock()) {
            call.respond(HttpStatusCode.Conflict, "{\"error\":\"inference busy — try again shortly\"}")
            return
        }

        try {
            call.respondTextWriter(contentType = ContentType("application", "x-ndjson")) {
                val writer = this
                withContext(Dispatchers.IO) {
                    val callback = object : LlamaCallback {
                        override fun onToken(token: String) {
                            writer.write(json.encodeToString(TokenChunk(token)) + "\n")
                            writer.flush()
                        }
                        override fun onStats(
                            tps: Float, ttftMs: Long, draftAcceptRate: Float,
                            totalTokens: Int, promptEvalTps: Float, backendName: String,
                            nDrafted: Int, nAccepted: Int, nCtx: Int, nPast: Int
                        ) { /* Edge API stats not surfaced over HTTP */ }
                    }
                    try {
                        app.inference.nativeGenerate(
                            ctxPtr = session.contextPtr,
                            prompt = prompt,
                            imageEmbedPtr = 0L,
                            maxTokens = maxTokens,
                            temperature = temperature,
                            topP = topP,
                            topK = topK,
                            repeatPenalty = repeatPenalty,
                            draftCtxPtr = 0L,
                            nDraft = 0,
                            batchSize = 512,
                            ubatchSize = 128,
                            callback = callback
                        )
                        lastInferenceAt = nowIso8601()
                        lastInferenceError = null
                    } catch (_: Exception) {
                        lastInferenceError = "generation failed"
                        writer.write(json.encodeToString(ErrorChunk("generation failed")) + "\n")
                        writer.flush()
                    }
                    writer.write(json.encodeToString(DoneChunk()) + "\n")
                    writer.flush()
                }
            }
        } finally {
            inferenceMutex.unlock()
        }
    }

    private suspend fun streamOaiResponse(
        call: ApplicationCall,
        app: MainApplication,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        modelId: String
    ) {
        if (isStopping) {
            call.respond(HttpStatusCode.ServiceUnavailable, "{\"error\":\"server stopping\"}")
            return
        }

        val elig = readEligibility(app)
        if (!elig.eligible) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                json.encodeToString(NodeUnavailableResponse("node_unavailable", elig.reason!!, true))
            )
            return
        }

        val session = app.activeSession
        if (session == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, "{\"error\":\"no model loaded\"}")
            return
        }

        if (!inferenceMutex.tryLock()) {
            call.respond(HttpStatusCode.Conflict, "{\"error\":\"inference busy — try again shortly\"}")
            return
        }

        val completionId = "chatcmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1000L

        try {
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                val writer = this
                withContext(Dispatchers.IO) {
                    val callback = object : LlamaCallback {
                        override fun onToken(token: String) {
                            val chunk = OaiChunk(
                                id = completionId,
                                obj = "chat.completion.chunk",
                                created = created,
                                model = modelId,
                                choices = listOf(OaiChoice(0, OaiDelta(content = token), null))
                            )
                            writer.write("data: ${json.encodeToString(chunk)}\n\n")
                            writer.flush()
                        }
                        override fun onStats(tps: Float, ttftMs: Long, draftAcceptRate: Float,
                            totalTokens: Int, promptEvalTps: Float, backendName: String,
                            nDrafted: Int, nAccepted: Int, nCtx: Int, nPast: Int) {}
                    }
                    try {
                        app.inference.nativeGenerate(
                            ctxPtr = session.contextPtr, prompt = prompt, imageEmbedPtr = 0L,
                            maxTokens = maxTokens, temperature = temperature,
                            topP = topP, topK = topK, repeatPenalty = 1.1f,
                            draftCtxPtr = 0L, nDraft = 0, batchSize = 512, ubatchSize = 128,
                            callback = callback
                        )
                        lastInferenceAt = nowIso8601()
                        lastInferenceError = null
                    } catch (_: Exception) {
                        lastInferenceError = "generation failed"
                        writer.write("data: {\"error\":\"generation failed\"}\n\n")
                        writer.flush()
                    }
                    // Stop chunk: delta must be {} not {"content":null} — safe string interpolation used intentionally
                    writer.write("data: {\"id\":\"$completionId\",\"object\":\"chat.completion.chunk\",\"created\":$created,\"model\":${json.encodeToString(modelId)},\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                    writer.write("data: [DONE]\n\n")
                    writer.flush()
                }
            }
        } finally {
            inferenceMutex.unlock()
        }
    }

    private suspend fun nonStreamOaiResponse(
        call: ApplicationCall,
        app: MainApplication,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        modelId: String
    ) {
        if (isStopping) {
            call.respond(HttpStatusCode.ServiceUnavailable, "{\"error\":\"server stopping\"}")
            return
        }

        val elig = readEligibility(app)
        if (!elig.eligible) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                json.encodeToString(NodeUnavailableResponse("node_unavailable", elig.reason!!, true))
            )
            return
        }

        val session = app.activeSession
        if (session == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, "{\"error\":\"no model loaded\"}")
            return
        }

        if (!inferenceMutex.tryLock()) {
            call.respond(HttpStatusCode.Conflict, "{\"error\":\"inference busy — try again shortly\"}")
            return
        }

        val completionId = "chatcmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1000L
        val contentBuilder = StringBuilder()

        var generationFailed = false
        try {
            withContext(Dispatchers.IO) {
                val callback = object : LlamaCallback {
                    override fun onToken(token: String) { contentBuilder.append(token) }
                    override fun onStats(tps: Float, ttftMs: Long, draftAcceptRate: Float,
                        totalTokens: Int, promptEvalTps: Float, backendName: String,
                        nDrafted: Int, nAccepted: Int, nCtx: Int, nPast: Int) {}
                }
                try {
                    app.inference.nativeGenerate(
                        ctxPtr = session.contextPtr, prompt = prompt, imageEmbedPtr = 0L,
                        maxTokens = maxTokens, temperature = temperature,
                        topP = topP, topK = topK, repeatPenalty = 1.1f,
                        draftCtxPtr = 0L, nDraft = 0, batchSize = 512, ubatchSize = 128,
                        callback = callback
                    )
                    lastInferenceAt = nowIso8601()
                    lastInferenceError = null
                } catch (_: Exception) {
                    lastInferenceError = "generation failed"
                    generationFailed = true
                }
            }
            if (generationFailed) {
                call.respond(HttpStatusCode.InternalServerError, "{\"error\":\"generation failed\"}")
            } else {
                val completion = OaiChatCompletion(
                    id = completionId,
                    obj = "chat.completion",
                    created = created,
                    model = modelId,
                    choices = listOf(
                        OaiNonStreamChoice(
                            index = 0,
                            message = OaiNonStreamMessage(role = "assistant", content = contentBuilder.toString()),
                            finish_reason = "stop"
                        )
                    )
                )
                call.respondText(json.encodeToString(completion), ContentType.Application.Json)
            }
        } finally {
            inferenceMutex.unlock()
        }
    }

    private fun detectTemplate(metadata: Array<String>?): PromptTemplate {
        val chatTemplate = metadata?.getOrNull(4) ?: ""
        val arch = metadata?.getOrNull(0) ?: ""
        return when {
            chatTemplate.contains("<|im_start|>") -> PromptTemplate.ChatML
            chatTemplate.contains("<|start_header_id|>") -> PromptTemplate.Llama3
            arch.startsWith("qwen") -> PromptTemplate.ChatML
            arch == "llama" -> PromptTemplate.Llama3
            else -> PromptTemplate.ChatML
        }
    }

    private fun applyTemplate(messages: List<ChatMessageRequest>, metadata: Array<String>?): String {
        val template = detectTemplate(metadata)
        val systemPrompt = messages.firstOrNull { it.role == "system" }?.content ?: ""
        val nonSystem = messages.filter { it.role != "system" }
        val lastUserIndex = nonSystem.indexOfLast { it.role == "user" }
        val history = if (lastUserIndex > 0) nonSystem.take(lastUserIndex).map { it.role to it.content } else emptyList()
        val userPrompt = nonSystem.getOrNull(lastUserIndex)?.content ?: ""
        return template.format(systemPrompt, history, userPrompt)
    }

    private fun authorize(authHeader: String?): Boolean {
        val expectedKey = cachedApiKey.value
        return expectedKey.isBlank() || authHeader == "Bearer $expectedKey"
    }

    private data class EligibilityResult(
        // ── Existing fields ───────────────────────────────────────────────────
        val eligible: Boolean,
        val reason: String?,
        val modelLoaded: Boolean,
        val batteryPercent: Int,
        val charging: Boolean,
        val thermalStatus: String,
        val thermalCode: Int,
        // ── B.3: OS thermal-zone data ─────────────────────────────────────────
        val peakThermalZoneC: Double?,
        val peakThermalZoneType: String?,
        val peakCpuZoneC: Double?,
        val peakCpuZoneType: String?,
        val peakGpuZoneC: Double?,
        val peakGpuZoneType: String?,
        val thermalZoneReadableCount: Int,
        val thermalZoneErrorCount: Int,
        val thermalZoneGateReason: String?,
        val batteryTemperatureC: Double?
    )

    private fun readEligibility(app: MainApplication): EligibilityResult {
        // ── Battery ──────────────────────────────────────────────────────────
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPercent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val charging = bm.isCharging

        // Battery temperature via sticky ACTION_BATTERY_CHANGED (tenths of °C → °C)
        val batteryTempC: Double? = try {
            val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (rawTemp != null && rawTemp != Int.MIN_VALUE) rawTemp / 10.0 else null
        } catch (_: Exception) { null }

        if (debugForceBlock) {
            return EligibilityResult(
                eligible = false, reason = "debug_forced_block",
                modelLoaded = app.activeSession != null,
                batteryPercent = batteryPercent, charging = charging,
                thermalStatus = "none", thermalCode = 0,
                peakThermalZoneC = null, peakThermalZoneType = null,
                peakCpuZoneC = null, peakCpuZoneType = null,
                peakGpuZoneC = null, peakGpuZoneType = null,
                thermalZoneReadableCount = 0, thermalZoneErrorCount = 0,
                thermalZoneGateReason = null, batteryTemperatureC = batteryTempC
            )
        }

        // ── PowerManager thermal status (existing gate — unchanged) ──────────
        val thermalCode: Int
        val thermalStatus: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("NewApi")
            thermalCode = pm.currentThermalStatus
            thermalStatus = thermalStatusString(thermalCode)
        } else {
            thermalCode = 0
            thermalStatus = "none"
        }

        // ── B.3: OS thermal-zone gate ─────────────────────────────────────────
        val zoneSnap = ThermalZoneReader.readSnapshot()
        val peakZone    = zoneSnap.peakC    ?: 0.0
        val peakCpuZone = zoneSnap.peakCpuC ?: 0.0
        val peakGpuZone = zoneSnap.peakGpuC ?: 0.0

        // Hard-block hysteresis: latch on >= HARD_BLOCK, release only when < COOLDOWN
        if (peakZone >= THERMAL_ZONE_HARD_BLOCK_C) {
            thermalZoneHardBlocked = true
        } else if (thermalZoneHardBlocked && peakZone < THERMAL_ZONE_COOLDOWN_C) {
            thermalZoneHardBlocked = false
        }

        // Gate reason (most severe wins)
        val thermalZoneGateReason: String? = when {
            thermalZoneHardBlocked ->
                "thermal_zone_hard_block (peak=${"%.1f".format(peakZone)}°C >= ${THERMAL_ZONE_HARD_BLOCK_C}°C; cooldown to ${THERMAL_ZONE_COOLDOWN_C}°C)"
            peakCpuZone >= THERMAL_ZONE_SOFT_BLOCK_CPU_C ->
                "thermal_zone_cpu_soft_block (${zoneSnap.peakCpuType ?: "cpu"}=${"%.1f".format(peakCpuZone)}°C >= ${THERMAL_ZONE_SOFT_BLOCK_CPU_C}°C)"
            peakGpuZone >= THERMAL_ZONE_SOFT_BLOCK_GPU_C ->
                "thermal_zone_gpu_soft_block (${zoneSnap.peakGpuType ?: "gpu"}=${"%.1f".format(peakGpuZone)}°C >= ${THERMAL_ZONE_SOFT_BLOCK_GPU_C}°C)"
            peakZone >= THERMAL_ZONE_WARN_C ->
                "thermal_zone_warn (peak=${"%.1f".format(peakZone)}°C >= ${THERMAL_ZONE_WARN_C}°C; inference allowed)"
            else -> null
        }

        val modelLoaded = app.activeSession != null
        val reason = when {
            !modelLoaded -> "model_not_loaded"
            batteryPercent < 30 && !charging -> "battery_below_threshold"
            thermalCode >= 3 -> "thermal_severe"
            thermalZoneHardBlocked -> "thermal_zone_hard_block"
            peakCpuZone >= THERMAL_ZONE_SOFT_BLOCK_CPU_C -> "thermal_zone_cpu_soft_block"
            peakGpuZone >= THERMAL_ZONE_SOFT_BLOCK_GPU_C -> "thermal_zone_gpu_soft_block"
            else -> null
        }

        return EligibilityResult(
            eligible = reason == null,
            reason = reason,
            modelLoaded = modelLoaded,
            batteryPercent = batteryPercent,
            charging = charging,
            thermalStatus = thermalStatus,
            thermalCode = thermalCode,
            peakThermalZoneC      = zoneSnap.peakC,
            peakThermalZoneType   = zoneSnap.peakType,
            peakCpuZoneC          = zoneSnap.peakCpuC,
            peakCpuZoneType       = zoneSnap.peakCpuType,
            peakGpuZoneC          = zoneSnap.peakGpuC,
            peakGpuZoneType       = zoneSnap.peakGpuType,
            thermalZoneReadableCount = zoneSnap.readableCount,
            thermalZoneErrorCount    = zoneSnap.errorCount,
            thermalZoneGateReason    = thermalZoneGateReason,
            batteryTemperatureC      = batteryTempC
        )
    }

    private fun thermalStatusString(code: Int): String = when (code) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "none"
    }

    private fun nowIso8601(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }

    /**
     * Stops the server and drains any in-flight inference.
     * Returns true if drain completed; false if it timed out.
     * On false the caller MUST NOT free native context pointers —
     * a bounded leak is safer than a use-after-free SIGSEGV.
     *
     * On timeout: isStopping is left true and isStarted false.
     * The process is logically degraded — further start() calls are blocked
     * for the remainder of the process lifetime, which is the right posture
     * because the process now holds leaked native allocations.
     */
    fun stop(): Boolean {
        if (!isStarted) return true
        isStopping = true
        ServiceHealthLog.record(EventType.STOP_DRAIN_STARTED)
        val drained = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(STOP_DRAIN_TIMEOUT_MS) { inferenceMutex.withLock { } } != null
        }
        if (drained) {
            ServiceHealthLog.record(EventType.STOP_DRAIN_OK)
        } else {
            ServiceHealthLog.record(EventType.STOP_DRAIN_TIMEOUT,
                "inference still running after ${STOP_DRAIN_TIMEOUT_MS}ms")
        }
        keyCollectorJob?.cancel()
        keyCollectorJob = null
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        server = null
        serverStartTime = 0L
        isStarted = false
        if (drained) {
            isStopping = false
        } else {
            // Mark the process contaminated — never cleared, never restartable.
            contaminated = true
        }
        return drained
    }
}
