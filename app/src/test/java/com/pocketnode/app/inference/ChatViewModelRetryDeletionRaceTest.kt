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
 * Regression coverage for the retryInterrupted() delete-before-verify race
 * (Codex pass-4 MEDIUM finding): the interrupted assistant row must never be
 * removed until the resend has minted its own request token under the
 * conversation's current binding, i.e. is guaranteed to proceed. Losing the
 * binding race, or failing to start for any other reason, must leave the
 * fragment intact rather than discarding it with no replacement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelRetryDeletionRaceTest {
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

    private suspend fun makeInterrupted(
        viewModel: ChatViewModel,
        repository: ChatRepository,
        conversationId: Long,
        prompt: String
    ): ChatMessage {
        viewModel.setStopRequestedForTesting(true)
        viewModel.sendMessageInternal(text = prompt, conversationId = conversationId, template = PromptTemplate.ChatML)
        viewModel.setStopRequestedForTesting(false)
        return repository.getMessagesSnapshot(conversationId).last { it.role == "assistant" && it.interrupted }
    }

    // 1. Binding changes (a different conversation's bind wins) after
    // retryInterrupted resolves the prompt/binding, but before the resend's
    // own sendMessageInternal call can mint its request token. The fragment
    // must survive untouched.
    @Test
    fun bindingChangesBeforeRequestTokenCreation_leavesFragmentIntact() = runBlocking {
        val inner = InMemoryChatDao()
        val dao = GatingByCallNumberChatDao(inner, pauseForId = 101L, pauseOnCallNumber = 2)
        val repository = ChatRepository(dao)
        val engine = SimpleFakeInferenceEngine(streamedTokens = listOf("answer"))
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)

        viewModel.bindConversation(101L)
        waitUntil { inner.getConversationById(101L) != null }
        val interrupted = makeInterrupted(viewModel, repository, 101L, "q1")

        // retryInterrupted's own ensureBoundToConversation call is the dao's
        // 1st getConversationById(101) call inside this retry attempt's
        // lifetime is already past (bindConversation + priming send already
        // consumed earlier call numbers on a fresh gate, so reset the gate
        // right before triggering retry to target sendMessageInternal's call
        // specifically).
        // Call #1 (within this arm) is retryInterrupted's own
        // ensureBoundToConversation check; call #2 is the resend's own
        // ensureBoundToConversation inside sendMessageInternal — pause there.
        dao.rearm(pauseOnCallNumber = 2)
        viewModel.retryInterrupted(101L, interrupted.id)
        dao.reachedPause.await()

        // Another conversation's bind wins the race while the resend is
        // stalled resolving its own binding.
        viewModel.bindConversation(102L)
        waitUntil { inner.getConversationById(102L) != null }

        dao.resumeGate.complete(Unit)
        delay(100)

        val messages = repository.getMessagesSnapshot(101L)
        assertTrue(
            "the interrupted fragment must still exist — the resend never got to start",
            messages.any { it.id == interrupted.id && it.interrupted }
        )
        assertEquals("no duplicate user turn must be created", 1, messages.count { it.role == "user" })
    }

    // 2. Binding changes after the resend has already minted its request
    // token (i.e. after the commit point) — the deletion has already
    // happened and must not be rolled back; the resend proceeds correctly.
    @Test
    fun bindingChangesAfterRequestTokenCreation_doesNotUndoOrCorruptDeletion() = runBlocking {
        val inner = InMemoryChatDao()
        val dao = GatingDeleteChatDao(inner, pauseForId = 201L)
        val repository = ChatRepository(dao)
        val engine = SimpleFakeInferenceEngine(streamedTokens = listOf("second answer"))
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)

        viewModel.bindConversation(201L)
        waitUntil { inner.getConversationById(201L) != null }
        val interrupted = makeInterrupted(viewModel, repository, 201L, "q1")

        viewModel.retryInterrupted(201L, interrupted.id)
        dao.reachedPause.await() // token already minted; about to delete the interrupted row

        // The SAME conversation's binding is bumped again (e.g. a rebind
        // triggered elsewhere in the UI) in the gap between minting the
        // request token and deleting the fragment. Since the identity
        // (sessionId/generation) is unchanged, this must not disturb the
        // already-committed resend or its pending deletion.
        viewModel.bindConversation(201L)
        delay(30)

        dao.resumeGate.complete(Unit)
        waitUntil {
            repository.getMessagesSnapshot(201L).any { it.role == "assistant" && !it.interrupted && it.content == "second answer" }
        }

        val messages = repository.getMessagesSnapshot(201L)
        assertFalse("the interrupted fragment must be gone", messages.any { it.id == interrupted.id })
        assertEquals(
            "exactly one assistant message (the replacement) must remain — no duplication from the race",
            1,
            messages.count { it.role == "assistant" }
        )
        assertEquals("no duplicate user turn", 1, messages.count { it.role == "user" })
    }

    // 3. Retry initialization failure (no model loaded) must leave the
    // fragment intact and must not duplicate the user turn.
    @Test
    fun retryInitializationFailure_leavesFragmentIntact() = runBlocking {
        val inner = InMemoryChatDao()
        val repository = ChatRepository(inner)
        val engine = SimpleFakeInferenceEngine(streamedTokens = listOf("answer"))
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)

        viewModel.bindConversation(301L)
        waitUntil { inner.getConversationById(301L) != null }
        val interrupted = makeInterrupted(viewModel, repository, 301L, "q1")

        // Simulate model becoming unavailable before the retry resend runs.
        viewModel.setLoadedContextForTesting(modelPtr = 0L, contextPtr = 0L)

        viewModel.retryInterrupted(301L, interrupted.id)
        delay(100)

        val messages = repository.getMessagesSnapshot(301L)
        assertTrue(
            "the interrupted fragment must survive a failed resend init",
            messages.any { it.id == interrupted.id && it.interrupted }
        )
        assertEquals("no duplicate user turn", 1, messages.count { it.role == "user" })
        assertFalse("isGenerating must not be stuck true", viewModel.isGenerating.value)
    }

    // 4. Successful retry removes only the selected interrupted fragment,
    // not other interrupted fragments in the same conversation.
    @Test
    fun successfulRetry_removesOnlyTheSelectedFragment() = runBlocking {
        val inner = InMemoryChatDao()
        val repository = ChatRepository(inner)
        val engine = SimpleFakeInferenceEngine(streamedTokens = listOf("fresh answer"))
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)

        viewModel.bindConversation(401L)
        waitUntil { inner.getConversationById(401L) != null }
        val interruptedOne = makeInterrupted(viewModel, repository, 401L, "q1")
        val interruptedTwo = makeInterrupted(viewModel, repository, 401L, "q2")

        viewModel.retryInterrupted(401L, interruptedOne.id)
        waitUntil {
            repository.getMessagesSnapshot(401L).any { it.role == "assistant" && !it.interrupted && it.content == "fresh answer" }
        }

        val messages = repository.getMessagesSnapshot(401L)
        assertFalse("the retried fragment must be gone", messages.any { it.id == interruptedOne.id })
        assertTrue(
            "the OTHER interrupted fragment must be untouched",
            messages.any { it.id == interruptedTwo.id && it.interrupted }
        )
        assertTrue(
            "a fresh completed answer for the retried turn must exist",
            messages.any { it.role == "assistant" && !it.interrupted && it.content == "fresh answer" }
        )
    }

    // 5. No duplicated user message across a full retry cycle.
    @Test
    fun retry_neverDuplicatesTheUserMessage() = runBlocking {
        val inner = InMemoryChatDao()
        val repository = ChatRepository(inner)
        val engine = SimpleFakeInferenceEngine(streamedTokens = listOf("answer"))
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)

        viewModel.bindConversation(501L)
        waitUntil { inner.getConversationById(501L) != null }
        val interrupted = makeInterrupted(viewModel, repository, 501L, "only prompt")

        viewModel.retryInterrupted(501L, interrupted.id)
        waitUntil {
            repository.getMessagesSnapshot(501L).any { it.role == "assistant" && !it.interrupted && it.content == "answer" }
        }

        val userMessages = repository.getMessagesSnapshot(501L).filter { it.role == "user" }
        assertEquals(1, userMessages.size)
        assertEquals("only prompt", userMessages.single().content)
    }

    // 6. Retry uses a fresh request sequence: the original (interrupted)
    // request's late callback must not be able to write into the new
    // completed message, and a genuinely new generation call must occur.
    @Test
    fun retry_usesANewRequestSequence_originalRequestCannotWriteAfterIt() = runBlocking {
        val inner = InMemoryChatDao()
        val repository = ChatRepository(inner)
        val engine = SimpleFakeInferenceEngine(streamedTokens = listOf("new response"))
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)

        viewModel.bindConversation(601L)
        waitUntil { inner.getConversationById(601L) != null }
        val interrupted = makeInterrupted(viewModel, repository, 601L, "q1")
        assertEquals(1, engine.generateCalls)

        viewModel.retryInterrupted(601L, interrupted.id)
        waitUntil { engine.generateCalls >= 2 }
        waitUntil {
            repository.getMessagesSnapshot(601L).any { it.role == "assistant" && !it.interrupted && it.content == "new response" }
        }

        assertEquals("a genuinely new generate() call must have run", 2, engine.generateCalls)
        val assistantMessages = repository.getMessagesSnapshot(601L).filter { it.role == "assistant" }
        assertEquals(1, assistantMessages.size)
        assertEquals("new response", assistantMessages.single().content)
        assertFalse(assistantMessages.single().interrupted)
    }

    /** Pauses getConversationById for a specific id on a specific 1-indexed
     * call number for that id, then can be re-armed to pause again on a new
     * call number — lets a test target exactly one of several calls made
     * against the same conversation id over a call's lifetime. */
    private class GatingByCallNumberChatDao(
        private val delegate: ChatDao,
        private val pauseForId: Long,
        pauseOnCallNumber: Int
    ) : ChatDao by delegate {
        @Volatile private var targetCallNumber = pauseOnCallNumber
        @Volatile private var callCount = 0
        @Volatile var reachedPause = CompletableDeferred<Unit>()
        @Volatile var resumeGate = CompletableDeferred<Unit>()
        @Volatile private var firedForCurrentArm = false

        fun rearm(pauseOnCallNumber: Int) {
            targetCallNumber = pauseOnCallNumber
            callCount = 0
            firedForCurrentArm = false
            reachedPause = CompletableDeferred()
            resumeGate = CompletableDeferred()
        }

        override suspend fun getConversationById(id: Long): Conversation? {
            if (id == pauseForId) {
                callCount++
                if (callCount == targetCallNumber && !firedForCurrentArm) {
                    firedForCurrentArm = true
                    reachedPause.complete(Unit)
                    resumeGate.await()
                }
            }
            return delegate.getConversationById(id)
        }
    }

    /** Pauses deleteMessageById exactly once for a given conversation's
     * interrupted-row deletion, so a test can race a competing bind against
     * the window right after the resend's request token has been minted. */
    private class GatingDeleteChatDao(
        private val delegate: ChatDao,
        private val pauseForId: Long
    ) : ChatDao by delegate {
        val reachedPause = CompletableDeferred<Unit>()
        val resumeGate = CompletableDeferred<Unit>()
        private var pausedOnce = false

        override suspend fun deleteMessageById(messageId: Long) {
            if (!pausedOnce) {
                pausedOnce = true
                reachedPause.complete(Unit)
                resumeGate.await()
            }
            delegate.deleteMessageById(messageId)
        }
    }

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
}
