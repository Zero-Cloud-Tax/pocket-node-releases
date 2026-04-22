package com.pocketnode.app.inference

import com.pocketnode.app.MainApplication
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GenerateRequest(
    val prompt: String,
    val max_tokens: Int = 512,
    val temperature: Float = 0.7f,
    val top_p: Float = 0.9f,
    val top_k: Int = 40
)

object ApiServer {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val inferenceMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun start(app: MainApplication, port: Int = 11434) {
        if (server != null) return
        server = embeddedServer(CIO, port = port) {
            routing {

                get("/") {
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
                    val session = app.activeSession
                    if (session == null) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            """{"error":"no model loaded"}"""
                        )
                        return@post
                    }

                    if (inferenceMutex.isLocked) {
                        call.respond(
                            HttpStatusCode(409, "Conflict"),
                            """{"error":"inference busy — try again shortly"}"""
                        )
                        return@post
                    }

                    val req = try {
                        json.decodeFromString<GenerateRequest>(call.receiveText())
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, """{"error":"invalid json body"}""")
                        return@post
                    }

                    inferenceMutex.withLock {
                        call.respondTextWriter(
                            contentType = ContentType("application", "x-ndjson")
                        ) {
                            val writer = this
                            withContext(Dispatchers.IO) {
                                val callback = object : LlamaCallback {
                                    override fun onToken(token: String) {
                                        val escaped = token
                                            .replace("\\", "\\\\")
                                            .replace("\"", "\\\"")
                                            .replace("\n", "\\n")
                                        writer.write("""{"token":"$escaped"}""" + "\n")
                                        writer.flush()
                                    }
                                }
                                try {
                                    app.inference.nativeGenerate(
                                        ctxPtr = session.contextPtr,
                                        prompt = req.prompt,
                                        maxTokens = req.max_tokens,
                                        temperature = req.temperature,
                                        topP = req.top_p,
                                        topK = req.top_k,
                                        repeatPenalty = 1.1f,
                                        callback = callback
                                    )
                                } catch (_: Exception) {
                                    writer.write("""{"error":"generation failed"}""" + "\n")
                                    writer.flush()
                                }
                                writer.write("""{"done":true}""" + "\n")
                                writer.flush()
                            }
                        }
                    }
                }
            }
        }
        server!!.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        server = null
    }
}
