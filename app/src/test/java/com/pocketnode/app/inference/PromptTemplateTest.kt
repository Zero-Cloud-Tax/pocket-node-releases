package com.pocketnode.app.inference

import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplateTest {
    @Test
    fun chatMlTemplateIncludesSystemHistoryAndAssistantCue() {
        val output = PromptTemplate.ChatML.format(
            systemPrompt = "sys",
            history = listOf("user" to "hi", "assistant" to "hello"),
            prompt = "next"
        )
        assertTrue(output.contains("<|im_start|>system"))
        assertTrue(output.contains("<|im_start|>user\nnext<|im_end|>"))
        assertTrue(output.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun llama3TemplateIncludesHeaders() {
        val output = PromptTemplate.Llama3.format(
            systemPrompt = "sys",
            history = listOf("user" to "hi"),
            prompt = "next"
        )
        assertTrue(output.contains("<|start_header_id|>system<|end_header_id|>"))
        assertTrue(output.contains("<|start_header_id|>assistant<|end_header_id|>"))
    }

    @Test
    fun alpacaTemplateIncludesInstructionResponseBlocks() {
        val output = PromptTemplate.Alpaca.format(
            systemPrompt = "sys",
            history = listOf("user" to "hi", "assistant" to "hello"),
            prompt = "next"
        )
        assertTrue(output.contains("### Instruction:"))
        assertTrue(output.contains("### Response:"))
    }
}
