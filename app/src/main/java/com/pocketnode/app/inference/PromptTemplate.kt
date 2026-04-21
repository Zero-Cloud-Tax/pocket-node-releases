package com.pocketnode.app.inference

sealed class PromptTemplate(val name: String) {
    abstract fun format(systemPrompt: String, history: List<Pair<String, String>>, prompt: String): String

    object ChatML : PromptTemplate("ChatML") {
        override fun format(systemPrompt: String, history: List<Pair<String, String>>, prompt: String): String {
            val sb = StringBuilder()
            if (systemPrompt.isNotEmpty()) {
                sb.append("<|im_start|>system\n$systemPrompt<|im_end|>\n")
            }
            for (msg in history) {
                sb.append("<|im_start|>${msg.first}\n${msg.second}<|im_end|>\n")
            }
            sb.append("<|im_start|>user\n$prompt<|im_end|>\n")
            sb.append("<|im_start|>assistant\n")
            return sb.toString()
        }
    }

    object Llama3 : PromptTemplate("Llama 3") {
        override fun format(systemPrompt: String, history: List<Pair<String, String>>, prompt: String): String {
            val sb = StringBuilder()
            sb.append("<|begin_of_text|>")
            if (systemPrompt.isNotEmpty()) {
                sb.append("<|start_header_id|>system<|end_header_id|>\n\n$systemPrompt<|eot_id|>")
            }
            for (msg in history) {
                val role = if (msg.first == "user") "user" else "assistant"
                sb.append("<|start_header_id|>$role<|end_header_id|>\n\n${msg.second}<|eot_id|>")
            }
            sb.append("<|start_header_id|>user<|end_header_id|>\n\n$prompt<|eot_id|>")
            sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            return sb.toString()
        }
    }

    object Alpaca : PromptTemplate("Alpaca") {
        override fun format(systemPrompt: String, history: List<Pair<String, String>>, prompt: String): String {
            val sb = StringBuilder()
            if (systemPrompt.isNotEmpty()) {
                sb.append("$systemPrompt\n\n")
            }
            for (msg in history) {
                if (msg.first == "user") {
                    sb.append("### Instruction:\n${msg.second}\n\n")
                } else {
                    sb.append("### Response:\n${msg.second}\n\n")
                }
            }
            sb.append("### Instruction:\n$prompt\n\n### Response:\n")
            return sb.toString()
        }
    }
}
