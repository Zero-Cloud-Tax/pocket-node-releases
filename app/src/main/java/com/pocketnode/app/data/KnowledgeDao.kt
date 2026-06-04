package com.pocketnode.app.data

import androidx.room.*
import com.pocketnode.app.data.model.KnowledgeChunk
import com.pocketnode.app.data.model.KnowledgeDocument
import com.pocketnode.app.data.model.KnowledgeSource
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDao {

    // ── Sources ──────────────────────────────────────────────────────────────

    @Query("SELECT * FROM knowledge_sources ORDER BY createdAt DESC")
    fun getAllSources(): Flow<List<KnowledgeSource>>

    @Insert
    suspend fun insertSource(source: KnowledgeSource): Long

    @Query("DELETE FROM knowledge_sources WHERE id = :sourceId")
    suspend fun deleteSource(sourceId: Long)

    @Query(
        "UPDATE knowledge_sources SET documentCount = :docCount, chunkCount = :chunkCount, " +
        "lastIndexedAt = :indexedAt WHERE id = :id"
    )
    suspend fun updateSourceCounts(id: Long, docCount: Int, chunkCount: Int, indexedAt: Long)

    // ── Documents ─────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertDocument(document: KnowledgeDocument): Long

    @Query("SELECT * FROM knowledge_documents WHERE sourceId = :sourceId")
    suspend fun getDocumentsBySourceId(sourceId: Long): List<KnowledgeDocument>

    @Query("SELECT * FROM knowledge_documents WHERE sourceId = :sourceId AND uriPath = :uriPath LIMIT 1")
    suspend fun getDocumentByUri(sourceId: Long, uriPath: String): KnowledgeDocument?

    @Query("DELETE FROM knowledge_documents WHERE id = :docId")
    suspend fun deleteDocument(docId: Long)

    @Query("SELECT COUNT(*) FROM knowledge_documents WHERE sourceId = :sourceId")
    suspend fun getDocumentCount(sourceId: Long): Int

    @Query("SELECT COUNT(*) FROM knowledge_chunks WHERE sourceId = :sourceId")
    suspend fun getChunkCount(sourceId: Long): Int

    // ── Chunks ────────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertChunks(chunks: List<KnowledgeChunk>)

    @Query(
        "SELECT * FROM knowledge_chunks " +
        "WHERE lower(text) LIKE '%' || lower(:term) || '%' " +
        "   OR lower(documentTitle) LIKE '%' || lower(:term) || '%' " +
        "LIMIT 100"
    )
    suspend fun searchChunksByTerm(term: String): List<KnowledgeChunk>
}
