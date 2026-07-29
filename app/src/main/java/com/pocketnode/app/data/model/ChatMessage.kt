package com.pocketnode.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    // True when streaming was interrupted (stopGeneration, cancellation, or a
    // rejected stale response) before this turn finished — the content, if
    // any, is a partial fragment and must never be treated as a completed
    // assistant turn by callers.
    val interrupted: Boolean = false
)
