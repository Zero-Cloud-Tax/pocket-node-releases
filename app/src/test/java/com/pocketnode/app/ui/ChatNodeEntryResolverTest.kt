package com.pocketnode.app.ui

import com.pocketnode.app.data.VerificationStatus
import com.pocketnode.app.data.model.LocalModel
import com.pocketnode.app.data.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNodeEntryResolverTest {
    @Test
    fun redirectsWithExplicitReasonWhenNoMainModelExists() {
        val decision = ChatNodeEntryResolver.resolve(emptyList())

        assertTrue(decision.redirectsToModelHub)
        assertEquals("no_active_main_model", decision.reason)
        assertEquals(
            "No active chat model selected. Import or download a main model in Model Hub.",
            decision.userMessage
        )
    }

    @Test
    fun redirectsWithExplicitReasonWhenMainModelIsNotEligible() {
        val main = LocalModel(
            id = "failed-main",
            name = "PocketNode_Operator_Q4_0",
            path = "C:\\missing.gguf",
            contextLength = 4096,
            role = ModelRole.MAIN.name,
            verificationStatus = VerificationStatus.FAILED
        )

        val decision = ChatNodeEntryResolver.resolve(listOf(main)) { false }

        assertTrue(decision.redirectsToModelHub)
        assertEquals("selected_main_model_file_missing", decision.reason)
        assertEquals(
            "Selected chat model file is missing. Re-import it from Model Hub.",
            decision.userMessage
        )
    }

    @Test
    fun genericUnknownHashMainModelCanProceedToChatLoadPath() {
        val main = LocalModel(
            id = "baseline-main",
            name = "Qwen 2.5 1.5B Instruct Q4",
            path = "C:\\models\\qwen.gguf",
            contextLength = 4096,
            role = ModelRole.MAIN.name,
            verificationStatus = VerificationStatus.UNKNOWN_HASH
        )

        val decision = ChatNodeEntryResolver.resolve(listOf(main)) { true }

        assertFalse(decision.redirectsToModelHub)
        assertEquals("using_default_main_model", decision.reason)
        assertEquals("C:\\models\\qwen.gguf", decision.modelPathToOpen)
    }

    @Test
    fun operatorHashMismatchRemainsBlocked() {
        val operator = LocalModel(
            id = "operator-main",
            name = "PocketNode_Operator_Q4_0",
            path = "C:\\models\\operator.gguf",
            contextLength = 4096,
            role = ModelRole.MAIN.name,
            sha256 = "deadbeef",
            verificationStatus = VerificationStatus.UNKNOWN_HASH
        )

        val decision = ChatNodeEntryResolver.resolve(listOf(operator)) { true }

        assertTrue(decision.redirectsToModelHub)
        assertEquals("selected_main_model_hash_mismatch", decision.reason)
        assertEquals(
            "Selected chat model does not match the expected PocketNode Operator artifact.",
            decision.userMessage
        )
    }
}
