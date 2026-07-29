package com.pocketnode.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonSessionRegistryTest {

    @Test
    fun noSessionId_isTreatedAsStateless_neverResumed() {
        val registry = DaemonSessionRegistry()
        val result = registry.evaluate(null, null, null, null, "boot-1", null)
        assertEquals(AcceptanceState.STATELESS, result.state)
        assertTrue(result.state.isAccepted)
    }

    @Test
    fun blankSessionId_isTreatedAsStateless() {
        val registry = DaemonSessionRegistry()
        val result = registry.evaluate("  ", null, null, null, "boot-1", null)
        assertEquals(AcceptanceState.STATELESS, result.state)
    }

    @Test
    fun firstRequestForASessionId_isNew_notResumed() {
        val registry = DaemonSessionRegistry()
        val result = registry.evaluate("s1", 0, 1, null, "boot-1", "model-a")
        assertEquals(AcceptanceState.NEW, result.state)
        assertTrue(result.state.isAccepted)
        assertEquals(0, result.generation)
        assertEquals(1L, result.sequence)
    }

    @Test
    fun secondRequestWithHigherGenerationAndSequence_isResumed() {
        val registry = DaemonSessionRegistry()
        registry.evaluate("s1", 0, 1, null, "boot-1", "model-a")
        val result = registry.evaluate("s1", 0, 2, null, "boot-1", "model-a")
        assertEquals(AcceptanceState.RESUMED, result.state)
        assertTrue(result.state.isAccepted)
    }

    @Test
    fun generationBehindWhatWasAlreadyAccepted_isRejectedAsStale() {
        val registry = DaemonSessionRegistry()
        registry.evaluate("s1", 2, 1, null, "boot-1", null)
        val result = registry.evaluate("s1", 1, 2, null, "boot-1", null)
        assertEquals(AcceptanceState.STALE_GENERATION, result.state)
        assertTrue(!result.state.isAccepted)
        assertEquals(2, result.generation) // echoes what the daemon actually has
    }

    @Test
    fun sequenceNotGreaterThanLastAccepted_isRejectedAsOutOfOrder() {
        val registry = DaemonSessionRegistry()
        registry.evaluate("s1", 0, 5, null, "boot-1", null)
        val result = registry.evaluate("s1", 0, 5, null, "boot-1", null)
        assertEquals(AcceptanceState.OUT_OF_ORDER_SEQUENCE, result.state)
        assertTrue(!result.state.isAccepted)
    }

    @Test
    fun mismatchedModelFingerprint_isRejected() {
        val registry = DaemonSessionRegistry()
        registry.evaluate("s1", 0, 1, null, "boot-1", "model-a")
        val result = registry.evaluate("s1", 0, 2, null, "boot-1", "model-b")
        assertEquals(AcceptanceState.MODEL_MISMATCH, result.state)
        assertTrue(!result.state.isAccepted)
    }

    @Test
    fun clientClaimingContinuityAcrossADaemonRestart_isAlwaysRejected() {
        val registry = DaemonSessionRegistry()
        registry.evaluate("s1", 0, 1, null, "boot-1", null)
        // Client still thinks it's talking to boot-1, but the daemon is now boot-2.
        val result = registry.evaluate("s1", 1, 2, "boot-1", "boot-2", null)
        assertEquals(AcceptanceState.STALE_DAEMON_BOOT, result.state)
        assertTrue(!result.state.isAccepted)
    }

    @Test
    fun reset_clearsAllTrackedSessions_soPostRestartRequestsAreNewNotResumed() {
        val registry = DaemonSessionRegistry()
        registry.evaluate("s1", 3, 9, null, "boot-1", null)

        registry.reset() // simulates ApiServer.start() after a restart

        val result = registry.evaluate("s1", 3, 9, null, "boot-2", null)
        assertEquals(
            "a session id that existed before a restart must never be silently treated as resumed",
            AcceptanceState.NEW,
            result.state
        )
    }

    @Test
    fun missingGenerationOrSequence_defaultsGracefullyForLegacyStatefulClients() {
        val registry = DaemonSessionRegistry()
        val first = registry.evaluate("s1", null, null, null, "boot-1", null)
        assertEquals(AcceptanceState.NEW, first.state)
        val second = registry.evaluate("s1", null, null, null, "boot-1", null)
        // No client-declared sequence — the registry advances its own.
        assertEquals(AcceptanceState.RESUMED, second.state)
        assertTrue(second.sequence > first.sequence)
    }
}
