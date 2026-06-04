package com.pocketnode.app.knowledge

object LocalKnowledgeChunker {

    private const val TARGET_TOKENS = 800
    private const val OVERLAP_TOKENS = 150
    private const val CHARS_PER_TOKEN = 4
    private const val MAX_CHUNKS = 500

    private val TARGET_CHARS = TARGET_TOKENS * CHARS_PER_TOKEN   // 3 200
    private val OVERLAP_CHARS = OVERLAP_TOKENS * CHARS_PER_TOKEN // 600

    data class ChunkData(
        val chunkIndex: Int,
        val documentTitle: String,
        val text: String,
        val tokenEstimate: Int,
        val charStart: Int,
        val charEnd: Int
    )

    fun chunk(text: String, documentTitle: String): List<ChunkData> {
        if (text.isBlank()) return emptyList()

        val result = mutableListOf<ChunkData>()
        var currentIndex = 0
        var chunkIndex = 0

        while (currentIndex < text.length && result.size < MAX_CHUNKS) {
            val rawEnd = minOf(currentIndex + TARGET_CHARS, text.length)

            // Prefer to break at a paragraph boundary near the target end.
            val breakIndex: Int = if (rawEnd < text.length) {
                val searchFloor = currentIndex + TARGET_CHARS / 2
                val lastNl = text.lastIndexOf('\n', rawEnd)
                if (lastNl > searchFloor) lastNl + 1 else rawEnd
            } else {
                rawEnd
            }

            val chunkText = text.substring(currentIndex, breakIndex).trim()
            if (chunkText.isNotEmpty()) {
                result.add(
                    ChunkData(
                        chunkIndex = chunkIndex++,
                        documentTitle = documentTitle,
                        text = chunkText,
                        tokenEstimate = chunkText.length / CHARS_PER_TOKEN,
                        charStart = currentIndex,
                        charEnd = breakIndex
                    )
                )
            }

            // Advance with overlap; always move forward by at least 1 character.
            val step = maxOf(breakIndex - currentIndex - OVERLAP_CHARS, 1)
            currentIndex += step

            if (breakIndex >= text.length) break
        }

        return result
    }
}
