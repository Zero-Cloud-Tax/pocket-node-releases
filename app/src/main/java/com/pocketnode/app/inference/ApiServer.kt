package com.pocketnode.app.inference

import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketnode.app.MainApplication
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
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

object ApiServer {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val inferenceMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cachedApiKey = MutableStateFlow("")

    fun start(app: MainApplication, port: Int = 11434) {
        if (server != null) return

        serverScope.launch {
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

                    val prompt = req.messages.joinToString("\n") { "${it.role}: ${it.content}" }
                    streamResponse(call, app, prompt, req.max_tokens, req.temperature, req.top_p, req.top_k, req.repeat_penalty)
                }
            }
        }
        server!!.start(wait = false)
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
                            callback = callback
                        )
                    } catch (_: Exception) {
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

    private fun authorize(authHeader: String?): Boolean {
        val expectedKey = cachedApiKey.value
        return expectedKey.isBlank() || authHeader == "Bearer $expectedKey"
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        server = null
    }
}
