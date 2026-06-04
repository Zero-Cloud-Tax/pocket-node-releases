package com.pocketnode.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "knowledge_sources")
data class KnowledgeSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val uriPath: String,
    val isFolder: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastIndexedAt: Long = 0,
    val documentCount: Int = 0,
    val chunkCount: Int = 0
)
