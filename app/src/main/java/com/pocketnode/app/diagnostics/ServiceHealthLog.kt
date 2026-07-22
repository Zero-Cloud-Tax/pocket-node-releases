package com.pocketnode.app.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object ServiceHealthLog {

    private const val TAG = "PocketNode.ServiceHealth"
    private const val MAX_EVENTS = 50

    enum class EventType {
        SERVICE_STARTED,
        SERVICE_STOPPED,
        BOOT_START_ATTEMPTED,
        BOOT_START_DENIED,
        PACKAGE_REPLACED_START_ATTEMPTED,
        STOP_DRAIN_STARTED,
        STOP_DRAIN_OK,
        STOP_DRAIN_TIMEOUT,
        NATIVE_FREE_SKIPPED,
        // P29 RC3.2: operator-triggered stop via the notification action or an
        // explicit ACTION_STOP_SERVING intent, distinct from the OS/system
        // stopping the service (e.g. low memory, swipe-away with no wake lock).
        STOP_ACTION_RECEIVED,
        // Hybrid alignment: structured request-correlation trace events.
        // Detail carries reason_code/status/request_id only — never prompt content.
        REQUEST_ACCEPTED,
        REQUEST_REJECTED,
    }

    data class ServiceEvent(
        val timestampMs: Long,
        val type: EventType,
        val detail: String = ""
    )

    private val _events = MutableStateFlow<List<ServiceEvent>>(emptyList())
    val events: StateFlow<List<ServiceEvent>> = _events

    fun record(type: EventType, detail: String = "") {
        val event = ServiceEvent(System.currentTimeMillis(), type, detail)
        Log.i(TAG, if (detail.isNotEmpty()) "${type.name}: $detail" else type.name)
        _events.update { current -> (listOf(event) + current).take(MAX_EVENTS) }
    }
}
