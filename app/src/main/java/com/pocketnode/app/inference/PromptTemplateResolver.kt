package com.pocketnode.app.inference

data class TemplateResolution(
    val template: PromptTemplate,
    val decision: String,
    val reason: String
)

object PromptTemplateResolver {

    fun resolve(metadata: ModelMetadata?, selectedModelName: String): TemplateResolution {
        val chatTemplateContent = metadata?.chatTemplate?.trim() ?: ""
        val arch = metadata?.architecture?.lowercase()?.trim() ?: ""
        val metaName = metadata?.name?.lowercase()?.trim() ?: ""
        val displayName = selectedModelName.lowercase().trim()

        // 1. Prefer embedded chat_template content detection
        if (chatTemplateContent.isNotEmpty()) {
            if (chatTemplateContent.contains("<|start_header_id|>") ||
                chatTemplateContent.contains("<|end_header_id|>")) {
                return TemplateResolution(PromptTemplate.Llama3, "Llama3", "embedded_template_llama3")
            }
            if (chatTemplateContent.contains("<|im_start|>") ||
                chatTemplateContent.contains("<|im_end|>")) {
                return TemplateResolution(PromptTemplate.ChatML, "ChatML", "embedded_template_chatml")
            }
        }

        // 2. Architecture + name fallback

        // PocketNode SmolLM3/Operator artifact: arch=smollm3 + known PocketNode name → Llama3.
        // Only this specific on-device artifact is mapped to Llama3; generic smollm3 gets ChatML.
        if (arch == "smollm3") {
            val isPocketNodeArtifact = listOf("pocketnode_operator", "pocketnode_smollm3", "pocketnode")
                .any { pattern -> displayName.contains(pattern) || metaName.contains(pattern) }
            return if (isPocketNodeArtifact) {
                TemplateResolution(PromptTemplate.Llama3, "Llama3", "pocketnode_smollm3_name_match")
            } else {
                TemplateResolution(PromptTemplate.ChatML, "ChatML", "smollm3_generic_chatml_fallback")
            }
        }

        // Llama 3 family
        if (arch == "llama" ||
            displayName.contains("llama-3") || displayName.contains("llama3") || displayName.contains("llama 3") ||
            metaName.contains("llama-3") || metaName.contains("llama3") || metaName.contains("llama 3")) {
            return TemplateResolution(PromptTemplate.Llama3, "Llama3", "llama3_arch_or_name")
        }

        // ChatML families: qwen, qwen2, qwen2.5, smollm, smollm2
        val chatMlArchs = setOf("qwen", "qwen2", "qwen2_5", "smollm", "smollm2")
        if (arch in chatMlArchs ||
            displayName.contains("qwen") || metaName.contains("qwen") ||
            displayName.contains("smollm") || metaName.contains("smollm")) {
            return TemplateResolution(PromptTemplate.ChatML, "ChatML", "chatml_family_arch_or_name")
        }

        // Phi family — no app-specific Phi template, fall back to ChatML
        if (arch.startsWith("phi") || displayName.contains("phi") || metaName.contains("phi")) {
            return TemplateResolution(PromptTemplate.ChatML, "ChatML", "phi_chatml_fallback")
        }

        // Unknown / generic architecture
        return TemplateResolution(PromptTemplate.ChatML, "ChatML", "unknown_arch_generic_fallback")
    }
}
