package com.pocketnode.app.inference

import com.pocketnode.app.MainApplication
import com.pocketnode.app.data.ChatDao
import com.pocketnode.app.data.ChatRepository
import com.pocketnode.app.data.HashUtils
import com.pocketnode.app.data.VerificationStatus
import com.pocketnode.app.data.model.ChatMessage
import com.pocketnode.app.data.model.Conversation
import com.pocketnode.app.data.model.LocalModel
import com.pocketnode.app.data.model.ModelRole
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPromptGroundingTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sendMessagePassesGroundedPromptToNativeGenerateAndKeepsStoredUserMessageRaw() {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = FakeInferenceEngine()
        val app = MainApplication()
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = app,
            groundingClock = fixedClock(),
            healthSummaryOverride = { HEALTH_SUMMARY }
        )

        viewModel.setLoadedContextForTesting(
            modelPtr = 1L,
            contextPtr = 1L,
            modelName = "PocketNode_Operator_Q4_0",
            backend = "OpenCL"
        )

        runBlocking {
            viewModel.sendMessageInternal(
                text = "What date is today?",
                conversationId = 101L,
                systemPrompt = "You are helpful.",
                template = PromptTemplate.ChatML
            )
        }
        val prompt = inference.lastPrompt
        assertNotNull(prompt)
        requireNotNull(prompt)

        assertTrue(prompt.contains("You are helpful."))
        assertTrue(prompt.contains("You are Pocket Node."))
        assertTrue(prompt.contains("<POCKET_NODE_CONTEXT>"))
        assertTrue(prompt.contains("Current device timezone: America/New_York"))
        assertTrue(prompt.contains("Deterministic date fact: On this device, today is Wednesday, June 3, 2026."))
        assertTrue(prompt.contains("Pocket Node local health: $HEALTH_SUMMARY"))
        assertTrue(prompt.contains("<|im_start|>user\nWhat date is today?<|im_end|>"))
        assertFalse(prompt.contains("Grounding facts for this turn:"))
        assertFalse(prompt.contains("User message:\nWhat date is today?"))

        val storedMessages = runBlocking { repository.getMessagesSnapshot(101L) }
        val storedUser = storedMessages.first { it.role == "user" }
        assertEquals("What date is today?", storedUser.content)
        assertFalse(storedUser.content.contains("Grounding facts"))
    }

    @Test
    fun sendMessageInjectsUnknownWhenServiceStateIsUnavailable() {
        val viewModel = newViewModel()
        runBlocking {
            viewModel.second.sendMessageInternal(
                text = "Is Moolah online?",
                conversationId = 202L,
                template = PromptTemplate.ChatML
            )
        }
        val prompt = requireNotNull(viewModel.first.lastPrompt)

        assertTrue(prompt.contains("Injected service-state snapshot: unavailable"))
        assertTrue(prompt.contains("Requested live status: Moolah = UNKNOWN because no service-state data was injected for this turn."))
        assertTrue(prompt.contains("Do not guess from Pocket Node local health."))
        assertTrue(prompt.contains("<|im_start|>user\nIs Moolah online?<|im_end|>"))
    }

    @Test
    fun sendMessageInjectsDeterministicUsFathersDayFact() {
        val viewModel = newViewModel()
        runBlocking {
            viewModel.second.sendMessageInternal(
                text = "When is Father's Day 2026?",
                conversationId = 303L,
                template = PromptTemplate.ChatML
            )
        }
        val prompt = requireNotNull(viewModel.first.lastPrompt)

        assertTrue(prompt.contains("Deterministic calendar fact: U.S. Father's Day 2026 is Sunday, June 21, 2026."))
        assertTrue(prompt.contains("<|im_start|>user\nWhen is Father's Day 2026?<|im_end|>"))
    }

    @Test
    fun loadModelBlocksDraftRecordFromPrimaryChatSlot() {
        val tempModel = createTempGguf("SmolLM2-135M-Instruct-Q4_0")
        val inference = FakeInferenceEngine()
        val repository = ChatRepository(InMemoryChatDao())
        val draftRecord = LocalModel(
            id = "draft-1",
            name = "SmolLM2 135M Draft (Q4_0)",
            path = tempModel.absolutePath,
            contextLength = 4096,
            role = ModelRole.DRAFT.name,
            sizeBytes = tempModel.length()
        )
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = MainApplication(),
            resolveModelRecordOverride = { draftRecord },
            availableMemoryBytesOverride = { Long.MAX_VALUE }
        )

        viewModel.loadModel(tempModel.absolutePath)
        waitFor { viewModel.modelError.value != null }

        assertEquals(
            "Draft model selected for primary chat: SmolLM2 135M Draft (Q4_0). Choose a main model from Chat Models.",
            viewModel.modelError.value
        )
        assertEquals(0, inference.loadModelCalls)
    }

    @Test
    fun loadModelBlocksFailedPrimaryModelBeforeNativeLoad() {
        val tempModel = createTempGguf("PocketNode_Operator_Q4_0")
        val inference = FakeInferenceEngine()
        val repository = ChatRepository(InMemoryChatDao())
        val failedRecord = LocalModel(
            id = "main-1",
            name = "PocketNode_Operator_Q4_0",
            path = tempModel.absolutePath,
            contextLength = 4096,
            role = ModelRole.MAIN.name,
            sizeBytes = tempModel.length(),
            verificationStatus = VerificationStatus.FAILED
        )
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = MainApplication(),
            resolveModelRecordOverride = { failedRecord },
            availableMemoryBytesOverride = { Long.MAX_VALUE }
        )

        viewModel.loadModel(tempModel.absolutePath)
        waitFor { viewModel.modelError.value != null }

        assertEquals(
            "Primary model failed integrity verification: PocketNode_Operator_Q4_0. Re-download or re-import the correct GGUF before chatting.",
            viewModel.modelError.value
        )
        assertEquals(0, inference.loadModelCalls)
    }

    @Test
    fun metadataMismatchBlocksGenerationForPrimaryModel() {
        val tempModel = createTempGguf("PocketNode_Operator_Q4_0")
        val inference = FakeInferenceEngine().apply {
            modelMetadata = arrayOf("llama", "Smollm2 135M 8k Lc100K Mix1 Ep2", "gpt2", "49152", "chatml")
        }
        val repository = ChatRepository(InMemoryChatDao())
        val operatorRecord = LocalModel(
            id = "main-2",
            name = "PocketNode_Operator_Q4_0",
            path = tempModel.absolutePath,
            contextLength = 4096,
            role = ModelRole.MAIN.name,
            sizeBytes = tempModel.length(),
            sha256 = HashUtils.KNOWN_HASHES.getValue("PocketNode_Operator_Q4_0"),
            verificationStatus = VerificationStatus.VERIFIED
        )
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = MainApplication(),
            resolveModelRecordOverride = { operatorRecord },
            availableMemoryBytesOverride = { Long.MAX_VALUE }
        )

        viewModel.loadModel(tempModel.absolutePath)
        waitFor { viewModel.modelError.value != null }

        assertTrue(
            viewModel.modelError.value!!.contains(
                "Loaded model metadata does not match the selected primary model."
            )
        )
        assertFalse(viewModel.isModelReady.value)
        assertNull(viewModel.mainModelMetadata.value)

        runBlocking {
            viewModel.sendMessageInternal(
                text = "What date is today?",
                conversationId = 404L,
                template = PromptTemplate.ChatML
            )
        }
        assertEquals(0, inference.generateCalls)
    }

    @Test
    fun metadataMatchAllowsVerifiedBaselinePrimaryModelToGenerate() {
        val tempModel = createTempGguf("Llama-3.2-3B-Instruct-Q4")
        val inference = FakeInferenceEngine().apply {
            modelMetadata = arrayOf("llama", "Llama 3.2 3B Instruct", "bpe", "128256", "chatml")
        }
        val repository = ChatRepository(InMemoryChatDao())
        val baselineRecord = LocalModel(
            id = "baseline-main",
            name = "Llama 3.2 3B Instruct Q4",
            path = tempModel.absolutePath,
            contextLength = 4096,
            role = ModelRole.MAIN.name,
            sizeBytes = tempModel.length(),
            verificationStatus = VerificationStatus.UNKNOWN_HASH
        )
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = MainApplication(),
            groundingClock = fixedClock(),
            healthSummaryOverride = { HEALTH_SUMMARY },
            resolveModelRecordOverride = { baselineRecord },
            availableMemoryBytesOverride = { Long.MAX_VALUE }
        )

        viewModel.loadModel(tempModel.absolutePath)
        waitFor { viewModel.isModelReady.value }

        assertNull(viewModel.modelError.value)
        assertTrue(viewModel.isModelReady.value)

        runBlocking {
            viewModel.sendMessageInternal(
                text = "Tiny prompt",
                conversationId = 505L,
                template = PromptTemplate.ChatML
            )
        }

        assertEquals(1, inference.loadModelCalls)
        assertEquals(1, inference.generateCalls)
        assertTrue(requireNotNull(inference.lastPrompt).contains("You are Pocket Node."))
    }

    @Test
    fun sendMessageSanitizesEchoedGroundingBlockFromVisibleAssistantMessage() {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = FakeInferenceEngine().apply {
            streamedTokens = listOf(
                "Grounding facts for this turn:\n",
                "Current device date/time: Wednesday, June 3, 2026 9:54 PM EDT\n",
                "Pocket Node local health: $HEALTH_SUMMARY\n"
            )
        }
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = MainApplication(),
            groundingClock = fixedClock(),
            healthSummaryOverride = { HEALTH_SUMMARY }
        )

        viewModel.setLoadedContextForTesting(
            modelPtr = 1L,
            contextPtr = 1L,
            modelName = "PocketNode_Operator_Q4_0",
            backend = "OpenCL"
        )

        runBlocking {
            viewModel.sendMessageInternal(
                text = "What date is today?",
                conversationId = 606L,
                template = PromptTemplate.ChatML
            )
        }

        val storedMessages = runBlocking { repository.getMessagesSnapshot(606L) }
        val assistant = storedMessages.last { it.role == "assistant" }

        assertEquals(
            "I had an internal prompt-formatting error on that turn. Please resend your message.",
            assistant.content
        )
        assertEquals("", viewModel.visibleAssistantMessage.value)
    }

    // ── Auto template resolution integration tests ────────────────────────────

    @Test
    fun autoTemplateWithSmolLm3PocketNodeMetadataUsesLlama3Format() {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = FakeInferenceEngine()
        val app = MainApplication()
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = app,
            groundingClock = fixedClock(),
            healthSummaryOverride = { HEALTH_SUMMARY }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)
        viewModel.mainModelMetadata.value = ModelMetadata(
            architecture = "smollm3",
            name = "PocketNode_Operator_BF16_Fresh",
            tokenizerModel = "gpt2",
            vocabSize = 128256,
            chatTemplate = ""
        )

        runBlocking {
            viewModel.sendMessageInternal(
                text = "Tiny prompt",
                conversationId = 800L,
                template = PromptTemplate.Auto
            )
        }

        val prompt = requireNotNull(inference.lastPrompt)
        // Llama 3 format markers must be present
        assertTrue(prompt.contains("<|start_header_id|>user<|end_header_id|>"))
        assertTrue(prompt.contains("<|eot_id|>"))
        // ChatML markers must NOT appear (would mean wrong template was used)
        assertFalse(prompt.contains("<|im_start|>user"))
        assertFalse(prompt.contains("<|im_end|>"))
    }

    @Test
    fun autoTemplateWithQwenArchUsesChatMLFormat() {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = FakeInferenceEngine()
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = MainApplication(),
            groundingClock = fixedClock(),
            healthSummaryOverride = { HEALTH_SUMMARY }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)
        viewModel.mainModelMetadata.value = ModelMetadata(
            architecture = "qwen2",
            name = "Qwen 2.5 7B Instruct",
            tokenizerModel = "gpt2",
            vocabSize = 151936,
            chatTemplate = ""
        )

        runBlocking {
            viewModel.sendMessageInternal(
                text = "Hello",
                conversationId = 801L,
                template = PromptTemplate.Auto
            )
        }

        val prompt = requireNotNull(inference.lastPrompt)
        assertTrue(prompt.contains("<|im_start|>user"))
        assertTrue(prompt.contains("<|im_end|>"))
        assertFalse(prompt.contains("<|start_header_id|>"))
    }

    @Test
    fun manualChatMLOverrideIsPreservedEvenWhenMetadataSuggestsLlama3() {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = FakeInferenceEngine()
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = MainApplication(),
            groundingClock = fixedClock(),
            healthSummaryOverride = { HEALTH_SUMMARY }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)
        viewModel.mainModelMetadata.value = ModelMetadata(
            architecture = "smollm3",
            name = "PocketNode_Operator_BF16_Fresh",
            tokenizerModel = "gpt2",
            vocabSize = 128256,
            chatTemplate = ""
        )

        runBlocking {
            viewModel.sendMessageInternal(
                text = "Hello",
                conversationId = 802L,
                template = PromptTemplate.ChatML  // explicit manual override
            )
        }

        val prompt = requireNotNull(inference.lastPrompt)
        // Manual ChatML must be used despite smollm3+pocketnode metadata
        assertTrue(prompt.contains("<|im_start|>user"))
        assertFalse(prompt.contains("<|start_header_id|>"))
    }

    @Test
    fun autoTemplateGroundingStillReachesNativeGeneration() {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = FakeInferenceEngine()
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = MainApplication(),
            groundingClock = fixedClock(),
            healthSummaryOverride = { HEALTH_SUMMARY }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)
        viewModel.mainModelMetadata.value = ModelMetadata(
            architecture = "smollm3",
            name = "PocketNode_Operator_BF16_Fresh",
            tokenizerModel = "gpt2",
            vocabSize = 128256,
            chatTemplate = ""
        )

        runBlocking {
            viewModel.sendMessageInternal(
                text = "What date is today?",
                conversationId = 803L,
                template = PromptTemplate.Auto
            )
        }

        assertEquals(1, inference.generateCalls)
        val prompt = requireNotNull(inference.lastPrompt)
        // PromptGrounding must still inject context
        assertTrue(prompt.contains("<POCKET_NODE_CONTEXT>"))
        assertTrue(prompt.contains("You are Pocket Node."))
        // Raw user message must not be stored with grounding artifacts
        val storedMessages = runBlocking { repository.getMessagesSnapshot(803L) }
        val storedUser = storedMessages.first { it.role == "user" }
        assertEquals("What date is today?", storedUser.content)
        assertFalse(storedUser.content.contains("POCKET_NODE_CONTEXT"))
    }

    @Test
    fun autoTemplateGroundingNotVisibleInStoredAssistantMessage() {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = FakeInferenceEngine().apply {
            streamedTokens = listOf("It is Thursday, June 4, 2026.")
        }
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = MainApplication(),
            groundingClock = fixedClock(),
            healthSummaryOverride = { HEALTH_SUMMARY }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)
        viewModel.mainModelMetadata.value = ModelMetadata(
            architecture = "smollm3",
            name = "PocketNode_Operator_BF16_Fresh",
            tokenizerModel = "gpt2",
            vocabSize = 128256,
            chatTemplate = ""
        )

        runBlocking {
            viewModel.sendMessageInternal(
                text = "What day is today?",
                conversationId = 804L,
                template = PromptTemplate.Auto
            )
        }

        val storedMessages = runBlocking { repository.getMessagesSnapshot(804L) }
        val assistant = storedMessages.last { it.role == "assistant" }
        assertEquals("It is Thursday, June 4, 2026.", assistant.content)
        assertFalse(assistant.content.contains("<POCKET_NODE_CONTEXT>"))
        assertFalse(assistant.content.contains("POCKET_NODE_CONTEXT"))
    }

    @Test
    fun sanitizerStillBlocksLeakedGroundingMarkersWithAutoTemplate() {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = FakeInferenceEngine().apply {
            streamedTokens = listOf("<POCKET_NODE_CONTEXT>\n", "Current device date/time: Wednesday, June 3, 2026\n")
        }
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = MainApplication(),
            groundingClock = fixedClock(),
            healthSummaryOverride = { HEALTH_SUMMARY }
        )
        viewModel.setLoadedContextForTesting(modelPtr = 1L, contextPtr = 1L)
        viewModel.mainModelMetadata.value = ModelMetadata(
            architecture = "smollm3",
            name = "PocketNode_Operator_BF16_Fresh",
            tokenizerModel = "gpt2",
            vocabSize = 128256,
            chatTemplate = ""
        )

        runBlocking {
            viewModel.sendMessageInternal(
                text = "What date is today?",
                conversationId = 805L,
                template = PromptTemplate.Auto
            )
        }

        val storedMessages = runBlocking { repository.getMessagesSnapshot(805L) }
        val assistant = storedMessages.last { it.role == "assistant" }
        assertEquals(
            "I had an internal prompt-formatting error on that turn. Please resend your message.",
            assistant.content
        )
    }

    @Test
    fun sendMessageKeepsNormalAssistantAnswerVisible() {
        val dao = InMemoryChatDao()
        val repository = ChatRepository(dao)
        val inference = FakeInferenceEngine().apply {
            streamedTokens = listOf("BASELINE_", "OK")
        }
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = MainApplication(),
            groundingClock = fixedClock(),
            healthSummaryOverride = { HEALTH_SUMMARY }
        )

        viewModel.setLoadedContextForTesting(
            modelPtr = 1L,
            contextPtr = 1L,
            modelName = "PocketNode_Operator_Q4_0",
            backend = "OpenCL"
        )

        runBlocking {
            viewModel.sendMessageInternal(
                text = "Reply with BASELINE_OK only.",
                conversationId = 707L,
                template = PromptTemplate.ChatML
            )
        }

        val storedMessages = runBlocking { repository.getMessagesSnapshot(707L) }
        val assistant = storedMessages.last { it.role == "assistant" }

        assertEquals("BASELINE_OK", assistant.content)
    }

    private fun newViewModel(): Pair<FakeInferenceEngine, ChatViewModel> {
        val inference = FakeInferenceEngine()
        val repository = ChatRepository(InMemoryChatDao())
        val viewModel = ChatViewModel(
            inference = inference,
            repository = repository,
            app = MainApplication(),
            groundingClock = fixedClock(),
            healthSummaryOverride = { HEALTH_SUMMARY }
        )
        viewModel.setLoadedContextForTesting(
            modelPtr = 1L,
            contextPtr = 1L,
            modelName = "PocketNode_Operator_Q4_0",
            backend = "OpenCL"
        )
        return inference to viewModel
    }

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-06-04T01:54:00Z"), ZoneId.of("America/New_York"))

    private fun waitFor(
        timeoutMs: Long = 1_000L,
        predicate: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Condition was not met within ${timeoutMs}ms")
            }
            Thread.sleep(10)
        }
    }

    private fun createTempGguf(baseName: String): File {
        val file = File.createTempFile(baseName, ".gguf")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(10_500_000L)
        }
        file.deleteOnExit()
        return file
    }

    private class FakeInferenceEngine : InferenceEngine {
        var lastPrompt: String? = null
        var modelMetadata: Array<String>? = null
        var loadModelCalls: Int = 0
        var generateCalls: Int = 0
        var streamedTokens: List<String> = emptyList()

        override fun nativeLoadModel(modelPath: String, nGpuLayers: Int): Long {
            loadModelCalls += 1
            return 1L
        }
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
            generateCalls += 1
            lastPrompt = prompt
            streamedTokens.forEach(callback::onToken)
            callback.onStats(
                tps = 120f,
                ttftMs = 50L,
                draftAcceptRate = 0f,
                totalTokens = 16,
                promptEvalTps = 200f,
                backendName = "Vulkan,OpenCL",
                nDrafted = 0,
                nAccepted = 0,
                nCtx = 4096,
                nPast = 0
            )
        }

        override fun nativeStopGeneration(ctxPtr: Long) = Unit
        override fun nativeClearCache(ctxPtr: Long) = Unit
        override fun nativeGetTokenCount(modelPtr: Long, text: String): Int =
            text.split(Regex("\\s+")).count { it.isNotBlank() }

        override fun nativeGetContextLength(modelPtr: Long): Int = 4096
        override fun nativeGetEmbeddingSize(modelPtr: Long): Int = 0
        override fun nativeGetVocabSize(modelPtr: Long): Int = 0
        override fun nativeGetLastError(): String = ""
        override fun nativeGetBackendName(): String = "Vulkan,OpenCL"
        override fun nativeGetModelMetadata(contextPtr: Long): Array<String>? = modelMetadata
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

        override fun getMessagesForConversation(conversationId: Long): Flow<List<ChatMessage>> =
            messageFlow(conversationId)

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
            if (index >= 0) {
                entries[index] = message
            } else {
                entries += message
            }
            entries.sortBy { it.timestamp }
            emitMessages(message.conversationId)
        }

        override suspend fun deleteMessagesForConversation(conversationId: Long) {
            messages.remove(conversationId)
            emitMessages(conversationId)
        }

        override suspend fun deleteConversation(id: Long) {
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
            messageFlows.getOrPut(conversationId) {
                MutableStateFlow(messages[conversationId]?.toList() ?: emptyList())
            }
    }

    private companion object {
        const val HEALTH_SUMMARY =
            "service_alive=truemodel_loaded=true backend=OpenCL battery=100% charging=true thermal=light eligible_for_inference=true"
    }
}
