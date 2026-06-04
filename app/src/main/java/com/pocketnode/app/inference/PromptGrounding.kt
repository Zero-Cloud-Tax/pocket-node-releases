package com.pocketnode.app.inference

import java.time.Clock
import java.time.format.DateTimeFormatter

object PromptGrounding {
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

    fun currentDeviceStamp(clock: Clock = Clock.systemDefaultZone()): String {
        val zonedDateTime = clock.instant().atZone(clock.zone)
        return dateTimeFormatter.format(zonedDateTime)
    }

    fun buildGroundedSystemPrompt(
        baseSystemPrompt: String,
        deviceStamp: String,
        pocketNodeHealthSummary: String,
        serviceStateSummary: String? = null
    ): String {
        val grounding = buildString {
            appendLine("Pocket Node grounding:")
            appendLine("- Current device time: $deviceStamp")
            appendLine("- Pocket Node is the local Android model host.")
            appendLine("- Static topology: Neo, Moolah, Watchdawg, Uno, and Edge Gate may exist in the wider homelab.")
            appendLine("- Never infer the live status of those systems from Pocket Node health alone.")
            appendLine("- Pocket Node health: $pocketNodeHealthSummary")
            if (!serviceStateSummary.isNullOrBlank()) {
                appendLine("- Service-state: ${serviceStateSummary.trim()}")
            }
            appendLine("- Policy: Do not invent live node/service status.")
            appendLine("- If service-state data is unavailable, answer live-status questions as UNKNOWN.")
        }.trimEnd()

        val trimmedBase = baseSystemPrompt.trim()
        return if (trimmedBase.isEmpty()) grounding else "$trimmedBase\n\n$grounding"
    }
}
