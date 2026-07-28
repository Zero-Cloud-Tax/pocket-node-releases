package com.pocketnode.app.session

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Lifecycle states for a single conversation session. No prompt/response text
 * ever passes through this class — it tracks identity and generation only.
 */
enum class SessionState {
    IDLE, SENDING, STREAMING, COMPLETED, INTERRUPTED, STALE, RESET_REQUIRED
}

/** Daemon reachability, tracked separately from [SessionState] — a transient
 * health-check failure must read as "reconnecting", never as "the daemon
 * restarted and invalidated my context" (only an actual boot id change does
 * that). */
enum class ConnectionStatus {
    CONNECTED, RECONNECTING
}

data class SessionSnapshot(
    val sessionId: String,
    val generation: Int,
    val state: SessionState,
    val modelFingerprint: String?,
    val backendFingerprint: String?,
    val connectionStatus: ConnectionStatus
)

/** Proof that a request was issued against a specific session/generation. Any
 * response must present the same token before it may touch persistence or UI. */
data class RequestToken(
    val sessionId: String,
    val generation: Int,
    val sequence: Long
)

/**
 * Single authoritative source of truth for conversation session identity,
 * context generation, and request/response staleness — see
 * Pocket Node stale-session hardening (session lifecycle invariants).
 *
 * Not persisted by this class itself: callers persist [SessionSnapshot.sessionId]
 * and [SessionSnapshot.generation] on the conversation record and call [restore]
 * when rebinding (app relaunch, switching conversations).
 */
class SessionManager {
    @Volatile private var sessionId: String = UUID.randomUUID().toString()
    @Volatile private var generation: Int = 0
    @Volatile private var state: SessionState = SessionState.IDLE
    @Volatile private var modelFingerprint: String? = null
    @Volatile private var backendFingerprint: String? = null
    @Volatile private var daemonBootId: String? = null
    @Volatile private var connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTED
    @Volatile private var dirty: Boolean = false
    // The most recently issued request's sequence. Only the token matching
    // this (plus session+generation) is "current" — an older in-flight
    // request (e.g. one that was interrupted but whose completion callback
    // is still pending) must never be allowed to overwrite a newer one, even
    // within the same generation. Covers "completion from request N cannot
    // overwrite request N+1" (e.g. retry superseding the original attempt).
    @Volatile private var latestSequence: Long = 0
    private val sequenceCounter = AtomicLong(0)

    @Synchronized
    fun snapshot(): SessionSnapshot =
        SessionSnapshot(sessionId, generation, state, modelFingerprint, backendFingerprint, connectionStatus)

    /** "New chat" — a genuinely new session identity, not merely a cleared state. */
    @Synchronized
    fun newSession(): SessionSnapshot {
        sessionId = UUID.randomUUID().toString()
        generation = 0
        state = SessionState.IDLE
        dirty = false
        sequenceCounter.set(0)
        return snapshot()
    }

    /** Rebind to a conversation's previously persisted session identity. */
    @Synchronized
    fun restore(persistedSessionId: String, persistedGeneration: Int): SessionSnapshot {
        sessionId = persistedSessionId
        generation = persistedGeneration
        state = SessionState.IDLE
        dirty = false
        return snapshot()
    }

    /**
     * Reset context within the same session: bumps the generation so any
     * in-flight or late request/response from before the reset is rejected,
     * but keeps the same sessionId. Idempotent — calling reset repeatedly
     * with no activity in between does not keep incrementing the generation.
     */
    @Synchronized
    fun resetSession(): SessionSnapshot {
        if (dirty || state == SessionState.RESET_REQUIRED || state == SessionState.STALE) {
            generation += 1
        }
        state = SessionState.IDLE
        dirty = false
        return snapshot()
    }

    @Synchronized
    fun beginRequest(): RequestToken {
        dirty = true
        state = SessionState.SENDING
        val seq = sequenceCounter.incrementAndGet()
        latestSequence = seq
        return RequestToken(sessionId, generation, seq)
    }

    @Synchronized
    fun markStreaming(token: RequestToken) {
        if (isCurrent(token)) state = SessionState.STREAMING
    }

    /** True if [token] still belongs to the current session/generation AND
     * is the most recently issued request — an older request (even one from
     * the same generation, e.g. superseded by a retry) is stale. A false
     * result means the caller must discard the response, not apply it. */
    @Synchronized
    fun isCurrent(token: RequestToken): Boolean =
        token.sessionId == sessionId && token.generation == generation && token.sequence == latestSequence

    @Synchronized
    fun markCompleted(token: RequestToken): Boolean {
        if (!isCurrent(token)) return false
        state = SessionState.COMPLETED
        return true
    }

    @Synchronized
    fun markInterrupted(token: RequestToken) {
        if (isCurrent(token)) state = SessionState.INTERRUPTED
    }

    /**
     * Model or backend identity changed. Returns true if this breaks context
     * continuity (generation bumped) — callers must then discard any in-flight
     * token and clear native context before issuing new requests.
     */
    @Synchronized
    fun onModelOrBackendChanged(newModel: String?, newBackend: String?): Boolean {
        val changed = (modelFingerprint != null && modelFingerprint != newModel) ||
            (backendFingerprint != null && backendFingerprint != newBackend)
        modelFingerprint = newModel
        backendFingerprint = newBackend
        if (changed) {
            generation += 1
            state = SessionState.IDLE
            dirty = false
        }
        return changed
    }

    /**
     * The daemon reported a boot ID different from the last one this session
     * observed — its in-memory context cannot be trusted to still exist.
     * Returns true when this represents an actual restart (not the first
     * observation, and not a repeat of the same id — idempotent), in which
     * case the generation is bumped (invalidating any in-flight token) and
     * the session moves to RESET_REQUIRED so no subsequent request can
     * pretend the previous daemon context still applies.
     */
    @Synchronized
    fun onDaemonBootIdObserved(bootId: String): Boolean {
        val previous = daemonBootId
        daemonBootId = bootId
        connectionStatus = ConnectionStatus.CONNECTED
        val changed = previous != null && previous != bootId
        if (changed) {
            generation += 1
            state = SessionState.RESET_REQUIRED
            dirty = false
        }
        return changed
    }

    /**
     * Single entry point for a health/status check result — reuse whatever
     * polling path already exists (e.g. an in-process ApiServer status read)
     * rather than adding a new one. A failed/unreachable check never
     * invalidates context by itself: it only flips [ConnectionStatus] to
     * RECONNECTING so the UI can show that distinctly from a genuine
     * daemon-restart (RESET_REQUIRED). Only a *changed* boot id on a
     * successful check invalidates context — see [onDaemonBootIdObserved].
     */
    @Synchronized
    fun onDaemonHealthObserved(alive: Boolean, bootId: String?): SessionSnapshot {
        if (!alive || bootId == null) {
            connectionStatus = ConnectionStatus.RECONNECTING
            return snapshot()
        }
        onDaemonBootIdObserved(bootId)
        return snapshot()
    }

    @Synchronized
    fun currentGeneration(): Int = generation

    @Synchronized
    fun currentSessionId(): String = sessionId

    @Synchronized
    fun currentState(): SessionState = state

    @Synchronized
    fun currentDaemonBootId(): String? = daemonBootId
}
