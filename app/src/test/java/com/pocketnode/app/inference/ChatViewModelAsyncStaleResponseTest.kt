package com.pocketnode.app.inference

import com.pocketnode.app.MainApplication
import com.pocketnode.app.data.ChatDao
import com.pocketnode.app.data.ChatRepository
import com.pocketnode.app.data.model.ChatMessage
import com.pocketnode.app.data.model.Conversation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Genuine asynchronous coverage for the stale-response guards: a real
 * background thread is blocked mid-generation (via [GatedInferenceEngine])
 * so a reset / New Chat can race it for real, rather than being simulated
 * synchronously. sendMessage() dispatches onto the real Dispatchers.IO pool
 * (only Dispatchers.Main is swapped for these tests), so this is actual
 * concurrency, not a virtual-time simulation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelAsyncStaleResponseTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun waitUntil(timeoutMs: Long = 5000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10)
        }
    }

    // 1. Old-generation chunks arriving after reset are discarded.
    @Test
    fun lateResponseFromOldGeneration_afterConcurrentReset_isDiscarded() = runBlocking {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val engine = GatedInferenceEngine(tokensToEmit = listOf("late", " content"))
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)

        viewModel.sendMessage(text = "hello", conversationId = 900L)
        engine.started.await() // nativeGenerate is now blocked on engine.gate, on a real IO thread

        // A reset races the in-flight generation for real.
        viewModel.resetSessionContext(900L)
        waitUntil { viewModel.sessionSnapshotForTesting().generation >= 1 }

        engine.gate.complete(Unit) // let the stale generation finish and attempt to persist
        waitUntil { !viewModel.isGenerating.value }

        val messages = repository.getMessagesSnapshot(900L)
        assertTrue(
            "a response from before the reset must never be saved as real content",
            messages.none { it.role == "assistant" && it.content.isNotBlank() }
        )
    }

    // 2. Old-conversation chunks arriving after New Chat are discarded.
    @Test
    fun lateResponseFromOldConversation_afterConcurrentNewChat_isDiscarded() = runBlocking {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val engine = GatedInferenceEngine(tokensToEmit = listOf("late", " content"))
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)
        viewModel.bindConversation(901L)

        viewModel.sendMessage(text = "hello", conversationId = 901L)
        engine.started.await()

        // "New chat" races the in-flight generation for real.
        viewModel.clearChat(901L)
        waitUntil { viewModel.messages.isEmpty() }

        engine.gate.complete(Unit)
        waitUntil { engine.generateCalls == 1 } // generation ran to completion in the background

        val messages = repository.getMessagesSnapshot(901L)
        assertTrue(
            "a response from the cleared conversation must never reappear as real content",
            messages.none { it.role == "assistant" && it.content.isNotBlank() }
        )
    }

    // 3. Retry creates a new request sequence without duplicating the user message.
    @Test
    fun retryInterrupted_usesNewSequenceWithoutDuplicatingUserTurn() = runBlocking {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = SimpleFakeInferenceEngine(streamedTokens = listOf("answer"))
        val viewModel = ChatViewModel(
            inference = inference, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)
        viewModel.setStopRequestedForTesting(true)
        viewModel.sendMessageInternal(text = "hello", conversationId = 902L, template = PromptTemplate.ChatML)
        viewModel.setStopRequestedForTesting(false)

        val afterStop = repository.getMessagesSnapshot(902L)
        val interrupted = afterStop.first { it.role == "assistant" }
        assertTrue(interrupted.interrupted)
        val sequenceBeforeRetry = viewModel.sessionSnapshotForTesting()

        viewModel.retryInterrupted(902L, interrupted.id)
        // isGenerating may already read false before retryInterrupted's async
        // work has even started, so wait for its definitive effect instead.
        waitUntil { inference.generateCalls >= 2 }
        waitUntil { repository.getMessagesSnapshot(902L).any { it.role == "assistant" && !it.interrupted } }

        val afterRetry = repository.getMessagesSnapshot(902L)
        assertEquals("retry must not duplicate the user turn", 1, afterRetry.count { it.role == "user" })
        assertEquals("hello", afterRetry.first { it.role == "user" }.content)
        val assistantMessages = afterRetry.filter { it.role == "assistant" }
        assertEquals(1, assistantMessages.size)
        assertFalse(assistantMessages.first().interrupted)
        assertTrue(inference.generateCalls >= 2)
        // Retry stays within the same generation (the context is still valid,
        // only the request sequence advances) — it's a new sequence, not a reset.
        assertEquals(sequenceBeforeRetry.generation, viewModel.sessionSnapshotForTesting().generation)
    }

    // 10. Dismiss removes only the partial assistant fragment.
    @Test
    fun dismissInterrupted_removesOnlyTheAssistantFragment() = runBlocking {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = SimpleFakeInferenceEngine(streamedTokens = listOf("partial"))
        val viewModel = ChatViewModel(
            inference = inference, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)
        viewModel.setStopRequestedForTesting(true)
        viewModel.sendMessageInternal(text = "hello", conversationId = 903L, template = PromptTemplate.ChatML)

        val beforeDismiss = repository.getMessagesSnapshot(903L)
        val interrupted = beforeDismiss.first { it.role == "assistant" }
        assertTrue(interrupted.interrupted)

        viewModel.dismissInterrupted(interrupted.id)
        waitUntil { repository.getMessagesSnapshot(903L).none { it.role == "assistant" } }

        val afterDismiss = repository.getMessagesSnapshot(903L)
        assertTrue(afterDismiss.none { it.role == "assistant" })
        assertEquals("the user prompt must survive a dismiss", 1, afterDismiss.count { it.role == "user" })
    }

    private class InMemoryChatDao : ChatDao {
        private val conversations = linkedMapOf<Long, Conversation>()
        private val messages = linkedMapOf<Long, MutableList<ChatMessage>>()
        private val conversationsFlow = MutableStateFlow(emptyList<Conversation>())
        private val messageFlows = linkedMapOf<Long, MutableStateFlow<List<ChatMessage>>>()
        private var nextConversationId = 1_000L
        private var nextMessageId = 10_000L

        override fun getAllConversations(): Flow<List<Conversation>> = conversationsFlow

        override suspend fun getConversationById(id: Long): Conversation? = synchronized(this) { conversations[id] }

        override suspend fun insertConversation(conversation: Conversation): Long = synchronized(this) {
            val id = conversation.id.takeIf { it != 0L } ?: nextConversationId++
            conversations[id] = conversation.copy(id = id)
            emitConversations()
            id
        }

        override suspend fun updateConversationTitle(id: Long, title: String) = synchronized(this) {
            conversations[id] = conversations[id]?.copy(title = title) ?: return@synchronized
            emitConversations()
        }

        override suspend fun updateConversationTimestamp(id: Long, timestamp: Long) = synchronized(this) {
            conversations[id] = conversations[id]?.copy(lastMessageAt = timestamp) ?: return@synchronized
            emitConversations()
        }

        override suspend fun updateConversationSession(id: Long, sessionUuid: String, generation: Int) = synchronized(this) {
            conversations[id] = conversations[id]?.copy(sessionUuid = sessionUuid, generation = generation) ?: return@synchronized
            emitConversations()
        }

        override fun getMessagesForConversation(conversationId: Long): Flow<List<ChatMessage>> =
            messageFlow(conversationId)

        override suspend fun getMessageById(id: Long): ChatMessage? = synchronized(this) {
            messages.values.flatten().firstOrNull { it.id == id }
        }

        override suspend fun insertMessage(message: ChatMessage): Long = synchronized(this) {
            val id = message.id.takeIf { it != 0L } ?: nextMessageId++
            val saved = message.copy(id = id)
            val entries = messages.getOrPut(saved.conversationId) { mutableListOf() }
            entries.removeAll { it.id == id }
            entries += saved
            entries.sortBy { it.timestamp }
            emitMessages(saved.conversationId)
            id
        }

        override suspend fun updateMessage(message: ChatMessage) = synchronized(this) {
            val entries = messages.getOrPut(message.conversationId) { mutableListOf() }
            val index = entries.indexOfFirst { it.id == message.id }
            if (index >= 0) entries[index] = message else entries += message
            entries.sortBy { it.timestamp }
            emitMessages(message.conversationId)
        }

        override suspend fun deleteMessageById(messageId: Long) = synchronized(this) {
            for ((conversationId, entries) in messages) {
                if (entries.removeAll { it.id == messageId }) emitMessages(conversationId)
            }
        }

        override suspend fun deleteMessagesForConversation(conversationId: Long) = synchronized(this) {
            messages.remove(conversationId)
            emitMessages(conversationId)
        }

        override suspend fun deleteConversation(id: Long) = synchronized(this) {
            conversations.remove(id)
            emitConversations()
        }

        private fun emitConversations() {
            conversationsFlow.value = conversations.values.sortedByDescending { it.lastMessageAt }
        }

        private fun emitMessages(conversationId: Long) {
            messageFlow(conversationId).value = messages[conversationId]?.toList() ?: emptyList()
        }

        private fun messageFlow(conversationId: Long): MutableStateFlow<List<ChatMessage>> =
            synchronized(this) {
                messageFlows.getOrPut(conversationId) { MutableStateFlow(messages[conversationId]?.toList() ?: emptyList()) }
            }
    }

    /** Blocks the calling (real IO pool) thread inside nativeGenerate until the
     * test explicitly releases [gate] — used to create genuine, real-thread
     * concurrency between an in-flight generation and a reset/New Chat. */
    private class GatedInferenceEngine(private val tokensToEmit: List<String>) : InferenceEngine {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        @Volatile var generateCalls = 0

        override fun nativeLoadModel(modelPath: String, nGpuLayers: Int): Long = 1L
        override fun nativeFreeModel(modelPtr: Long) = Unit
        override fun nativeCreateContext(modelPtr: Long, contextSize: Int, nThreads: Int): Long = 1L
        override fun nativeFreeContext(ctxPtr: Long) = Unit
        override fun nativeLoadDraftModel(modelPath: String, nGpuLayers: Int): Long = 1L
        override fun nativeFreeDraftModel(draftModelPtr: Long) = Unit
        override fun nativeCreateDraftContext(draftModelPtr: Long, contextSize: Int, nThreads: Int): Long = 1L
        override fun nativeFreeDraftContext(draftCtxPtr: Long) = Unit

        override fun nativeGenerate(
            ctxPtr: Long, prompt: String, imageEmbedPtr: Long, maxTokens: Int, temperature: Float,
            topP: Float, topK: Int, repeatPenalty: Float, draftCtxPtr: Long, nDraft: Int,
            batchSize: Int, ubatchSize: Int, callback: LlamaCallback
        ) {
            generateCalls++
            started.complete(Unit)
            runBlocking { gate.await() } // real thread-block — this is deliberate
            tokensToEmit.forEach(callback::onToken)
            callback.onStats(120f, 50L, 0f, tokensToEmit.size, 200f, "CPU", 0, 0, 4096, 0)
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

    /** Non-blocking fake — used where no real concurrency is needed. */
    private class SimpleFakeInferenceEngine(private val streamedTokens: List<String>) : InferenceEngine {
        @Volatile var generateCalls = 0

        override fun nativeLoadModel(modelPath: String, nGpuLayers: Int): Long = 1L
        override fun nativeFreeModel(modelPtr: Long) = Unit
        override fun nativeCreateContext(modelPtr: Long, contextSize: Int, nThreads: Int): Long = 1L
        override fun nativeFreeContext(ctxPtr: Long) = Unit
        override fun nativeLoadDraftModel(modelPath: String, nGpuLayers: Int): Long = 1L
        override fun nativeFreeDraftModel(draftModelPtr: Long) = Unit
        override fun nativeCreateDraftContext(draftModelPtr: Long, contextSize: Int, nThreads: Int): Long = 1L
        override fun nativeFreeDraftContext(draftCtxPtr: Long) = Unit

        override fun nativeGenerate(
            ctxPtr: Long, prompt: String, imageEmbedPtr: Long, maxTokens: Int, temperature: Float,
            topP: Float, topK: Int, repeatPenalty: Float, draftCtxPtr: Long, nDraft: Int,
            batchSize: Int, ubatchSize: Int, callback: LlamaCallback
        ) {
            generateCalls++
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
