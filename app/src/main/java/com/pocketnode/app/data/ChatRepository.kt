package com.pocketnode.app.data

import com.pocketnode.app.data.model.ChatMessage
import com.pocketnode.app.inference.PromptTemplate
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val chatDao: ChatDao) {

    fun getMessages(conversationId: Long): Flow<List<ChatMessage>> =
        chatDao.getMessagesForConversation(conversationId)

    suspend fun saveMessage(message: ChatMessage) =
        chatDao.insertMessage(message)

    suspend fun clearConversation(conversationId: Long) {
        chatDao.deleteMessagesForConversation(conversationId)
        chatDao.deleteConversation(conversationId)
    }

    fun buildContextString(
        messages: List<ChatMessage>,
        systemPrompt: String,
        template: PromptTemplate,
        maxHistory: Int = 10
    ): String {
        val recent = if (messages.size > maxHistory) messages.takeLast(maxHistory) else messages
        // Build history pairs, excluding the last user message (it becomes the prompt)
        val historyMessages = if (recent.isNotEmpty()) recent.dropLast(1) else emptyList()
        val currentPrompt = recent.lastOrNull()?.content ?: ""
        val history = historyMessages.map { it.role to it.content }
        return template.format(systemPrompt, history, currentPrompt)
    }
}
