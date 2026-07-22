package com.pocketnode.app.inference

import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure, Android-independent pieces of the Hybrid alignment pass:
 * reason-code mapping, correlation-id sanitization, and prompt-size estimation.
 * ApiServer itself requires an Android runtime (Context, PowerManager, BatteryManager)
 * so its route wiring is not exercised here — see AGENTS.md verification notes.
 */
class ApiServerHybridAlignmentTest {

    @Test
    fun reasonCodeWireValuesAreStableLowercaseSnakeLikeTokens() {
        assertEquals("thermal_block", ReasonCode.THERMAL_BLOCK.wireValue)
        assertEquals("model_not_loaded", ReasonCode.MODEL_NOT_LOADED.wireValue)
        assertEquals("context_too_large", ReasonCode.CONTEXT_TOO_LARGE.wireValue)
        assertEquals("conversation_too_long", ReasonCode.CONVERSATION_TOO_LONG.wireValue)
        assertEquals("server_busy", ReasonCode.SERVER_BUSY.wireValue)
        assertEquals("battery_low", ReasonCode.BATTERY_LOW.wireValue)
        assertEquals("inference_failed", ReasonCode.INFERENCE_FAILED.wireValue)
        assertEquals("invalid_request", ReasonCode.INVALID_REQUEST.wireValue)
    }

    @Test
    fun nodeStatusWireValuesMatchSpecStateModel() {
        assertEquals("ready", NodeStatus.READY.wireValue)
        assertEquals("degraded", NodeStatus.DEGRADED.wireValue)
        assertEquals("blocked", NodeStatus.BLOCKED.wireValue)
    }

    @Test
    fun contextAndConversationLimitReasonsAreRetryableFalseWithA413() {
        assertEquals(HttpStatusCode.PayloadTooLarge, ReasonCode.CONTEXT_TOO_LARGE.httpStatus)
        assertEquals(HttpStatusCode.PayloadTooLarge, ReasonCode.CONVERSATION_TOO_LONG.httpStatus)
        assertTrue(!ReasonCode.CONTEXT_TOO_LARGE.retryable)
        assertTrue(!ReasonCode.CONVERSATION_TOO_LONG.retryable)
    }

    @Test
    fun thermalAndModelReasonsAreRetryable() {
        assertTrue(ReasonCode.THERMAL_BLOCK.retryable)
        assertTrue(ReasonCode.MODEL_NOT_LOADED.retryable)
        assertTrue(ReasonCode.BATTERY_LOW.retryable)
    }

    @Test
    fun reasonCodeForMapsExistingEligibilityReasonStringsToStableCodes() {
        assertEquals(ReasonCode.MODEL_NOT_LOADED, ApiServer.reasonCodeFor("model_not_loaded"))
        assertEquals(ReasonCode.BATTERY_LOW, ApiServer.reasonCodeFor("battery_below_threshold"))
        assertEquals(ReasonCode.THERMAL_BLOCK, ApiServer.reasonCodeFor("thermal_severe"))
        assertEquals(ReasonCode.THERMAL_BLOCK, ApiServer.reasonCodeFor("thermal_zone_hard_block"))
        assertEquals(ReasonCode.THERMAL_BLOCK, ApiServer.reasonCodeFor("thermal_zone_cpu_soft_block"))
        assertEquals(ReasonCode.THERMAL_BLOCK, ApiServer.reasonCodeFor("thermal_zone_gpu_soft_block"))
        assertEquals(ReasonCode.SERVER_BUSY, ApiServer.reasonCodeFor("debug_forced_block"))
    }

    @Test
    fun reasonCodeForReturnsNullWhenEligibleAndUnsupportedForUnknownReasons() {
        assertNull(ApiServer.reasonCodeFor(null))
        assertEquals(ReasonCode.REQUEST_UNSUPPORTED, ApiServer.reasonCodeFor("some_future_reason"))
    }

    @Test
    fun sanitizeCorrelationIdStripsControlCharactersAndBoundsLength() {
        assertEquals("abc123", ApiServer.sanitizeCorrelationId("abc123"))
        assertEquals("abc123", ApiServer.sanitizeCorrelationId("ab\nc1\t23 "))
        assertNull(ApiServer.sanitizeCorrelationId(null))
        assertNull(ApiServer.sanitizeCorrelationId(""))
        assertNull(ApiServer.sanitizeCorrelationId("\n\t "))

        val oversized = "a".repeat(500)
        val sanitized = ApiServer.sanitizeCorrelationId(oversized)
        assertEquals(128, sanitized?.length)
    }

    @Test
    fun sanitizeCorrelationIdAllowsDotsUnderscoresAndHyphens() {
        assertEquals("req-123.abc_9", ApiServer.sanitizeCorrelationId("req-123.abc_9"))
    }

    @Test
    fun estimateTokenCountIsRoughlyCharsDividedByFourAndNeverZero() {
        assertEquals(1, ApiServer.estimateTokenCount(""))
        assertEquals(1, ApiServer.estimateTokenCount("hi"))
        assertEquals(2048, ApiServer.estimateTokenCount("a".repeat(8192)))
        assertTrue(ApiServer.estimateTokenCount("a".repeat(8196)) > ApiServer.MAX_PROMPT_TOKENS)
    }

    @Test
    fun declaredRoutingLimitsMatchKnownHybridFallbackThresholds() {
        assertEquals(2048, ApiServer.MAX_PROMPT_TOKENS)
        assertEquals(5, ApiServer.MAX_CONVERSATION_TURNS)
    }

    @Test
    fun schemaVersionIsDeclared() {
        assertEquals("1", ApiServer.SCHEMA_VERSION)
    }

    @Test
    fun unsupportedFeatureKeyDetectsToolCallingFields() {
        assertEquals(
            "tools",
            ApiServer.unsupportedFeatureKey("""{"messages":[],"tools":[{"type":"function"}]}""")
        )
        assertEquals(
            "tool_choice",
            ApiServer.unsupportedFeatureKey("""{"messages":[],"tool_choice":"auto"}""")
        )
        assertEquals(
            "functions",
            ApiServer.unsupportedFeatureKey("""{"messages":[],"functions":[]}""")
        )
        assertEquals(
            "function_call",
            ApiServer.unsupportedFeatureKey("""{"messages":[],"function_call":"auto"}""")
        )
    }

    @Test
    fun unsupportedFeatureKeyReturnsNullForOrdinaryRequests() {
        assertNull(
            ApiServer.unsupportedFeatureKey("""{"messages":[{"role":"user","content":"hi"}],"stream":false}""")
        )
        assertNull(ApiServer.unsupportedFeatureKey("""{"prompt":"hi"}"""))
    }

    @Test
    fun unsupportedFeatureKeyReturnsNullForMalformedJsonRatherThanThrowing() {
        assertNull(ApiServer.unsupportedFeatureKey("not json"))
        assertNull(ApiServer.unsupportedFeatureKey(""))
    }
}
