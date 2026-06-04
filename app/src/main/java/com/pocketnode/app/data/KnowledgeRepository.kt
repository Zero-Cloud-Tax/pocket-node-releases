package com.pocketnode.app.data

import androidx.room.withTransaction
import com.pocketnode.app.data.model.KnowledgeChunk
import com.pocketnode.app.data.model.KnowledgeDocument
import com.pocketnode.app.data.model.KnowledgeSource
import kotlinx.coroutines.flow.Flow

class KnowledgeRepository(
    private val db: AppDatabase,
    private val dao: KnowledgeDao
) {

    fun getAllSources(): Flow<List<KnowledgeSource>> = dao.getAllSources()

    suspend fun deleteSource(sourceId: Long) = dao.deleteSource(sourceId)

    // Atomically insert source + document + all chunks; returns the new source id.
    suspend fun indexDocument(
        sourceDisplayName: String,
        sourceUri: String,
        docDisplayName: String,
        docUri: String,
        sizeBytes: Long,
        modifiedAt: Long,
        textHash: String,
        chunks: List<KnowledgeChunk>
    ): Long = db.withTransaction {
        val sourceId = dao.insertSource(
            KnowledgeSource(
                displayName = sourceDisplayName,
                uriPath = sourceUri
            )
        )
        val docId = dao.insertDocument(
            KnowledgeDocument(
                sourceId = sourceId,
                displayName = docDisplayName,
                uriPath = docUri,
                sizeBytes = sizeBytes,
                modifiedAt = modifiedAt,
                textHash = textHash
            )
        )
        if (chunks.isNotEmpty()) {
            dao.insertChunks(chunks.map { it.copy(documentId = docId, sourceId = sourceId) })
        }
        dao.updateSourceCounts(sourceId, 1, chunks.size, System.currentTimeMillis())
        sourceId
    }

    // Create a folder source without any documents; returns sourceId.
    suspend fun createSource(displayName: String, uriPath: String, isFolder: Boolean): Long =
        dao.insertSource(KnowledgeSource(displayName = displayName, uriPath = uriPath, isFolder = isFolder))

    // Fetch all documents belonging to a source.
    suspend fun getDocumentsBySource(sourceId: Long): List<KnowledgeDocument> =
        dao.getDocumentsBySourceId(sourceId)

    // Delete a single document (chunks cascade via FK).
    suspend fun deleteDocument(docId: Long) = dao.deleteDocument(docId)

    // Atomically add one document + chunks to an existing source; returns docId.
    suspend fun addDocumentToSource(
        sourceId: Long,
        docDisplayName: String,
        docUri: String,
        sizeBytes: Long,
        modifiedAt: Long,
        textHash: String,
        chunks: List<KnowledgeChunk>
    ): Long = db.withTransaction {
        val docId = dao.insertDocument(
            KnowledgeDocument(
                sourceId = sourceId,
                displayName = docDisplayName,
                uriPath = docUri,
                sizeBytes = sizeBytes,
                modifiedAt = modifiedAt,
                textHash = textHash
            )
        )
        if (chunks.isNotEmpty()) {
            dao.insertChunks(chunks.map { it.copy(documentId = docId, sourceId = sourceId) })
        }
        docId
    }

    // Recompute document/chunk counts from DB and update the source row.
    suspend fun refreshSourceCounts(sourceId: Long) {
        val docCount = dao.getDocumentCount(sourceId)
        val chunkCount = dao.getChunkCount(sourceId)
        dao.updateSourceCounts(sourceId, docCount, chunkCount, System.currentTimeMillis())
    }

    // Multi-term search: union results across all terms, rank by frequency in memory.
    suspend fun searchChunks(query: String, maxResults: Int = 10): List<KnowledgeChunk> {
        if (query.isBlank()) return emptyList()
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        if (terms.isEmpty()) return emptyList()

        val scored = mutableMapOf<Long, Pair<KnowledgeChunk, Int>>()
        for (term in terms) {
            for (chunk in dao.searchChunksByTerm(term)) {
                val lowerText = chunk.text.lowercase()
                val lowerTitle = chunk.documentTitle.lowercase()
                val textHits = lowerText.split(term).size - 1
                val titleHits = (lowerTitle.split(term).size - 1) * 3
                val existing = scored[chunk.id]
                scored[chunk.id] = chunk to ((existing?.second ?: 0) + textHits + titleHits)
            }
        }

        return scored.values
            .sortedByDescending { (_, score) -> score }
            .take(maxResults)
            .map { (chunk, _) -> chunk }
    }
}
