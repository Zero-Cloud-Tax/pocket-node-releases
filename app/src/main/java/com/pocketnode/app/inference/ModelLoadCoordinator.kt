package com.pocketnode.app.inference

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes all model load / unload / active-session-replacement operations
 * across the whole app — both the chat UI (ChatViewModel) and the Edge API
 * service (GenerationService.autoLoadModel) — so at most one such operation
 * is ever in flight anywhere in the process.
 *
 * P29 RC3.1: prior to this, GenerationService.autoLoadModel() called
 * nativeLoadModel/nativeCreateContext with no lock at all, while
 * ChatViewModel serialized its own load/unload paths behind a private
 * Mutex the service never touched. Two racing callers (e.g. an app-upgrade
 * service restart overlapping a foreground load, or two overlapping service
 * starts) could call into native model-load/free concurrently and crash the
 * process via ggml_abort(). [MainApplication.activeSession] being
 * `@Volatile` only made the *visibility* of that field safe across threads —
 * it never made the load/unload/replace sequence atomic.
 *
 * [mutex] is exposed directly (rather than only through [withLifecycleLock])
 * so ChatViewModel's existing `nativeSessionMutex` can be repointed at this
 * single shared instance without restructuring its five existing
 * `nativeSessionMutex.withLock { ... }` call sites.
 */
object ModelLoadCoordinator {
    private const val TAG = "PocketNode.ModelLoad"

    val mutex = Mutex()

    /**
     * True while a load/unload/session-replacement is in progress anywhere
     * in the app. Purely informational for logging/skip decisions — callers
     * must still take [mutex] (via [withLifecycleLock]) to actually
     * serialize; this flag does not itself provide any exclusion.
     */
    @Volatile
    var inFlight: Boolean = false
        private set

    /**
     * Runs [block] under the shared lifecycle [mutex]. If another operation
     * already holds the lock, logs `model_load_join_inflight` before
     * suspending on it — this is what "join the in-flight load" means in
     * practice: the caller simply waits for the current holder to finish
     * rather than racing it.
     */
    suspend fun <T> withLifecycleLock(op: String, block: suspend () -> T): T {
        if (mutex.isLocked) {
            Log.i(TAG, "model_load_join_inflight op=$op")
        }
        return mutex.withLock {
            inFlight = true
            try {
                block()
            } finally {
                inFlight = false
            }
        }
    }
}
