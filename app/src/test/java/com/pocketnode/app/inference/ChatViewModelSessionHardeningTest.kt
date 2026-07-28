package com.pocketnode.app.inference

import com.pocketnode.app.MainApplication
import com.pocketnode.app.data.ChatDao
import com.pocketnode.app.data.ChatRepository
import com.pocketnode.app.data.model.ChatMessage
import com.pocketnode.app.data.model.Conversation
import com.pocketnode.app.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration coverage for the stale-session/context-corruption hardening
 * work: a stopped generation must never be persisted as a completed
 * assistant turn (see the finally-block cleanup in
 * ChatViewModel.sendMessageInternal), and every conversation gets a stable
 * session identity as soon as it's used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSessionHardeningTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 10. Interrupted stream does not become a completed assistant message.
    @Test
    fun stoppedGenerationWithPartialContent_isFlaggedInterruptedNotCompleted() {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = FakeInferenceEngine(streamedTokens = listOf("partial", " answer"))
        val viewModel = ChatViewModel(inference = inference, repository = repository, app = MainApplication(), healthSummaryOverride = { "test-health" })
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)
        viewModel.setStopRequestedForTesting(true)

        runBlocking {
            viewModel.sendMessageInternal(text = "Hello", conversationId = 500L, template = PromptTemplate.ChatML)
        }

        val messages = runBlocking { repository.getMessagesSnapshot(500L) }
        val assistant = messages.first { it.role == "assistant" }
        assertTrue("partial content must be preserved, not silently dropped", assistant.content.isNotBlank())
        assertTrue("a stopped generation must be flagged interrupted", assistant.interrupted)
    }

    // Companion case: a stopped generation with no tokens yet must be
    // deleted, not left as an empty "completed" assistant turn.
    @Test
    fun stoppedGenerationWithNoContent_isDeletedNotSaved() {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = FakeInferenceEngine(streamedTokens = emptyList())
        val viewModel = ChatViewModel(inference = inference, repository = repository, app = MainApplication(), healthSummaryOverride = { "test-health" })
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)
        viewModel.setStopRequestedForTesting(true)

        runBlocking {
            viewModel.sendMessageInternal(text = "Hello", conversationId = 501L, template = PromptTemplate.ChatML)
        }

        val messages = runBlocking { repository.getMessagesSnapshot(501L) }
        assertTrue(messages.none { it.role == "assistant" })
    }

    // Baseline: an uninterrupted send is saved as completed, not interrupted.
    @Test
    fun normalGeneration_isSavedCompletedNotInterrupted() {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = FakeInferenceEngine(streamedTokens = listOf("Hi", " there"))
        val viewModel = ChatViewModel(inference = inference, repository = repository, app = MainApplication(), healthSummaryOverride = { "test-health" })
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)

        runBlocking {
            viewModel.sendMessageInternal(text = "Hello", conversationId = 502L, template = PromptTemplate.ChatML)
        }

        val messages = runBlocking { repository.getMessagesSnapshot(502L) }
        val assistant = messages.first { it.role == "assistant" }
        assertFalse(assistant.interrupted)
        assertTrue(assistant.content.isNotBlank())
        assertEquals(SessionState.COMPLETED, viewModel.sessionSnapshotForTesting().state)
    }

    // 1. Every conversation is assigned a stable session id as soon as it's used.
    @Test
    fun sendingIntoAConversation_assignsAPersistedSessionId() {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = FakeInferenceEngine(streamedTokens = listOf("ok"))
        val viewModel = ChatViewModel(inference = inference, repository = repository, app = MainApplication(), healthSummaryOverride = { "test-health" })
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)

        runBlocking {
            viewModel.sendMessageInternal(text = "Hello", conversationId = 503L, template = PromptTemplate.ChatML)
        }

        val (sessionId, _) = runBlocking { repository.ensureSessionIdentity(503L) }
        assertTrue(sessionId.isNotBlank())
        assertEquals(sessionId, viewModel.sessionSnapshotForTesting().sessionId)
    }

    private class InMemoryChatDao : ChatDao {
        private val conversations = linkedMapOf<Long, Conversation>()
        private val messages = linkedMapOf<Long, MutableList<ChatMessage>>()
        private val conversationsFlow = MutableStateFlow(emptyList<Conversation>())
        private val messageFlows = linkedMapOf<Long, MutableStateFlow<List<ChatMessage>>>()
        private var nextConversationId = 1_000L
        private var nextMessageId = 10_000L

        override fun getAllConversations(): Flow<List<Conversation>> = conversationsFlow

        override suspend fun getConversationById(id: Long): Conversation? = conversations[id]

        override suspend fun insertConversation(conversation: Conversation): Long {
            val id = conversation.id.takeIf { it != 0L } ?: nextConversationId++
            conversations[id] = conversation.copy(id = id)
            emitConversations()
            return id
        }

        override suspend fun updateConversationTitle(id: Long, title: String) {
            conversations[id] = conversations[id]?.copy(title = title) ?: return
            emitConversations()
        }

        override suspend fun updateConversationTimestamp(id: Long, timestamp: Long) {
            conversations[id] = conversations[id]?.copy(lastMessageAt = timestamp) ?: return
            emitConversations()
        }

        override suspend fun updateConversationSession(id: Long, sessionUuid: String, generation: Int) {
            conversations[id] = conversations[id]?.copy(sessionUuid = sessionUuid, generation = generation) ?: return
            emitConversations()
        }

        override fun getMessagesForConversation(conversationId: Long): Flow<List<ChatMessage>> =
            messageFlow(conversationId)

        override suspend fun getMessageById(id: Long): ChatMessage? =
            messages.values.flatten().firstOrNull { it.id == id }

        override suspend fun insertMessage(message: ChatMessage): Long {
            val id = message.id.takeIf { it != 0L } ?: nextMessageId++
            val saved = message.copy(id = id)
            val entries = messages.getOrPut(saved.conversationId) { mutableListOf() }
            entries.removeAll { it.id == id }
            entries += saved
            entries.sortBy { it.timestamp }
            emitMessages(saved.conversationId)
            return id
        }

        override suspend fun updateMessage(message: ChatMessage) {
            val entries = messages.getOrPut(message.conversationId) { mutableListOf() }
            val index = entries.indexOfFirst { it.id == message.id }
            if (index >= 0) entries[index] = message else entries += message
            entries.sortBy { it.timestamp }
            emitMessages(message.conversationId)
        }

        override suspend fun deleteMessageById(messageId: Long) {
            for ((conversationId, entries) in messages) {
                if (entries.removeAll { it.id == messageId }) emitMessages(conversationId)
            }
        }

        override suspend fun deleteMessagesForConversation(conversationId: Long) {
            messages.remove(conversationId)
            emitMessages(conversationId)
        }

        override suspend fun deleteConversation(id: Long) {
            conversations.remove(id)
        }

        private fun emitConversations() {
            conversationsFlow.value = conversations.values.sortedByDescending { it.lastMessageAt }
        }

        private fun emitMessages(conversationId: Long) {
            messageFlow(conversationId).value = messages[conversationId]?.toList() ?: emptyList()
        }

        private fun messageFlow(conversationId: Long): MutableStateFlow<List<ChatMessage>> =
            messageFlows.getOrPut(conversationId) { MutableStateFlow(messages[conversationId]?.toList() ?: emptyList()) }
    }

    private class FakeInferenceEngine(private val streamedTokens: List<String>) : InferenceEngine {
        override fun nativeLoadModel(modelPath: String, nGpuLayers: Int): Long = 1L
        override fun nativeFreeModel(modelPtr: Long) = Unit
        override fun nativeCreateContext(modelPtr: Long, contextSize: Int, nThreads: Int): Long = 1L
        override fun nativeFreeContext(ctxPtr: Long) = Unit
        override fun nativeLoadDraftModel(modelPath: String, nGpuLayers: Int): Long = 1L
        override fun nativeFreeDraftModel(draftModelPtr: Long) = Unit
        override fun nativeCreateDraftContext(draftModelPtr: Long, contextSize: Int, nThreads: Int): Long = 1L
        override fun nativeFreeDraftContext(draftCtxPtr: Long) = Unit

        override fun nativeGenerate(
            ctxPtr: Long,
            prompt: String,
            imageEmbedPtr: Long,
            maxTokens: Int,
            temperature: Float,
            topP: Float,
            topK: Int,
            repeatPenalty: Float,
            draftCtxPtr: Long,
            nDraft: Int,
            batchSize: Int,
            ubatchSize: Int,
            callback: LlamaCallback
        ) {
            streamedTokens.forEach(callback::onToken)
            callback.onStats(120f, 50L, 0f, streamedTokens.size, 200f, "CPU", 0, 0, 4096, 0)
        }

        override fun nativeStopGeneration(ctxPtr: Long) = Unit
        override fun nativeClearCache(ctxPtr: Long) = Unit
        override fun nativeGetTokenCount(modelPtr: Long, text: String): Int = text.length / 4
        override fun nativeGetContextLength(modelPtr: Long): Int = 4096
        override fun nativeGetEmbeddingSize(modelPtr: Long): Int = 0
        override fun nativeGetVocabSize(modelPtr: Long): Int = 0
        override fun nativeGetLastError(): String = ""
        override fun nativeGetBackendName(): String = "CPU"
        override fun nativeGetModelMetadata(contextPtr: Long): Array<String>? = null
        override fun nativeCloseFd(fd: Int) = Unit
        override fun nativeLoadMmproj(mmprojPath: String): Long = 0L
        override fun nativeFreeMmproj(ctxPtr: Long) = Unit
        override fun nativeMakeImageEmbed(ctxPtr: Long, imageBytes: ByteArray): Long = 0L
        override fun nativeFreeImageEmbed(embedPtr: Long) = Unit
    }
}
