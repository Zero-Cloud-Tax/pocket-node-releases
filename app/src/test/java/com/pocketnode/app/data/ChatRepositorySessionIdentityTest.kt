package com.pocketnode.app.data

import com.pocketnode.app.data.model.ChatMessage
import com.pocketnode.app.data.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatRepositorySessionIdentityTest {

    private class InMemoryChatDao : ChatDao {
        val conversations = linkedMapOf<Long, Conversation>()
        private val messages = linkedMapOf<Long, ChatMessage>()

        override fun getAllConversations(): Flow<List<Conversation>> =
            MutableStateFlow(conversations.values.toList())

        override suspend fun getConversationById(id: Long): Conversation? = conversations[id]

        override suspend fun insertConversation(conversation: Conversation): Long {
            val id = conversation.id.takeIf { it != 0L } ?: (conversations.size + 1L)
            conversations[id] = conversation.copy(id = id)
            return id
        }

        override suspend fun updateConversationTitle(id: Long, title: String) {
            conversations[id] = conversations[id]?.copy(title = title) ?: return
        }

        override suspend fun updateConversationTimestamp(id: Long, timestamp: Long) {
            conversations[id] = conversations[id]?.copy(lastMessageAt = timestamp) ?: return
        }

        override suspend fun updateConversationSession(id: Long, sessionUuid: String, generation: Int) {
            conversations[id] = conversations[id]?.copy(sessionUuid = sessionUuid, generation = generation) ?: return
        }

        override fun getMessagesForConversation(conversationId: Long): Flow<List<ChatMessage>> =
            MutableStateFlow(messages.values.filter { it.conversationId == conversationId })

        override suspend fun getMessageById(id: Long): ChatMessage? = messages[id]

        override suspend fun insertMessage(message: ChatMessage): Long {
            val id = message.id.takeIf { it != 0L } ?: (messages.size + 1L).also { }
            messages[id] = message.copy(id = id)
            return id
        }

        override suspend fun updateMessage(message: ChatMessage) {
            messages[message.id] = message
        }

        override suspend fun deleteMessageById(messageId: Long) {
            messages.remove(messageId)
        }

        override suspend fun deleteMessagesForConversation(conversationId: Long) {
            messages.values.filter { it.conversationId == conversationId }.forEach { messages.remove(it.id) }
        }

        override suspend fun deleteConversation(id: Long) {
            conversations.remove(id)
        }
    }

    @Test
    fun ensureSessionIdentity_backfillsBlankUuidOnMigratedRow() = runBlocking {
        val dao = InMemoryChatDao()
        // Simulates a row migrated from schema version 6, where sessionUuid defaults to ''.
        dao.conversations[1L] = Conversation(id = 1L, title = "Chat", modelId = "m", sessionUuid = "", generation = 0)
        val repository = ChatRepository(dao)

        val (sessionId, generation) = repository.ensureSessionIdentity(1L)

        assertFalse(sessionId.isBlank())
        assertEquals(0, generation)
        assertEquals(sessionId, dao.conversations[1L]?.sessionUuid)
    }

    @Test
    fun ensureSessionIdentity_doesNotOverwriteExistingUuid() = runBlocking {
        val dao = InMemoryChatDao()
        dao.conversations[1L] = Conversation(id = 1L, title = "Chat", modelId = "m", sessionUuid = "fixed-uuid", generation = 2)
        val repository = ChatRepository(dao)

        val (sessionId, generation) = repository.ensureSessionIdentity(1L)

        assertEquals("fixed-uuid", sessionId)
        assertEquals(2, generation)
    }

    @Test
    fun newlyCreatedConversations_getDistinctSessionUuids() = runBlocking {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)

        val idA = repository.createConversation("A")
        val idB = repository.createConversation("B")

        val (uuidA, _) = repository.ensureSessionIdentity(idA)
        val (uuidB, _) = repository.ensureSessionIdentity(idB)

        assertNotEquals(uuidA, uuidB)
    }

    @Test
    fun persistSessionState_updatesGenerationWithoutChangingSessionId() = runBlocking {
        val dao = InMemoryChatDao()
        dao.conversations[1L] = Conversation(id = 1L, title = "Chat", modelId = "m", sessionUuid = "fixed-uuid", generation = 0)
        val repository = ChatRepository(dao)

        repository.persistSessionState(1L, "fixed-uuid", 1)

        val stored = dao.conversations[1L]!!
        assertEquals("fixed-uuid", stored.sessionUuid)
        assertEquals(1, stored.generation)
    }
}
