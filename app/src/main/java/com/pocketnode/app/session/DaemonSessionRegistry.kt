package com.pocketnode.app.session

/** Outcome of validating a request's session metadata against what the
 * daemon has previously observed for that sessionId. Never carries prompt
 * or response content. */
enum class AcceptanceState {
    /** First time this sessionId has been seen since the daemon last (re)started. */
    NEW,
    /** Session already known, generation/sequence/model check passed. */
    RESUMED,
    /** Client's context_generation is behind what the daemon has already accepted. */
    STALE_GENERATION,
    /** Client's request_sequence is not greater than the last one accepted. */
    OUT_OF_ORDER_SEQUENCE,
    /** Client's declared model identity doesn't match what this session was tracking. */
    MODEL_MISMATCH,
    /** Client asserted continuity (generation/sequence/model) with a daemon
     * instance that is no longer the one running — always rejected, never
     * silently treated as resumed. */
    STALE_DAEMON_BOOT,
    /** No sessionId was supplied at all — a legacy/stateless client. Must
     * never be reported as RESUMED. */
    STATELESS;

    val isAccepted: Boolean get() = this == NEW || this == RESUMED || this == STATELESS
}

data class SessionAcceptance(
    val sessionId: String?,
    val generation: Int,
    val sequence: Long,
    val state: AcceptanceState
)

/**
 * Daemon-side counterpart to [SessionManager]: tracks, per sessionId, the
 * highest context generation and request sequence this daemon instance has
 * accepted, so it can reject stale or out-of-order requests instead of
 * silently pretending prior context still applies. Cleared on every daemon
 * (re)start — see ApiServer.start() — so a client resuming after a restart
 * is always evaluated fresh (AcceptanceState.NEW, never RESUMED).
 */
class DaemonSessionRegistry {
    private data class Entry(var generation: Int, var lastSequence: Long, var modelFingerprint: String?)

    private val sessions = mutableMapOf<String, Entry>()

    @Synchronized
    fun evaluate(
        sessionId: String?,
        clientGeneration: Int?,
        clientSequence: Long?,
        clientDaemonBootId: String?,
        currentDaemonBootId: String,
        modelFingerprint: String?
    ): SessionAcceptance {
        if (sessionId.isNullOrBlank()) {
            return SessionAcceptance(null, clientGeneration ?: 0, clientSequence ?: 0, AcceptanceState.STATELESS)
        }

        // A client claiming continuity with a daemon instance that is no
        // longer running is always rejected, regardless of what the
        // in-memory registry (which was cleared on restart) currently shows.
        if (clientDaemonBootId != null && clientDaemonBootId != currentDaemonBootId) {
            return SessionAcceptance(sessionId, 0, 0, AcceptanceState.STALE_DAEMON_BOOT)
        }

        val existing = sessions[sessionId]
        if (existing == null) {
            val generation = clientGeneration ?: 0
            val sequence = clientSequence ?: 1L
            sessions[sessionId] = Entry(generation, sequence, modelFingerprint)
            return SessionAcceptance(sessionId, generation, sequence, AcceptanceState.NEW)
        }

        if (clientGeneration != null && clientGeneration < existing.generation) {
            return SessionAcceptance(sessionId, existing.generation, existing.lastSequence, AcceptanceState.STALE_GENERATION)
        }
        if (clientSequence != null && clientSequence <= existing.lastSequence) {
            return SessionAcceptance(sessionId, existing.generation, existing.lastSequence, AcceptanceState.OUT_OF_ORDER_SEQUENCE)
        }
        if (modelFingerprint != null && existing.modelFingerprint != null && modelFingerprint != existing.modelFingerprint) {
            return SessionAcceptance(sessionId, existing.generation, existing.lastSequence, AcceptanceState.MODEL_MISMATCH)
        }

        val newGeneration = clientGeneration ?: existing.generation
        val newSequence = clientSequence ?: (existing.lastSequence + 1)
        existing.generation = newGeneration
        existing.lastSequence = newSequence
        existing.modelFingerprint = modelFingerprint ?: existing.modelFingerprint
        return SessionAcceptance(sessionId, newGeneration, newSequence, AcceptanceState.RESUMED)
    }

    /** Called on every daemon (re)start — see ApiServer.start(). */
    @Synchronized
    fun reset() {
        sessions.clear()
    }
}
