# Pocket Node RC3.3 Diagnostics Validation

Date/time: 2026-07-02, 16:56–17:02 local
Branch: main
Starting HEAD: 1478686 ("P29: harden Pocket Node foreground service lifecycle")
Final HEAD: (see final response — inserted after this phase's commit is created)
Device: Samsung SM-F956U (Galaxy Z Fold 6), Android 16, serial RFCX60BRDWA
APK: app/build/outputs/apk/debug/app-debug.apk (debug build)

## Current-state inspection
Existing diagnostics files: `app/src/main/java/com/pocketnode/app/ui/screens/DiagnosticsScreen.kt`,
`app/src/main/java/com/pocketnode/app/diagnostics/DiagnosticsViewModel.kt`,
`DiagnosticMetrics.kt`, `HardwareMetricsProvider.kt`, `ThermalZoneReader.kt`,
`ServiceHealthLog.kt`. The diagnostics screen is reached via
Settings → Developer → "Engine Diagnostics".

Existing data sources found (more mature than expected — this was hardening, not
greenfield): a 1-second auto-refresh loop already polling `HardwareMetricsProvider.snapshot()`
into a `DiagnosticMetrics` StateFlow (already including OS thermal-zone peak fields:
`peakThermalZoneC`/`peakCpuZoneC`/`peakGpuZoneC`/`batteryTemperatureC` from B.3); a live
`ServiceHealthLog.events` StateFlow (newest-first) already rendered as a "Service Health" card;
existing "Engine"/"Memory"/"Hardware"/"Model"/"Context" cards already covering backend, TPS/TTFT,
JVM/native memory, device info, active model/SHA-256/verification status, and context fill.

Gaps found against the mission's desired sections: no "Service state" summary (serving/
stopped/loading/unavailable) anywhere — only the raw event log; no "Thermal / Eligibility"
section using the actual `eligible_for_inference`/`reason_if_not_eligible` gate decision (the
existing "Hardware" card's `thermalLabel` is the *different*, PowerManager-based
`thermalStatus`, not the OS-thermal-zone eligibility gate `ApiServer` actually enforces); no
"Last error" visibility; no explicit manual refresh action (data was live via the 1s poll and
reactive Flows, but no operator-triggered "refresh now").

## Fix design
Sections added/updated: two new cards — **Service** (State, Server alive, Uptime, Active
session model, Last inference, Last error) and **Thermal / Eligibility** (Eligible for
inference, Reason, Peak OS/CPU/GPU zone, Battery temp) — inserted at the top of the existing
scrollable column, above the pre-existing Engine/Memory/Hardware/Model/Context/Service Health
cards (all left untouched). A "Refresh now" text button was added above the Service card.

Data sources used: the new `ServiceSnapshot` is built entirely from data these components
*already* compute internally — `ApiServer.currentEligibility(app)` (added in RC3.2),
`MainApplication.activeSession` (existing shared session reference), the most recent
`ServiceHealthLog` event (existing), and the existing `hardware` StateFlow's thermal-zone
fields for the temperature rows. No new polling loop, no ADB/logcat dependency, no new
permissions, no network/loopback HTTP calls.

New read-only helpers: `ApiServer.ApiStatusSummary` (data class) +
`ApiServer.currentStatusSummary()` — mirrors the same `serverAlive`/`uptimeMs`/
`lastInferenceAt`/`lastError` state `/health` and `/capabilities` already expose over HTTP,
exposed in-process for the UI; no route or schema changed. `DiagnosticsViewModel.ServiceSnapshot`
(data class, with a `stateLabel` computed property deriving "Serving"/"Loading model…"/
"Unavailable: <reason>"/"Stopped" the same way `GenerationService`'s own `ServingState`
notification logic does, without depending on that private, instance-bound enum) and
`DiagnosticsViewModel.refreshNow()` (re-reads the same live sources the automatic 1s loop
already polls).

No secrets/private URLs exposed: the new sections show only data already visible via
`/capabilities` (thermal reason strings, temperatures, eligibility) or already visible
elsewhere in the app (model name/path, backend). No API keys, Tailscale addresses, or other
homelab-internal identifiers are introduced.

## Files changed
- `app/src/main/java/com/pocketnode/app/inference/ApiServer.kt`: added `ApiStatusSummary` data
  class and `currentStatusSummary()` — additive, read-only, no schema change.
- `app/src/main/java/com/pocketnode/app/diagnostics/DiagnosticsViewModel.kt`: added the
  `ServiceSnapshot` data class, a `serviceSnapshot` StateFlow, `refreshNow()`, and folded the
  new snapshot construction into the existing 1-second refresh loop (no new loop/timer).
- `app/src/main/java/com/pocketnode/app/ui/screens/DiagnosticsScreen.kt`: added the "Refresh
  now" button, the "Service" card, and the "Thermal / Eligibility" card, plus small private
  helpers (`serviceStateColor`, `formatUptime`, `formatTempC`). All pre-existing cards/logic
  unchanged.
- `POCKET_NODE_RC3_3_DIAGNOSTICS_VALIDATION.md` (new): this document.
- `POCKET_NODE_RC3_2_FOREGROUND_SERVICE_VALIDATION.md`: trivial Final-HEAD SHA-fill correction
  left over from the prior phase's commit.

## Build result
assembleDebug: **PASS** — `BUILD SUCCESSFUL in 1m 3s`.

## API regression checks
`/health`: **PASS** — `{"status":"ok","node":"pocket-node","device":"android","model_loaded":true,...}`
`/capabilities`: **PASS** — all documented fields present, unchanged schema.
`stream=false`: **PASS** — single consolidated `{"response": "...", "done": true}` object
(verified twice — once before, once after the on-device UI walkthrough).

## UI verification
Diagnostics screen reachable: **yes** — Chat screen → gear icon → Settings → Developer →
"Engine Diagnostics", confirmed via live screenshots and UI taps (not just code review).
Service state visible: **yes** — observed all four states live, driven by genuine device
behavior during this session (not simulated): green "Serving" (initial load), red "Stopped"
(after using the RC3.2 notification "Stop serving" action), "Loading model…" (mid cold-restart),
and amber "Unavailable: thermal_zone_hard_block" (a real, unforced thermal hard-block from
the repeated test loads in this and prior sessions).
Model state visible: **yes** — existing "Model" card (Active model, Resolved file, Loaded,
Verification: VERIFIED, Role: Primary model, Size, SHA-256 prefix) rendered correctly throughout,
unaffected by this phase's changes.
Thermal/eligibility visible: **yes** — new "Thermal / Eligibility" card showed live
`eligible_for_inference` (green/red), the exact gate `reason` string (e.g.
`thermal_zone_hard_block`), and Peak OS/CPU/GPU zone temperatures with zone-type labels, matching
`/capabilities` field-for-field.
Recent events visible: **yes** — existing "Service Health" card correctly showed the new
`stop action received` event (added in RC3.2) alongside `stop drain started`/`stop drain ok`/
`service stopped`/`service started`, newest-first, unaffected by this phase.
Last error/no-error visible: **yes** — "Last error" row in the Service card showed "None"
throughout this session (no errors occurred); the row is wired to show the actual error string
via `ApiServer.currentStatusSummary().lastError` if one occurs.
Refresh behavior: **yes** — tapped "Refresh now" mid-session and observed the Service/Thermal
cards update immediately to the live state (state label, temperatures, and reason all changed
to reflect the real-time thermal hard-block that had occurred), confirming `refreshNow()`
correctly re-reads the same live sources as the automatic 1-second loop.

## Stop/restart visibility
Stopped state shown: **yes** — after triggering the RC3.2 "Stop serving" notification action
(exercised via real UI tap, not adb), reopening the app landed back on the Diagnostics screen
(activity state preserved) and the Service Health list showed the new
`stop action received → stop drain started → stop drain ok → service stopped` sequence; after
scrolling up, the Service card's State row showed **"Stopped"** in red, Server alive **false**,
Uptime **"Not running"**.
Restart/loading/serving state shown: **yes** — after `am force-stop` + relaunch (a genuine cold
start, since a mere resume does not restart the service per the RC3.2 follow-up), the
Diagnostics screen was reached again and showed **"Loading model…"** while the model was still
loading (Server alive true, Eligible false, Reason `model_not_loaded`), then — after using
"Refresh now" — the real device had, by that point, crossed into a genuine thermal hard-block
from the cumulative test load, correctly shown as **"Unavailable: thermal_zone_hard_block"**
(amber) with live temperature values. The initial "Serving" (green) state had already been
directly observed earlier in this same session, at the very start of the UI verification pass,
before any stop/restart cycle — so all four states (Serving, Loading, Unavailable, Stopped)
were each independently confirmed rendering correctly at some point in this validation pass.

## Verdict
**PASS**

- `assembleDebug` passes with no regressions.
- The diagnostics screen now surfaces service state, model state, thermal/eligibility state,
  recent events, and last-error visibility, sourced entirely from existing internal data
  (`ApiServer`, `ServiceHealthLog`, `MainApplication.activeSession`, `ThermalZoneReader`) with
  zero ADB/logcat dependency and zero new secrets/private-URL exposure.
- A manual "Refresh now" action was added and verified to correctly re-read live state on
  demand.
- All four coarse service states (Serving/Loading/Unavailable/Stopped) were independently
  observed rendering correctly against genuine, unforced device behavior during this session —
  not simulated or mocked.
- Stop/restart visibility fully confirmed: the Diagnostics screen accurately reflected the
  RC3.2 stop action and the subsequent cold-restart recovery sequence.
- No new inference features; no `/health`/`/capabilities`/`/api/generate` schema changes beyond
  the additive, HTTP-invisible `ApiServer.currentStatusSummary()`; no thermal threshold changes;
  no native/lifecycle code touched; no Neo/LiteLLM/routing changes.

## Follow-ups
- The Service card's "Active session model" can show a stale value briefly after a stop, since
  it reads `MainApplication.activeSession` which RC3.2's `onDestroy()` only clears when it
  matches the service's own owned context pointer — this is existing, documented dual-ownership
  behavior from the RC3.1 follow-ups list, not something this phase introduced or needs to fix.
- The "Reason" row in Thermal/Eligibility shows the raw `reason_if_not_eligible` string (e.g.
  `thermal_zone_hard_block`) rather than a friendlier operator-facing label — acceptable for an
  engineering diagnostics screen per this phase's scope, but a future polish pass could map
  these to human-readable phrases if the diagnostics screen is ever shown to non-technical
  operators.
- No unit/instrumentation tests were added for `ServiceSnapshot`/`refreshNow()`, consistent with
  RC3.1's finding that this project has no existing test source sets or framework wired into the
  Gradle build; verification here relied on live device screenshots and UI interaction instead.
