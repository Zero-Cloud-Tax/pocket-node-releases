package com.pocketnode.app.data

import com.pocketnode.app.data.model.ChatMessage
import com.pocketnode.app.data.model.Conversation
import com.pocketnode.app.inference.PromptTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class ChatRepository(private val chatDao: ChatDao) {
    fun getConversations(): Flow<List<Conversation>> = chatDao.getAllConversations()

    suspend fun createConversation(title: String, modelId: String = "unknown"): Long =
        chatDao.insertConversation(Conversation(title = title, modelId = modelId))

    suspend fun updateConversationTitle(conversationId: Long, title: String) =
        chatDao.updateConversationTitle(conversationId, title)

    suspend fun ensureConversation(conversationId: Long, title: String, modelId: String = "unknown") {
        if (chatDao.getConversationById(conversationId) == null) {
            chatDao.insertConversation(
                Conversation(
                    id = conversationId,
                    title = title,
                    modelId = modelId,
                    createdAt = System.currentTimeMillis(),
                    lastMessageAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun getMessages(conversationId: Long): Flow<List<ChatMessage>> =
        chatDao.getMessagesForConversation(conversationId)

    suspend fun getMessagesSnapshot(conversationId: Long): List<ChatMessage> =
        chatDao.getMessagesForConversation(conversationId).first()

    suspend fun saveMessage(message: ChatMessage): Long {
        val id = chatDao.insertMessage(message)
        chatDao.updateConversationTimestamp(message.conversationId, System.currentTimeMillis())
        return id
    }

    suspend fun updateMessage(message: ChatMessage) =
        chatDao.updateMessage(message)

    suspend fun clearConversation(conversationId: Long) {
        chatDao.deleteMessagesForConversation(conversationId)
        chatDao.deleteConversation(conversationId)
    }

    fun buildContextString(
        messages: List<ChatMessage>,
        systemPrompt: String,
        template: PromptTemplate,
        maxHistory: Int = 10,
        knowledgeContext: String = "",
        promptOverride: String? = null
    ): String {
        val recent = if (messages.size > maxHistory) messages.takeLast(maxHistory) else messages
        val historyMessages = if (recent.isNotEmpty()) recent.dropLast(1) else emptyList()
        val rawPrompt = promptOverride ?: recent.lastOrNull()?.content.orEmpty()
        val currentPrompt = if (knowledgeContext.isNotEmpty()) {
            "$knowledgeContext\n\n$rawPrompt"
        } else {
            rawPrompt
        }
        val history = historyMessages.map { it.role to it.content }
        return template.format(systemPrompt, history, currentPrompt)
    }
}
