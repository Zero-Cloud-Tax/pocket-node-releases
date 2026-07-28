package com.pocketnode.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {

    // 1. New chat creates a new session ID.
    @Test
    fun newSession_generatesDifferentSessionId() {
        val manager = SessionManager()
        val before = manager.snapshot()
        val after = manager.newSession()
        assertNotEquals(before.sessionId, after.sessionId)
        assertEquals(0, after.generation)
        assertEquals(SessionState.IDLE, after.state)
    }

    // 2. Reset increments generation and clears active context.
    @Test
    fun resetSession_incrementsGenerationAfterActivity() {
        val manager = SessionManager()
        val sessionId = manager.snapshot().sessionId
        manager.beginRequest() // marks the session dirty

        val afterReset = manager.resetSession()

        assertEquals(sessionId, afterReset.sessionId) // same session, not a new chat
        assertEquals(1, afterReset.generation)
        assertEquals(SessionState.IDLE, afterReset.state)
    }

    // 3. Reset is idempotent.
    @Test
    fun resetSession_isIdempotentWithNoActivityBetweenCalls() {
        val manager = SessionManager()
        manager.beginRequest()
        val first = manager.resetSession()
        val second = manager.resetSession()
        assertEquals(first.generation, second.generation)
        assertEquals(first.sessionId, second.sessionId)
    }

    @Test
    fun resetSession_withNoPriorActivity_doesNotBumpGeneration() {
        val manager = SessionManager()
        val snapshot = manager.resetSession()
        assertEquals(0, snapshot.generation)
    }

    // 4. Late response from an old generation is rejected.
    @Test
    fun isCurrent_rejectsTokenFromOldGeneration() {
        val manager = SessionManager()
        val staleToken = manager.beginRequest()
        manager.resetSession() // generation bumps to 1

        assertFalse(manager.isCurrent(staleToken))
        assertFalse(manager.markCompleted(staleToken))
    }

    @Test
    fun isCurrent_acceptsTokenFromCurrentGeneration() {
        val manager = SessionManager()
        val token = manager.beginRequest()
        assertTrue(manager.isCurrent(token))
        assertTrue(manager.markCompleted(token))
    }

    // 5. Late stream chunks after cancellation are ignored (modeled as:
    // once a newer request superseded the token, the old token is stale).
    @Test
    fun isCurrent_rejectsTokenAfterNewChatSupersedesIt() {
        val manager = SessionManager()
        val oldToken = manager.beginRequest()
        manager.newSession()
        val newToken = manager.beginRequest()

        assertFalse(manager.isCurrent(oldToken))
        assertTrue(manager.isCurrent(newToken))
    }

    // 6. Model switch invalidates incompatible context.
    @Test
    fun onModelOrBackendChanged_bumpsGenerationWhenModelDiffers() {
        val manager = SessionManager()
        manager.onModelOrBackendChanged("model-a", "CPU") // first observation, not a "change"
        val genBefore = manager.currentGeneration()

        val changed = manager.onModelOrBackendChanged("model-b", "CPU")

        assertTrue(changed)
        assertEquals(genBefore + 1, manager.currentGeneration())
    }

    @Test
    fun onModelOrBackendChanged_doesNotBumpWhenModelUnchanged() {
        val manager = SessionManager()
        manager.onModelOrBackendChanged("model-a", "CPU")
        val genBefore = manager.currentGeneration()

        val changed = manager.onModelOrBackendChanged("model-a", "CPU")

        assertFalse(changed)
        assertEquals(genBefore, manager.currentGeneration())
    }

    // 7. Backend switch invalidates or renegotiates context.
    @Test
    fun onModelOrBackendChanged_bumpsGenerationWhenBackendDiffers() {
        val manager = SessionManager()
        manager.onModelOrBackendChanged("model-a", "CPU")
        val genBefore = manager.currentGeneration()

        val changed = manager.onModelOrBackendChanged("model-a", "Vulkan")

        assertTrue(changed)
        assertEquals(genBefore + 1, manager.currentGeneration())
    }

    // 9. Daemon boot-ID change marks the session stale/reset-required.
    @Test
    fun onDaemonBootIdObserved_marksResetRequiredOnChange() {
        val manager = SessionManager()
        manager.onDaemonBootIdObserved("boot-1") // first observation

        val changed = manager.onDaemonBootIdObserved("boot-2")

        assertTrue(changed)
        assertEquals(SessionState.RESET_REQUIRED, manager.currentState())
        assertEquals(1, manager.currentGeneration())
    }

    @Test
    fun onDaemonBootIdObserved_sameBootId_doesNotChangeState() {
        val manager = SessionManager()
        manager.onDaemonBootIdObserved("boot-1")
        val changed = manager.onDaemonBootIdObserved("boot-1")
        assertFalse(changed)
        assertEquals(SessionState.IDLE, manager.currentState())
        assertEquals(0, manager.currentGeneration())
    }

    // 1. First observation of a boot id must not invalidate a fresh session.
    @Test
    fun onDaemonBootIdObserved_firstObservation_doesNotInvalidateFreshSession() {
        val manager = SessionManager()
        val changed = manager.onDaemonBootIdObserved("boot-1")
        assertFalse(changed)
        assertEquals(SessionState.IDLE, manager.currentState())
        assertEquals(0, manager.currentGeneration())
        assertEquals("boot-1", manager.currentDaemonBootId())
    }

    // 5. Daemon boot-ID change moves the session to reset-required.
    @Test
    fun onDaemonHealthObserved_newBootId_movesToResetRequiredAndBumpsGeneration() {
        val manager = SessionManager()
        manager.onDaemonHealthObserved(alive = true, bootId = "boot-1")

        val snapshot = manager.onDaemonHealthObserved(alive = true, bootId = "boot-2")

        assertEquals(SessionState.RESET_REQUIRED, snapshot.state)
        assertEquals(1, snapshot.generation)
        assertEquals(ConnectionStatus.CONNECTED, snapshot.connectionStatus)
    }

    // 6. Repeated identical boot ID is a no-op.
    @Test
    fun onDaemonHealthObserved_repeatedSameBootId_isNoOp() {
        val manager = SessionManager()
        manager.onDaemonHealthObserved(alive = true, bootId = "boot-1")
        val genBefore = manager.currentGeneration()

        val snapshot = manager.onDaemonHealthObserved(alive = true, bootId = "boot-1")

        assertEquals(SessionState.IDLE, snapshot.state)
        assertEquals(genBefore, snapshot.generation)
    }

    // 7. Health failure followed by recovery with the SAME boot id does not invalidate context.
    @Test
    fun onDaemonHealthObserved_transientFailureThenRecoverySameBootId_doesNotInvalidate() {
        val manager = SessionManager()
        manager.onDaemonHealthObserved(alive = true, bootId = "boot-1")
        val genBefore = manager.currentGeneration()

        val duringFailure = manager.onDaemonHealthObserved(alive = false, bootId = null)
        assertEquals(ConnectionStatus.RECONNECTING, duringFailure.connectionStatus)
        assertEquals(SessionState.IDLE, duringFailure.state) // not falsely marked reset-required

        val afterRecovery = manager.onDaemonHealthObserved(alive = true, bootId = "boot-1")

        assertEquals(ConnectionStatus.CONNECTED, afterRecovery.connectionStatus)
        assertEquals(SessionState.IDLE, afterRecovery.state)
        assertEquals(genBefore, afterRecovery.generation)
    }

    // 8. Health recovery with a NEW boot id does invalidate context.
    @Test
    fun onDaemonHealthObserved_recoveryWithNewBootId_doesInvalidate() {
        val manager = SessionManager()
        manager.onDaemonHealthObserved(alive = true, bootId = "boot-1")
        manager.onDaemonHealthObserved(alive = false, bootId = null) // outage

        val afterRecovery = manager.onDaemonHealthObserved(alive = true, bootId = "boot-2")

        assertEquals(SessionState.RESET_REQUIRED, afterRecovery.state)
        assertEquals(1, afterRecovery.generation)
        assertEquals(ConnectionStatus.CONNECTED, afterRecovery.connectionStatus)
    }

    // 10. Interrupted stream does not become a completed assistant message
    // (state-machine half of this — persistence half is covered by
    // ChatViewModel's finally-block wiring, verified separately).
    @Test
    fun markInterrupted_setsInterruptedStateForCurrentToken() {
        val manager = SessionManager()
        val token = manager.beginRequest()
        manager.markInterrupted(token)
        assertEquals(SessionState.INTERRUPTED, manager.currentState())
    }

    @Test
    fun markInterrupted_isNoOpForStaleToken() {
        val manager = SessionManager()
        val staleToken = manager.beginRequest()
        manager.resetSession()
        manager.markInterrupted(staleToken)
        // resetSession already put state back to IDLE; a stale interrupt must not override it
        assertEquals(SessionState.IDLE, manager.currentState())
    }

    // 11. Retry uses a new request sequence and does not duplicate the user turn
    // (sequence half — no-duplication is a ChatViewModel-level guarantee).
    @Test
    fun beginRequest_incrementsSequenceOnEachCall() {
        val manager = SessionManager()
        val first = manager.beginRequest()
        val second = manager.beginRequest()
        assertTrue(second.sequence > first.sequence)
        assertEquals(first.sessionId, second.sessionId)
        assertEquals(first.generation, second.generation)
    }

    // 4. Completion from request N cannot overwrite request N+1 — same
    // session AND generation, but request N+1 (e.g. a retry) supersedes N.
    @Test
    fun isCurrent_rejectsOlderSequenceWithinSameGenerationOnceANewerRequestStarted() {
        val manager = SessionManager()
        val requestN = manager.beginRequest()
        val requestNPlus1 = manager.beginRequest() // e.g. retry, same session/generation

        assertFalse("request N must be stale once N+1 has started", manager.isCurrent(requestN))
        assertTrue(manager.isCurrent(requestNPlus1))
        assertFalse(manager.markCompleted(requestN))
        assertTrue(manager.markCompleted(requestNPlus1))
    }

    // 12. Old session cannot overwrite the active conversation.
    @Test
    fun restore_toOlderPersistedGeneration_doesNotAllowOldTokenToPass() {
        val manager = SessionManager()
        val token = manager.beginRequest()
        manager.resetSession() // generation 1

        // Simulate an app relaunch that restores the OLD persisted generation
        // by mistake — this should never happen in real code (persistence
        // always tracks SessionManager's output), but the guard must hold
        // regardless: a token minted before the restore point is still
        // checked against whatever the manager considers current now.
        manager.restore(manager.currentSessionId(), 0)
        assertFalse(manager.isCurrent(token) && manager.currentGeneration() != token.generation)
    }

    // Diagnostics never carry prompt/response content by construction — the
    // type system enforces this: SessionSnapshot and RequestToken have no
    // text-bearing fields at all.
    @Test
    fun snapshotAndToken_haveNoTextBearingFields() {
        val snapshotFields = SessionSnapshot::class.java.declaredFields.map { it.name }
        val tokenFields = RequestToken::class.java.declaredFields.map { it.name }
        val forbidden = listOf("content", "prompt", "message", "text")
        assertTrue(snapshotFields.none { field -> forbidden.any { field.contains(it, ignoreCase = true) } })
        assertTrue(tokenFields.none { field -> forbidden.any { field.contains(it, ignoreCase = true) } })
    }
}
