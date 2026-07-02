package com.pocketnode.app.inference

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.intPreferencesKey
import com.pocketnode.app.InferenceSession
import com.pocketnode.app.MainActivity
import com.pocketnode.app.MainApplication
import com.pocketnode.app.data.AppDatabase
import com.pocketnode.app.diagnostics.ServiceHealthLog
import com.pocketnode.app.diagnostics.ServiceHealthLog.EventType
import com.pocketnode.app.ui.screens.settingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GenerationService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "pocketnode_edge_api"
        private const val TAG = "PocketNode.ModelLoad"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Pointers owned by this service (0 if not loaded or already freed)
    private var ownedModelPtr = 0L
    private var ownedCtxPtr = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketNode::EdgeApiWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MainApplication

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pocket Node Edge API")
            .setContentText("Running on port 11434 — tap to open app")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        ServiceHealthLog.record(EventType.SERVICE_STARTED)
        ApiServer.start(app)

        if (wakeLock?.isHeld == false) wakeLock?.acquire()

        // Fast-path check only — the authoritative check happens inside
        // ModelLoadCoordinator's lock in autoLoadModel(), since activeSession
        // being null here is not itself a guarantee by the time the launched
        // coroutine actually runs (see P29 RC3.1).
        if (app.activeSession == null) {
            serviceScope.launch { autoLoadModel(app) }
        }

        return START_STICKY
    }

    private suspend fun autoLoadModel(app: MainApplication) {
        ModelLoadCoordinator.withLifecycleLock("service_auto_load") {
            // Re-check under the lock: another load (this service racing itself
            // via a second onStartCommand, or the chat UI) may have already
            // populated activeSession while we were waiting for the lock.
            if (app.activeSession != null) {
                Log.i(TAG, "model_load_skip_already_active")
                return@withLifecycleLock
            }

            val model = AppDatabase.getInstance(app).modelDao().getFirstMainModel()
                ?: return@withLifecycleLock

            val prefs = settingsDataStore.data.first()
            val contextSize = prefs[intPreferencesKey("context_size")] ?: 4096
            val threadCount = prefs[intPreferencesKey("thread_count")]
                ?: Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val gpuLayers = prefs[intPreferencesKey("gpu_layers")] ?: 0

            Log.i(TAG, "model_load_start model=${model.name}")

            val modelPtr = app.inference.nativeLoadModel(model.path, gpuLayers)
            if (modelPtr == 0L) {
                Log.w(TAG, "model_load_failed reason=native_load_model_failed")
                return@withLifecycleLock
            }

            val ctxPtr = app.inference.nativeCreateContext(modelPtr, contextSize, threadCount)
            if (ctxPtr == 0L) {
                app.inference.nativeFreeModel(modelPtr)
                Log.w(TAG, "model_load_failed reason=native_create_context_failed")
                return@withLifecycleLock
            }

            // Native calls above can't be cancelled mid-execution. If the scope was
            // cancelled while they were blocking, free the pointers we just allocated
            // rather than leaking them, then let the CancellationException propagate.
            try {
                currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) {
                app.inference.nativeFreeContext(ctxPtr)
                app.inference.nativeFreeModel(modelPtr)
                throw e
            }

            ownedModelPtr = modelPtr
            ownedCtxPtr = ctxPtr
            app.activeSession = InferenceSession(ctxPtr, model.name)
            Log.i(TAG, "model_load_success model=${model.name}")
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()

        // Stop the server and drain any in-flight inference BEFORE freeing the
        // context pointer. If drain times out, skip nativeFreeContext — a bounded
        // memory leak is safer than a use-after-free SIGSEGV.
        val drained = ApiServer.stop()

        val app = application as MainApplication

        if (ownedCtxPtr != 0L) {
            Log.i(TAG, "model_unload_start")
            // Clear the shared session only if it still points to our context
            // (ChatViewModel may have replaced it with its own loaded model)
            if (app.activeSession?.contextPtr == ownedCtxPtr) {
                app.activeSession = null
            }
            if (drained) {
                app.inference.nativeFreeContext(ownedCtxPtr)
                app.inference.nativeFreeModel(ownedModelPtr)
                Log.i(TAG, "model_unload_success")
            } else {
                ServiceHealthLog.record(EventType.NATIVE_FREE_SKIPPED,
                    "drain timed out; skipping nativeFreeContext to avoid SIGSEGV")
            }
            // Always zero so this block is idempotent regardless of drain outcome.
            ownedCtxPtr = 0L
            ownedModelPtr = 0L
        }

        ServiceHealthLog.record(EventType.SERVICE_STOPPED)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Edge API Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the Pocket Node Edge API running in the background"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
