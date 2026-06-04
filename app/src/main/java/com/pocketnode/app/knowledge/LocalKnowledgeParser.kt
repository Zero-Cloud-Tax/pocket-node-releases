package com.pocketnode.app.knowledge

object LocalKnowledgeParser {

    private const val MAX_FILE_BYTES = 5 * 1024 * 1024 // 5 MB

    sealed class ParseResult {
        data class Success(val text: String, val title: String) : ParseResult()
        data class Error(val message: String) : ParseResult()
    }

    fun parse(fileName: String, content: String): ParseResult {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext != "md" && ext != "txt") {
            return ParseResult.Error(
                "Unsupported file type .$ext. Only .md and .txt files are supported."
            )
        }
        if (content.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            return ParseResult.Error("File exceeds the 5 MB limit.")
        }
        return when (ext) {
            "md" -> parseMarkdown(fileName, content)
            else -> parsePlainText(fileName, content)
        }
    }

    private fun parseMarkdown(fileName: String, raw: String): ParseResult {
        var text = raw
        // Strip YAML frontmatter (--- ... ---)
        text = text.replace(Regex("^---\\s*\\n.*?\\n---\\s*\\n", RegexOption.DOT_MATCHES_ALL), "")
        text = normalise(text)
        val title = fileName
            .removeSuffix(".md").removeSuffix(".MD")
            .trimStart('/')
        return ParseResult.Success(text, title)
    }

    private fun parsePlainText(fileName: String, raw: String): ParseResult {
        val title = fileName
            .removeSuffix(".txt").removeSuffix(".TXT")
            .trimStart('/')
        return ParseResult.Success(normalise(raw), title)
    }

    private fun normalise(text: String): String {
        var t = text.replace("\r\n", "\n").replace("\r", "\n")
        t = t.replace(Regex("\\n{3,}"), "\n\n")
        return t.trim()
    }
}
