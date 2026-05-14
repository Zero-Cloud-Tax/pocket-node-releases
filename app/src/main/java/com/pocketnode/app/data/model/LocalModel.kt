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
    val tokenizerHash: String? = null       // SHA256 of tokenizer.json — draft compat check
)
