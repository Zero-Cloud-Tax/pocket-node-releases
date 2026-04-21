package com.pocketnode.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.pocketnode.app.data.model.ChatMessage
import com.pocketnode.app.data.model.Conversation
import com.pocketnode.app.data.model.LocalModel

@Database(entities = [LocalModel::class, ChatMessage::class, Conversation::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun chatDao(): ChatDao
}
