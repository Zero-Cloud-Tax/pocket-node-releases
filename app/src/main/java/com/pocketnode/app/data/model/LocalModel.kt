package com.pocketnode.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "models")
data class LocalModel(
    @PrimaryKey val id: String,
    val name: String,
    val path: String,
    val contextLength: Int,
    val addedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "role", defaultValue = "MAIN")
    val role: String = ModelRole.MAIN.name,

    @ColumnInfo(name = "family")
    val family: String? = null,             // e.g. "SmolLM3", "Llama3", "Phi"

    @ColumnInfo(name = "quantization")
    val quantization: String? = null,       // e.g. "Q4_0", "Q4_K_M" — persisted, not regex

    @ColumnInfo(name = "tokenizer_hash")
    val tokenizerHash: String? = null,      // SHA256 of tokenizer.json — draft compat check

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0L,

    @ColumnInfo(name = "sha256")
    val sha256: String? = null,

    @ColumnInfo(name = "verification_status", defaultValue = "NOT_CHECKED")
    val verificationStatus: String = "NOT_CHECKED",

    @ColumnInfo(name = "last_modified")
    val lastModified: Long = 0L,

    @ColumnInfo(name = "last_checked_at")
    val lastCheckedAt: Long = 0L
)
