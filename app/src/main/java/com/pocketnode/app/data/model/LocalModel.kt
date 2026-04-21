package com.pocketnode.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "models")
data class LocalModel(
    @PrimaryKey val id: String,
    val name: String,
    val path: String,
    val contextLength: Int,
    val addedAt: Long = System.currentTimeMillis()
)
