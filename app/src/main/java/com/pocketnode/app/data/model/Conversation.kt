package com.pocketnode.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val modelId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastMessageAt: Long = System.currentTimeMillis(),
    // Stable session identity, distinct from the Room row id — used to detect
    // stale/late requests and responses. Blank on rows migrated from schema
    // version 6 or earlier; ChatRepository.ensureSessionIdentity backfills it
    // lazily on first access rather than requiring a SQL-side UUID generator.
    val sessionUuid: String = java.util.UUID.randomUUID().toString(),
    // Context generation for this session. Bumped by an explicit reset or by
    // a model/backend switch; requests/responses tagged with an older
    // generation must be rejected as stale.
    val generation: Int = 0
)
