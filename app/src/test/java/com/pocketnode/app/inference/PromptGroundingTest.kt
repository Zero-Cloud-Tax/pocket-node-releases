package com.pocketnode.app.inference

import org.junit.Assert.assertTrue
import org.junit.Test

class PromptGroundingTest {
    @Test
    fun groundedPromptPreservesBaseSystemPromptAndAddsLocalContext() {
        val output = PromptGrounding.buildGroundedSystemPrompt(
            baseSystemPrompt = "You are helpful.",
            deviceStamp = "2026-06-03 20:53:00 UTC",
            pocketNodeHealthSummary = "model_loaded=true backend=OpenCL battery=78% charging=false thermal=moderate eligible=true"
        )

        assertTrue(output.startsWith("You are helpful."))
        assertTrue(output.contains("Current device time: 2026-06-03 20:53:00 UTC"))
        assertTrue(output.contains("Pocket Node is the local Android model host."))
        assertTrue(output.contains("Neo, Moolah, Watchdawg, Uno, and Edge Gate"))
        assertTrue(output.contains("Pocket Node health: model_loaded=true backend=OpenCL"))
        assertTrue(output.contains("Do not invent live node/service status."))
        assertTrue(output.contains("If service-state data is unavailable, answer live-status questions as UNKNOWN."))
    }

    @Test
    fun groundedPromptCanIncludeOptionalServiceState() {
        val output = PromptGrounding.buildGroundedSystemPrompt(
            baseSystemPrompt = "",
            deviceStamp = "2026-06-03 20:53:00 UTC",
            pocketNodeHealthSummary = "model_loaded=true backend=OpenCL",
            serviceStateSummary = "P20: healthy"
        )

        assertTrue(output.contains("Service-state: P20: healthy"))
    }
}
