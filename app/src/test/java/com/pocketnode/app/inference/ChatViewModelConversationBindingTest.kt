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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression coverage for the conversation-binding architecture: every
 * session-mutating operation (Send, Reset, New Chat, Retry, model/backend
 * change) must reconcile against a single immutable (conversationId,
 * sessionId, generation, bindVersion) snapshot rather than trusting
 * activeConversationId/sessionManager's ambient state independently. A
 * gated fake DAO pauses a specific conversation's lookup on a real IO thread
 * so a bind can genuinely be caught mid-flight by another operation, rather
 * than simulating the race synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelConversationBindingTest {
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

    // 1. Bind A active; Bind B begins and suspends before restore; Send is
    // triggered for B. Send must establish/use only B's own session identity.
    @Test
    fun sendDuringConcurrentBind_neverUsesPreviousConversationsSessionIdentity() = runBlocking {
        val inner = InMemoryChatDao()
        val dao = GatingConversationLookupChatDao(inner, pauseForId = 911L)
        val repository = ChatRepository(dao)
        val engine = SimpleFakeInferenceEngine(streamedTokens = listOf("answer"))
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)

        viewModel.bindConversation(910L)
        waitUntil { inner.getConversationById(910L) != null }
        val sessionABefore = inner.getConversationById(910L)!!.sessionUuid

        viewModel.bindConversation(911L) // begins switching; its lookup pauses
        dao.reachedPause.await()

        viewModel.sendMessageInternal(text = "hello", conversationId = 911L, template = PromptTemplate.ChatML)
        waitUntil { !viewModel.isGenerating.value }

        dao.resumeGate.complete(Unit) // let the stalled bind(911) finish (must be a no-op)
        delay(50)

        val convoA = inner.getConversationById(910L)!!
        val convoB = inner.getConversationById(911L)!!
        assertEquals("conversation A's identity must be untouched by a send meant for B", sessionABefore, convoA.sessionUuid)
        assertTrue("conversation B must have its own distinct session identity, not A's", convoB.sessionUuid != sessionABefore)
        val assistantMessages = repository.getMessagesSnapshot(911L).filter { it.role == "assistant" }
        assertTrue("the send must complete and be saved under conversation B", assistantMessages.any { it.content == "answer" })
    }

    // 2. Bind A suspends; Bind B (started later) completes; Bind A resumes.
    // B remains active; A's late completion cannot overwrite it.
    @Test
    fun lateResumingOlderBind_cannotOverwriteANewerCompletedBind() = runBlocking {
        val inner = InMemoryChatDao()
        val dao = GatingConversationLookupChatDao(inner, pauseForId = 921L)
        val repository = ChatRepository(dao)
        val engine = SimpleFakeInferenceEngine(streamedTokens = emptyList())
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )

        viewModel.bindConversation(921L) // A, suspends before restoring
        dao.reachedPause.await()

        viewModel.bindConversation(922L) // B, started later, runs to completion
        waitUntil { inner.getConversationById(922L) != null }
        waitUntil { viewModel.sessionSnapshotForTesting().sessionId == inner.getConversationById(922L)!!.sessionUuid }

        dao.resumeGate.complete(Unit) // let A resume and finish late
        delay(50)

        assertEquals(
            "B must remain the active session even after A's late completion",
            inner.getConversationById(922L)!!.sessionUuid,
            viewModel.sessionSnapshotForTesting().sessionId
        )
    }

    // 3. Reset Context during a bind switch — only the conversation actually
    // bound at that instant has its generation bumped.
    @Test
    fun resetContext_duringConcurrentBindSwitch_onlyBumpsIntendedConversation() = runBlocking {
        val inner = InMemoryChatDao()
        val dao = GatingConversationLookupChatDao(inner, pauseForId = 932L)
        val repository = ChatRepository(dao)
        val engine = SimpleFakeInferenceEngine(streamedTokens = emptyList())
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )

        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)
        viewModel.bindConversation(931L)
        waitUntil { inner.getConversationById(931L) != null }
        // Prime the session with real activity first — resetSession() only
        // bumps the generation for a session that's actually "dirty" (had a
        // request), matching SessionManager's idempotent-no-op-on-fresh-bind
        // contract.
        viewModel.sendMessageInternal(text = "prime", conversationId = 931L, template = PromptTemplate.ChatML)
        waitUntil { !viewModel.isGenerating.value }
        val convoABefore = inner.getConversationById(931L)!!

        viewModel.bindConversation(932L) // switching away; pauses
        dao.reachedPause.await()

        viewModel.resetSessionContext(931L) // targets the conversation still actually bound
        waitUntil { inner.getConversationById(931L)!!.generation > convoABefore.generation }

        dao.resumeGate.complete(Unit)
        waitUntil { inner.getConversationById(932L) != null }
        delay(50)

        val convoAAfter = inner.getConversationById(931L)!!
        val convoBAfter = inner.getConversationById(932L)!!
        assertEquals(convoABefore.generation + 1, convoAAfter.generation)
        assertEquals("932 must never receive a generation bump intended for 931", 0, convoBAfter.generation)
    }

    // 4. New Chat during a bind switch — old binding is atomically detached
    // and no identity leaks between the two conversations.
    @Test
    fun newChat_racingConcurrentBindToAnotherConversation_neverMixesIdentities() = runBlocking {
        val inner = InMemoryChatDao()
        val dao = GatingConversationLookupChatDao(inner, pauseForId = 942L)
        val repository = ChatRepository(dao)
        val engine = SimpleFakeInferenceEngine(streamedTokens = emptyList())
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )

        viewModel.bindConversation(941L)
        waitUntil { inner.getConversationById(941L) != null }

        viewModel.bindConversation(942L) // pauses
        dao.reachedPause.await()

        viewModel.clearChat(941L) // New Chat on the conversation still actually bound
        waitUntil { !viewModel.isGenerating.value }

        dao.resumeGate.complete(Unit)
        waitUntil { inner.getConversationById(942L) != null }
        delay(50)

        assertTrue(
            "941's row must be gone (New Chat deletes it) and never resurrected with 942's identity",
            inner.getConversationById(941L) == null
        )
        val convo942 = inner.getConversationById(942L)!!
        assertTrue("942 must have its own valid, uncorrupted identity", convo942.sessionUuid.isNotBlank())
    }

    // 5. Retrying an interrupted message while another conversation is
    // binding must never mix that other conversation's session in.
    @Test
    fun retryInterrupted_duringConcurrentBindToAnotherConversation_keepsItsOwnSession() = runBlocking {
        val inner = InMemoryChatDao()
        val dao = GatingConversationLookupChatDao(inner, pauseForId = 952L)
        val repository = ChatRepository(dao)
        val inference = SimpleFakeInferenceEngine(streamedTokens = listOf("answer"))
        val viewModel = ChatViewModel(
            inference = inference, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)

        viewModel.bindConversation(951L)
        waitUntil { inner.getConversationById(951L) != null }
        viewModel.setStopRequestedForTesting(true)
        viewModel.sendMessageInternal(text = "q1", conversationId = 951L, template = PromptTemplate.ChatML)
        viewModel.setStopRequestedForTesting(false)
        val interrupted = repository.getMessagesSnapshot(951L).first { it.role == "assistant" }
        assertTrue(interrupted.interrupted)

        viewModel.bindConversation(952L) // switching away; pauses
        dao.reachedPause.await()

        viewModel.retryInterrupted(951L, interrupted.id)
        waitUntil { inference.generateCalls >= 2 }
        waitUntil { repository.getMessagesSnapshot(951L).any { it.role == "assistant" && !it.interrupted } }

        dao.resumeGate.complete(Unit)
        waitUntil { inner.getConversationById(952L) != null }
        delay(50)

        val convo951 = inner.getConversationById(951L)!!
        val convo952 = inner.getConversationById(952L)!!
        assertTrue("951 and 952 must never share a session identity", convo951.sessionUuid != convo952.sessionUuid)
        assertEquals(
            "retry must not duplicate the user turn in 951",
            1,
            repository.getMessagesSnapshot(951L).count { it.role == "user" }
        )
    }

    // 6. Rapid A -> B -> C binds with delayed completion order C, A, B: C
    // (the most recently *started* bind) remains authoritative regardless of
    // completion order.
    @Test
    fun rapidTripleBind_mostRecentlyStartedBindRemainsAuthoritative() = runBlocking {
        val inner = InMemoryChatDao()
        val dao = MultiGatingConversationLookupChatDao(inner, pauseForIds = setOf(961L, 962L))
        val repository = ChatRepository(dao)
        val engine = SimpleFakeInferenceEngine(streamedTokens = emptyList())
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )

        viewModel.bindConversation(961L) // A, stuck
        dao.reached(961L).await()
        viewModel.bindConversation(962L) // B, stuck
        dao.reached(962L).await()
        viewModel.bindConversation(963L) // C, unblocked — completes first despite starting last
        waitUntil { inner.getConversationById(963L) != null }
        waitUntil { viewModel.sessionSnapshotForTesting().sessionId == inner.getConversationById(963L)!!.sessionUuid }

        // Resume in completion order C(already done), A, B.
        dao.resume(961L)
        delay(50)
        dao.resume(962L)
        delay(50)

        assertEquals(
            "C must remain authoritative no matter what order A and B finish resuming in",
            inner.getConversationById(963L)!!.sessionUuid,
            viewModel.sessionSnapshotForTesting().sessionId
        )
    }

    // 7. A model/backend fingerprint change detected during a Send must only
    // bump the generation of the conversation that Send actually targets,
    // even while another conversation's bind is in flight.
    @Test
    fun modelBackendChange_duringConcurrentBindToAnotherConversation_onlyAffectsIntendedConversation() = runBlocking {
        val inner = InMemoryChatDao()
        val dao = GatingConversationLookupChatDao(inner, pauseForId = 972L)
        val repository = ChatRepository(dao)
        val engine = SimpleFakeInferenceEngine(streamedTokens = listOf("ok"))
        val viewModel = ChatViewModel(
            inference = engine, repository = repository, app = MainApplication(),
            healthSummaryOverride = { "test-health" }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L, backend = "CPU")

        viewModel.bindConversation(971L)
        waitUntil { inner.getConversationById(971L) != null }
        // Prime a baseline backend fingerprint first — onModelOrBackendChanged
        // only reports a change relative to a previously observed fingerprint,
        // so the very first send never counts as a "change" regardless of
        // the backend it used.
        viewModel.sendMessageInternal(text = "prime", conversationId = 971L, template = PromptTemplate.ChatML)
        waitUntil { !viewModel.isGenerating.value }
        val convo971Before = inner.getConversationById(971L)!!

        viewModel.bindConversation(972L) // pauses
        dao.reachedPause.await()

        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L, backend = "OpenCL")
        viewModel.sendMessageInternal(text = "hi", conversationId = 971L, template = PromptTemplate.ChatML)
        waitUntil { !viewModel.isGenerating.value }

        dao.resumeGate.complete(Unit)
        waitUntil { inner.getConversationById(972L) != null }
        delay(50)

        val convo971After = inner.getConversationById(971L)!!
        val convo972After = inner.getConversationById(972L)!!
        assertTrue("the fingerprint-change bump must land on 971", convo971After.generation > convo971Before.generation)
        assertEquals("972 must be untouched by 971's fingerprint-change bump", 0, convo972After.generation)
    }

    /** Pauses getConversationById for one specific conversation id, exactly
     * once, so a test can land a concurrent operation in the gap before a
     * bind/operation reaches its session-restore step. */
    private class GatingConversationLookupChatDao(
        private val delegate: ChatDao,
        private val pauseForId: Long
    ) : ChatDao by delegate {
        val reachedPause = CompletableDeferred<Unit>()
        val resumeGate = CompletableDeferred<Unit>()
        private var pausedOnce = false

        override suspend fun getConversationById(id: Long): Conversation? {
            if (id == pauseForId && !pausedOnce) {
                pausedOnce = true
                reachedPause.complete(Unit)
                resumeGate.await()
            }
            return delegate.getConversationById(id)
        }
    }

    /** Same as [GatingConversationLookupChatDao] but supports pausing several
     * distinct conversation ids independently, each exactly once. */
    private class MultiGatingConversationLookupChatDao(
        private val delegate: ChatDao,
        private val pauseForIds: Set<Long>
    ) : ChatDao by delegate {
        private val reachedMap = pauseForIds.associateWith { CompletableDeferred<Unit>() }
        private val resumeMap = pauseForIds.associateWith { CompletableDeferred<Unit>() }
        private val pausedOnce = mutableSetOf<Long>()

        fun reached(id: Long) = reachedMap.getValue(id)
        fun resume(id: Long) {
            resumeMap.getValue(id).complete(Unit)
        }

        override suspend fun getConversationById(id: Long): Conversation? {
            if (id in pauseForIds && id !in pausedOnce) {
                pausedOnce += id
                reachedMap.getValue(id).complete(Unit)
                resumeMap.getValue(id).await()
            }
            return delegate.getConversationById(id)
        }
    }

    /** Non-blocking fake — no real thread-block needed for these tests since
     * the race is over conversation binding, not over an in-flight generate. */
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
