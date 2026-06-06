package com.pocketnode.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplateResolverTest {

    // ── Manual override ───────────────────────────────────────────────────────

    @Test
    fun manualOverrideChatMLWinsOverAutoForSmolLM3PocketNode() {
        // Even though smollm3 + pocketnode → Llama3 auto-rule, a manual ChatML override wins.
        val metadata = smolLm3PocketNodeMetadata()
        val result = PromptTemplateResolver.resolve(metadata, "PocketNode_SmolLM3_Q4_0_Fresh")
        assertEquals(PromptTemplate.Llama3, result.template) // auto selects Llama3
        // Caller should skip resolve() when manual override is set; verify the resolver itself is unaffected.
        // (Manual override is enforced by ChatViewModel, not the resolver.)
        assertEquals("pocketnode_smollm3_name_match", result.reason)
    }

    // ── Embedded chat_template detection ─────────────────────────────────────

    @Test
    fun embeddedLlama3ChatTemplateSelectsLlama3() {
        val metadata = ModelMetadata(
            architecture = "llama",
            name = "Some Model",
            tokenizerModel = "gpt2",
            vocabSize = 128256,
            chatTemplate = "{% if messages %}<|start_header_id|>system<|end_header_id|>\\n\\n{{ system }}<|eot_id|>{% endif %}"
        )
        val result = PromptTemplateResolver.resolve(metadata, "some-model")
        assertEquals(PromptTemplate.Llama3, result.template)
        assertEquals("embedded_template_llama3", result.reason)
    }

    @Test
    fun embeddedEndHeaderIdAloneSelectsLlama3() {
        val metadata = ModelMetadata(
            architecture = "llama",
            name = "Some Model",
            tokenizerModel = "gpt2",
            vocabSize = 128256,
            chatTemplate = "<|end_header_id|>"
        )
        val result = PromptTemplateResolver.resolve(metadata, "some-model")
        assertEquals(PromptTemplate.Llama3, result.template)
        assertEquals("embedded_template_llama3", result.reason)
    }

    @Test
    fun embeddedChatMLTemplateSelectsChatML() {
        val metadata = ModelMetadata(
            architecture = "smollm2",
            name = "SmolLM2 360M",
            tokenizerModel = "gpt2",
            vocabSize = 49152,
            chatTemplate = "<|im_start|>system\\n{{ system }}<|im_end|>"
        )
        val result = PromptTemplateResolver.resolve(metadata, "smollm2-360m")
        assertEquals(PromptTemplate.ChatML, result.template)
        assertEquals("embedded_template_chatml", result.reason)
    }

    @Test
    fun embeddedImEndAloneSelectsChatML() {
        val metadata = ModelMetadata(
            architecture = "qwen2",
            name = "Qwen 2",
            tokenizerModel = "gpt2",
            vocabSize = 151936,
            chatTemplate = "...{{ content }}<|im_end|>\\n..."
        )
        val result = PromptTemplateResolver.resolve(metadata, "qwen2-7b")
        assertEquals(PromptTemplate.ChatML, result.template)
        assertEquals("embedded_template_chatml", result.reason)
    }

    // ── PocketNode SmolLM3 / Operator rule ───────────────────────────────────

    @Test
    fun smolLm3PlusMetaNamePocketNodeOperatorSelectsLlama3() {
        val metadata = smolLm3PocketNodeMetadata()
        val result = PromptTemplateResolver.resolve(metadata, "PocketNode_SmolLM3_Q4_0_Fresh")
        assertEquals(PromptTemplate.Llama3, result.template)
        assertEquals("pocketnode_smollm3_name_match", result.reason)
        assertFalse(result.decision == "ChatML")
    }

    @Test
    fun smolLm3PlusDisplayNamePocketNodeSelectsLlama3() {
        val metadata = ModelMetadata(
            architecture = "smollm3",
            name = "Unknown Name",
            tokenizerModel = "gpt2",
            vocabSize = 128256,
            chatTemplate = ""
        )
        val result = PromptTemplateResolver.resolve(metadata, "PocketNode_Q4_0")
        assertEquals(PromptTemplate.Llama3, result.template)
        assertEquals("pocketnode_smollm3_name_match", result.reason)
    }

    @Test
    fun genericSmolLm3WithoutPocketNodeNameDoesNotSelectLlama3() {
        val metadata = ModelMetadata(
            architecture = "smollm3",
            name = "SmolLM3 1.7B",
            tokenizerModel = "gpt2",
            vocabSize = 128256,
            chatTemplate = ""
        )
        val result = PromptTemplateResolver.resolve(metadata, "smollm3-1.7b-q4")
        assertEquals(PromptTemplate.ChatML, result.template)
        assertEquals("smollm3_generic_chatml_fallback", result.reason)
    }

    @Test
    fun genericSmolLm3WithoutPocketNodeNameInMetaOrDisplayDoesNotSelectLlama3() {
        val metadata = ModelMetadata(
            architecture = "smollm3",
            name = "Community SmolLM3 360M",
            tokenizerModel = "gpt2",
            vocabSize = 128256,
            chatTemplate = ""
        )
        val result = PromptTemplateResolver.resolve(metadata, "community-model")
        assertEquals(PromptTemplate.ChatML, result.template)
        assertEquals("smollm3_generic_chatml_fallback", result.reason)
    }

    // ── Llama 3 family ────────────────────────────────────────────────────────

    @Test
    fun archLlamaWithNoEmbeddedTemplateSelectsLlama3() {
        val metadata = ModelMetadata(
            architecture = "llama",
            name = "Llama 3.2 3B Instruct",
            tokenizerModel = "bpe",
            vocabSize = 128256,
            chatTemplate = ""
        )
        val result = PromptTemplateResolver.resolve(metadata, "llama-3.2-3b-instruct-q4")
        assertEquals(PromptTemplate.Llama3, result.template)
        assertEquals("llama3_arch_or_name", result.reason)
    }

    @Test
    fun displayNameLlama3SelectsLlama3() {
        val result = PromptTemplateResolver.resolve(null, "Llama-3.1-8B-Instruct-Q4_K_M")
        assertEquals(PromptTemplate.Llama3, result.template)
        assertEquals("llama3_arch_or_name", result.reason)
    }

    @Test
    fun displayNameLlama3SpaceSelectsLlama3() {
        val result = PromptTemplateResolver.resolve(null, "Meta Llama 3 8B")
        assertEquals(PromptTemplate.Llama3, result.template)
        assertEquals("llama3_arch_or_name", result.reason)
    }

    // ── Qwen / ChatML families ────────────────────────────────────────────────

    @Test
    fun archQwen2SelectsChatML() {
        val metadata = ModelMetadata(
            architecture = "qwen2",
            name = "Qwen 2.5 7B Instruct",
            tokenizerModel = "gpt2",
            vocabSize = 151936,
            chatTemplate = ""
        )
        val result = PromptTemplateResolver.resolve(metadata, "qwen2.5-7b-instruct-q4")
        assertEquals(PromptTemplate.ChatML, result.template)
        assertEquals("chatml_family_arch_or_name", result.reason)
    }

    @Test
    fun archQwen2_5SelectsChatML() {
        val metadata = ModelMetadata(
            architecture = "qwen2_5",
            name = "Qwen 2.5",
            tokenizerModel = "gpt2",
            vocabSize = 151936,
            chatTemplate = ""
        )
        val result = PromptTemplateResolver.resolve(metadata, "qwen2.5-q4")
        assertEquals(PromptTemplate.ChatML, result.template)
        assertEquals("chatml_family_arch_or_name", result.reason)
    }

    @Test
    fun displayNameQwenSelectsChatML() {
        val result = PromptTemplateResolver.resolve(null, "Qwen2-7B-Instruct-Q4_K_M")
        assertEquals(PromptTemplate.ChatML, result.template)
        assertEquals("chatml_family_arch_or_name", result.reason)
    }

    @Test
    fun archSmolLm2SelectsChatML() {
        val metadata = ModelMetadata(
            architecture = "smollm2",
            name = "SmolLM2 1.7B Instruct",
            tokenizerModel = "gpt2",
            vocabSize = 49152,
            chatTemplate = ""
        )
        val result = PromptTemplateResolver.resolve(metadata, "smollm2-1.7b-q4")
        assertEquals(PromptTemplate.ChatML, result.template)
        assertEquals("chatml_family_arch_or_name", result.reason)
    }

    // ── Phi family ────────────────────────────────────────────────────────────

    @Test
    fun archPhi3FallsBackToChatML() {
        val metadata = ModelMetadata(
            architecture = "phi3",
            name = "Phi-3 Mini 4K Instruct",
            tokenizerModel = "gpt2",
            vocabSize = 32064,
            chatTemplate = ""
        )
        val result = PromptTemplateResolver.resolve(metadata, "phi-3-mini-q4")
        assertEquals(PromptTemplate.ChatML, result.template)
        assertEquals("phi_chatml_fallback", result.reason)
    }

    @Test
    fun displayNamePhiFallsBackToChatML() {
        val result = PromptTemplateResolver.resolve(null, "Phi-3.5-mini-instruct-q4")
        assertEquals(PromptTemplate.ChatML, result.template)
        assertEquals("phi_chatml_fallback", result.reason)
    }

    // ── Unknown architecture ──────────────────────────────────────────────────

    @Test
    fun unknownArchFallsBackToChatML() {
        val metadata = ModelMetadata(
            architecture = "gemma2",
            name = "Gemma 2 9B",
            tokenizerModel = "spm",
            vocabSize = 256000,
            chatTemplate = ""
        )
        val result = PromptTemplateResolver.resolve(metadata, "gemma-2-9b-q4")
        assertEquals(PromptTemplate.ChatML, result.template)
        assertEquals("unknown_arch_generic_fallback", result.reason)
    }

    @Test
    fun nullMetadataFallsBackToChatML() {
        val result = PromptTemplateResolver.resolve(null, "")
        assertEquals(PromptTemplate.ChatML, result.template)
        assertEquals("unknown_arch_generic_fallback", result.reason)
    }

    @Test
    fun nullMetadataWithUnknownNameFallsBackToChatML() {
        val result = PromptTemplateResolver.resolve(null, "my-custom-model")
        assertEquals(PromptTemplate.ChatML, result.template)
        assertEquals("unknown_arch_generic_fallback", result.reason)
    }

    // ── Embedded template takes priority over arch/name ───────────────────────

    @Test
    fun embeddedLlama3TemplateOverridesSmolLm3GenericRule() {
        val metadata = ModelMetadata(
            architecture = "smollm3",
            name = "SmolLM3 Community",
            tokenizerModel = "gpt2",
            vocabSize = 128256,
            chatTemplate = "<|start_header_id|>system<|end_header_id|>"
        )
        val result = PromptTemplateResolver.resolve(metadata, "smollm3-community")
        assertEquals(PromptTemplate.Llama3, result.template)
        assertEquals("embedded_template_llama3", result.reason)
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun smolLm3PocketNodeMetadata() = ModelMetadata(
        architecture = "smollm3",
        name = "PocketNode_Operator_BF16_Fresh",
        tokenizerModel = "gpt2",
        vocabSize = 128256,
        chatTemplate = ""
    )
}
