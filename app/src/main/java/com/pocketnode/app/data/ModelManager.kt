package com.pocketnode.app.data

import android.content.Context
import androidx.room.Room
import com.pocketnode.app.data.model.LocalModel
import kotlinx.coroutines.flow.Flow

class ModelManager(context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java, "pocketnode.db"
    ).build()

    private val modelDao = db.modelDao()

    fun getModels(): Flow<List<LocalModel>> = modelDao.getAllModels()

    suspend fun addModel(model: LocalModel) {
        modelDao.insertModel(model)
    }

    suspend fun deleteModel(model: LocalModel) {
        modelDao.deleteModel(model)
    }
}
