package com.pocketnode.app.inference

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol/schema coverage for the session-hardening additive fields on
 * GenerateRequest/ChatRequest (context_generation, request_sequence,
 * daemon_boot_id, model_fingerprint) and the new session-related
 * ReasonCodes. Confirms both directions of backward compatibility: an
 * old client's JSON (missing the new fields) still decodes, and the new
 * fields round-trip correctly when present.
 */
class ApiServerSessionProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun generateRequest_decodesLegacyBodyWithoutSessionFields() {
        val legacyBody = """{"prompt":"hi"}"""
        val req = json.decodeFromString<GenerateRequest>(legacyBody)
        assertEquals("hi", req.prompt)
        assertNull(req.sessionId)
        assertNull(req.context_generation)
        assertNull(req.request_sequence)
        assertNull(req.daemon_boot_id)
        assertNull(req.model_fingerprint)
    }

    @Test
    fun generateRequest_decodesNewSessionFieldsWhenPresent() {
        val body = """{"prompt":"hi","sessionId":"s1","context_generation":3,"request_sequence":42,"daemon_boot_id":"boot-9","model_fingerprint":"model-a"}"""
        val req = json.decodeFromString<GenerateRequest>(body)
        assertEquals("s1", req.sessionId)
        assertEquals(3, req.context_generation)
        assertEquals(42L, req.request_sequence)
        assertEquals("boot-9", req.daemon_boot_id)
        assertEquals("model-a", req.model_fingerprint)
    }

    @Test
    fun chatRequest_decodesLegacyBodyWithoutSessionFields() {
        val legacyBody = """{"messages":[{"role":"user","content":"hi"}]}"""
        val req = json.decodeFromString<ChatRequest>(legacyBody)
        assertEquals(1, req.messages.size)
        assertNull(req.context_generation)
        assertNull(req.request_sequence)
    }

    @Test
    fun chatRequest_decodesNewSessionFieldsWhenPresent() {
        val body = """{"messages":[{"role":"user","content":"hi"}],"context_generation":1,"request_sequence":7}"""
        val req = json.decodeFromString<ChatRequest>(body)
        assertEquals(1, req.context_generation)
        assertEquals(7L, req.request_sequence)
    }

    @Test
    fun generateRequest_roundTripsThroughEncodeDecode() {
        val original = GenerateRequest(
            prompt = "hi",
            sessionId = "s1",
            context_generation = 2,
            request_sequence = 10L,
            daemon_boot_id = "boot-1",
            model_fingerprint = "model-a"
        )
        val decoded = json.decodeFromString<GenerateRequest>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun sessionReasonCodes_haveExpectedWireValuesAndHttpStatus() {
        assertEquals("session_stale_generation", ReasonCode.SESSION_STALE_GENERATION.wireValue)
        assertEquals("session_out_of_order", ReasonCode.SESSION_OUT_OF_ORDER.wireValue)
        assertEquals("session_model_mismatch", ReasonCode.SESSION_MODEL_MISMATCH.wireValue)
        assertEquals("session_stale_daemon", ReasonCode.SESSION_STALE_DAEMON.wireValue)
        assertEquals(HttpStatusCode.Conflict, ReasonCode.SESSION_STALE_GENERATION.httpStatus)
        assertEquals(HttpStatusCode.Conflict, ReasonCode.SESSION_OUT_OF_ORDER.httpStatus)
        assertEquals(HttpStatusCode.Conflict, ReasonCode.SESSION_MODEL_MISMATCH.httpStatus)
        assertEquals(HttpStatusCode.Conflict, ReasonCode.SESSION_STALE_DAEMON.httpStatus)
        // Stale generation/sequence/boot are all things a client can recover from by
        // resetting its local session and retrying; a model mismatch is not.
        assertTrue(ReasonCode.SESSION_STALE_GENERATION.retryable)
        assertTrue(ReasonCode.SESSION_OUT_OF_ORDER.retryable)
        assertTrue(ReasonCode.SESSION_STALE_DAEMON.retryable)
        assertFalse(ReasonCode.SESSION_MODEL_MISMATCH.retryable)
    }
}
