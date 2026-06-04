package com.pocketnode.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.pocketnode.app.diagnostics.ServiceHealthLog
import com.pocketnode.app.diagnostics.ServiceHealthLog.EventType
import com.pocketnode.app.inference.GenerationService
import com.pocketnode.app.ui.screens.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // DataStore requires device unlock — LOCKED_BOOT_COMPLETED is intentionally
        // not handled because files are encrypted at rest before first unlock.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.settingsDataStore.data.first()
                val edgeApiEnabled = prefs[booleanPreferencesKey("edge_api_enabled")] ?: false
                if (edgeApiEnabled) {
                    val eventType = if (action == Intent.ACTION_MY_PACKAGE_REPLACED)
                        EventType.PACKAGE_REPLACED_START_ATTEMPTED
                    else
                        EventType.BOOT_START_ATTEMPTED
                    ServiceHealthLog.record(eventType)
                    startEdgeApiService(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun startEdgeApiService(context: Context) {
        val serviceIntent = Intent(context, GenerationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                // Android 12+ blocks foreground service starts when there is no
                // visible activity (common immediately after boot before first unlock).
                // Recovery: MainActivity's LaunchedEffect(edgeApiEnabled, isPro) will
                // call startForegroundService the next time the user opens the app.
                // START_STICKY does NOT apply here — the service was never started,
                // so there is nothing for the OS to restart.
                ServiceHealthLog.record(EventType.BOOT_START_DENIED, e.message ?: "")
            }
        } else {
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
