package com.pocketnode.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pocketnode.app.data.model.ChatMessage
import com.pocketnode.app.data.model.Conversation
import com.pocketnode.app.data.model.KnowledgeChunk
import com.pocketnode.app.data.model.KnowledgeDocument
import com.pocketnode.app.data.model.KnowledgeSource
import com.pocketnode.app.data.model.LocalModel

@Database(
    entities = [
        LocalModel::class,
        ChatMessage::class,
        Conversation::class,
        KnowledgeSource::class,
        KnowledgeDocument::class,
        KnowledgeChunk::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun chatDao(): ChatDao
    abstract fun knowledgeDao(): KnowledgeDao

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

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_sources (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        displayName TEXT NOT NULL,
                        uriPath TEXT NOT NULL,
                        isFolder INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        lastIndexedAt INTEGER NOT NULL DEFAULT 0,
                        documentCount INTEGER NOT NULL DEFAULT 0,
                        chunkCount INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_documents (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceId INTEGER NOT NULL,
                        displayName TEXT NOT NULL,
                        uriPath TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL DEFAULT 0,
                        modifiedAt INTEGER NOT NULL DEFAULT 0,
                        textHash TEXT NOT NULL DEFAULT '',
                        indexedAt INTEGER NOT NULL,
                        FOREIGN KEY (sourceId) REFERENCES knowledge_sources(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_knowledge_documents_sourceId " +
                    "ON knowledge_documents(sourceId)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_chunks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        documentId INTEGER NOT NULL,
                        sourceId INTEGER NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        documentTitle TEXT NOT NULL,
                        text TEXT NOT NULL,
                        tokenEstimate INTEGER NOT NULL DEFAULT 0,
                        charStart INTEGER NOT NULL DEFAULT 0,
                        charEnd INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY (documentId) REFERENCES knowledge_documents(id) ON DELETE CASCADE,
                        FOREIGN KEY (sourceId) REFERENCES knowledge_sources(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_knowledge_chunks_documentId " +
                    "ON knowledge_chunks(documentId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_knowledge_chunks_sourceId " +
                    "ON knowledge_chunks(sourceId)"
                )
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
