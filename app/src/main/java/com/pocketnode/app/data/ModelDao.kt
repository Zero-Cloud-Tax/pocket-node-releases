package com.pocketnode.app.data

import androidx.room.*
import com.pocketnode.app.data.model.LocalModel
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY addedAt DESC")
    fun getAllModels(): Flow<List<LocalModel>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: LocalModel)

    @Delete
    suspend fun deleteModel(model: LocalModel)

    @Query("SELECT * FROM models WHERE id = :id LIMIT 1")
    suspend fun getModelById(id: String): LocalModel?
}
