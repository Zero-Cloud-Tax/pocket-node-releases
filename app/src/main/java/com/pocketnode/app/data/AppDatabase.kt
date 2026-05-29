package com.pocketnode.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pocketnode.app.data.model.ChatMessage
import com.pocketnode.app.data.model.Conversation
import com.pocketnode.app.data.model.LocalModel

@Database(entities = [LocalModel::class, ChatMessage::class, Conversation::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun chatDao(): ChatDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        modelId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastMessageAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO conversations (id, title, modelId, createdAt, lastMessageAt)
                    VALUES (1, 'Chat', 'unknown', strftime('%s','now') * 1000, strftime('%s','now') * 1000),
                           (2, 'Ask Image', 'unknown', strftime('%s','now') * 1000, strftime('%s','now') * 1000),
                           (3, 'Prompt Lab', 'unknown', strftime('%s','now') * 1000, strftime('%s','now') * 1000)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        modelId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastMessageAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO conversations (id, title, modelId, createdAt, lastMessageAt)
                    VALUES (1, 'Chat', 'unknown', strftime('%s','now') * 1000, strftime('%s','now') * 1000),
                           (2, 'Ask Image', 'unknown', strftime('%s','now') * 1000, strftime('%s','now') * 1000),
                           (3, 'Prompt Lab', 'unknown', strftime('%s','now') * 1000, strftime('%s','now') * 1000)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE models ADD COLUMN role TEXT NOT NULL DEFAULT 'MAIN'")
                db.execSQL("ALTER TABLE models ADD COLUMN family TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN quantization TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN tokenizer_hash TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE models ADD COLUMN size_bytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE models ADD COLUMN sha256 TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN verification_status TEXT NOT NULL DEFAULT 'NOT_CHECKED'")
                db.execSQL("ALTER TABLE models ADD COLUMN last_modified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE models ADD COLUMN last_checked_at INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pocketnode.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
