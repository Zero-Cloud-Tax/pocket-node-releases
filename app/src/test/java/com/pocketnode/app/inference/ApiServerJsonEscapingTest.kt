package com.pocketnode.app.inference

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiServerJsonEscapingTest {
    @Serializable
    private data class TokenChunk(val token: String)

    @Test
    fun jsonEncodingEscapesControlCharacters() {
        val input = "line1\nline2\r\t\b\u000C\"\\\\"
        val encoded = Json.encodeToString(TokenChunk(input))
        assertTrue(encoded.contains("\\n"))
        assertTrue(encoded.contains("\\r"))
        assertTrue(encoded.contains("\\t"))
        assertTrue(encoded.contains("\\b"))
        assertTrue(encoded.contains("\\f"))
        assertTrue(encoded.contains("\\\""))
        assertTrue(encoded.contains("\\\\"))
    }
}
