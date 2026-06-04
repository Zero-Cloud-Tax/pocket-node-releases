package com.pocketnode.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_documents",
    foreignKeys = [ForeignKey(
        entity = KnowledgeSource::class,
        parentColumns = ["id"],
        childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sourceId")]
)
data class KnowledgeDocument(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val displayName: String,
    val uriPath: String,
    val sizeBytes: Long = 0,
    val modifiedAt: Long = 0,
    val textHash: String = "",
    val indexedAt: Long = System.currentTimeMillis()
)
