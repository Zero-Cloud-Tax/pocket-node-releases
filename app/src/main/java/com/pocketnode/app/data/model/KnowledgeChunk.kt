package com.pocketnode.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_chunks",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeDocument::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = KnowledgeSource::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId"), Index("sourceId")]
)
data class KnowledgeChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val sourceId: Long,
    val chunkIndex: Int,
    val documentTitle: String,
    val text: String,
    val tokenEstimate: Int,
    val charStart: Int,
    val charEnd: Int,
    val createdAt: Long = System.currentTimeMillis()
)
