package com.pocketnode.app.inference

import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class GroundedTurnPrompt(
    val systemPrompt: String,
    val userPrompt: String,
    val serviceStatePresent: Boolean,
    val sectionFlags: Set<String>,
    val groundedContextPreview: String
)

object PromptGrounding {
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
    private val localDateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
    private val localDateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy h:mm a z")
    private val fathersDayRegex = Regex("\\bfather'?s day(?:\\s+(\\d{4}))?\\b", RegexOption.IGNORE_CASE)
    private val todayRegex = Regex(
        "\\b(what date is today|what day is today|today'?s date|today date|current date|date today)\\b",
        RegexOption.IGNORE_CASE
    )
    private val homelabServices = listOf("Moolah", "Neo", "Watchdawg", "Uno", "Edge Gate")

    fun currentDeviceDateTime(clock: Clock = Clock.systemDefaultZone()): ZonedDateTime =
        clock.instant().atZone(clock.zone)

    fun currentDeviceStamp(clock: Clock = Clock.systemDefaultZone()): String {
        return dateTimeFormatter.format(currentDeviceDateTime(clock))
    }

    fun buildGroundedSystemPrompt(
        baseSystemPrompt: String,
        deviceStamp: String,
        pocketNodeHealthSummary: String,
        serviceStateSummary: String? = null,
        contextualFacts: List<String> = emptyList()
    ): String {
        val grounding = buildString {
            appendLine("You are Pocket Node.")
            appendLine()
            appendLine("<POCKET_NODE_CONTEXT>")
            appendLine("- Current device time: $deviceStamp")
            appendLine("- Pocket Node is the local Android model host.")
            appendLine("- Static topology: Neo, Moolah, Watchdawg, Uno, and Edge Gate may exist in the wider homelab.")
            appendLine("- Never infer the live status of those systems from Pocket Node health alone.")
            appendLine("- Pocket Node health: $pocketNodeHealthSummary")
            if (!serviceStateSummary.isNullOrBlank()) {
                appendLine("- Service-state: ${serviceStateSummary.trim()}")
            }
            appendLine("- If the user asks about dates or time, prefer the injected device date/time/timezone over model guesswork.")
            appendLine("- Policy: Do not invent live node/service status.")
            appendLine("- If service-state data is unavailable, answer live-status questions as UNKNOWN.")
            contextualFacts.forEach { appendLine("- $it") }
            appendLine("</POCKET_NODE_CONTEXT>")
        }.trimEnd()

        val trimmedBase = baseSystemPrompt.trim()
        return if (trimmedBase.isEmpty()) grounding else "$trimmedBase\n\n$grounding"
    }

    fun buildGroundedTurnPrompt(
        baseSystemPrompt: String,
        rawUserPrompt: String,
        deviceTime: ZonedDateTime,
        pocketNodeHealthSummary: String,
        serviceStateSummary: String? = null
    ): GroundedTurnPrompt {
        val trimmedUserPrompt = rawUserPrompt.trim()
        val deviceStamp = dateTimeFormatter.format(deviceTime)
        val serviceStatePresent = !serviceStateSummary.isNullOrBlank()
        val sectionFlags = linkedSetOf(
            "device_time",
            "device_timezone",
            "pocket_node_health",
            "static_topology",
            if (serviceStatePresent) "service_state_present" else "service_state_absent",
            "no_live_inference_policy"
        )

        val facts = mutableListOf(
            "Current device date/time: ${localDateTimeFormatter.format(deviceTime)}",
            "Current device timezone: ${deviceTime.zone.id}",
            "Pocket Node local health: $pocketNodeHealthSummary",
            "Static homelab topology: ${homelabServices.joinToString()} may exist in the wider homelab.",
            if (serviceStatePresent) {
                "Injected service-state snapshot: ${serviceStateSummary!!.trim()}"
            } else {
                "Injected service-state snapshot: unavailable"
            },
            "Live service-status policy: ${homelabServices.joinToString()} must be reported as UNKNOWN unless explicit service-state data is injected."
        )

        if (todayRegex.containsMatchIn(trimmedUserPrompt)) {
            facts += "Deterministic date fact: On this device, today is ${localDateFormatter.format(deviceTime)}."
            sectionFlags += "today_fact"
        }

        val fathersDayYear = extractFathersDayYear(trimmedUserPrompt)
        if (fathersDayYear != null) {
            val fathersDay = usFathersDay(fathersDayYear)
            facts += "Deterministic calendar fact: U.S. Father's Day $fathersDayYear is ${localDateFormatter.format(fathersDay)}."
            sectionFlags += "fathers_day_fact"
        }

        val requestedServices = matchedServices(trimmedUserPrompt)
        if (requestedServices.isNotEmpty() && !serviceStatePresent) {
            facts += "Requested live status: ${requestedServices.joinToString()} = UNKNOWN because no service-state data was injected for this turn. Do not guess from Pocket Node local health."
            sectionFlags += "requested_service_unknown"
        }

        val groundedContextPreview = facts.joinToString(separator = "\n") { "- $it" }

        return GroundedTurnPrompt(
            systemPrompt = buildGroundedSystemPrompt(
                baseSystemPrompt = baseSystemPrompt,
                deviceStamp = deviceStamp,
                pocketNodeHealthSummary = pocketNodeHealthSummary,
                serviceStateSummary = serviceStateSummary,
                contextualFacts = facts
            ),
            userPrompt = trimmedUserPrompt,
            serviceStatePresent = serviceStatePresent,
            sectionFlags = sectionFlags,
            groundedContextPreview = groundedContextPreview
        )
    }

    private fun extractFathersDayYear(rawUserPrompt: String): Int? {
        val match = fathersDayRegex.find(rawUserPrompt) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun usFathersDay(year: Int): LocalDate =
        LocalDate.of(year, 6, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, java.time.DayOfWeek.SUNDAY))

    private fun matchedServices(rawUserPrompt: String): List<String> {
        val prompt = rawUserPrompt.lowercase()
        return homelabServices.filter { prompt.contains(it.lowercase()) }
    }
}
