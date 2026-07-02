# Pocket Node RC3.2 Foreground Service Validation

Date/time: 2026-07-02, 16:36–16:50 local
Branch: main
Starting HEAD: c3f6a48 ("P29: serialize Pocket Node model lifecycle")
Final HEAD: 1478686 ("P29: harden Pocket Node foreground service lifecycle")
Device: Samsung SM-F956U (Galaxy Z Fold 6), Android 16, serial RFCX60BRDWA
APK: app/build/outputs/apk/debug/app-debug.apk (debug build)

## Current-state inspection
Service type: `GenerationService` declared in `AndroidManifest.xml` as
`android:foregroundServiceType="dataSync"`, `android:exported="false"`.
Notification channel: existed already (`pocketnode_edge_api`, `IMPORTANCE_LOW`, created in
`onCreate()`), but the notification content was a single static string
(`"Running on port 11434 — tap to open app"`) set once in `onStartCommand()` and never updated
afterward.
Foreground behavior before fix: `startForeground()` was already called promptly in
`onStartCommand()` (good — no missing-foreground-window risk), but the notification never
reflected model-loading progress, serving eligibility, or thermal-blocked state, and offered no
action beyond tapping through to the app.
Start/stop paths before fix: `MainActivity`'s `LaunchedEffect(edgeApiEnabled, isPro)` called
`startForegroundService`/`stopService` based on the operator's Edge API toggle;
`BootReceiver` independently called `startForegroundService` on `ACTION_BOOT_COMPLETED`/
`ACTION_MY_PACKAGE_REPLACED` when `edge_api_enabled` was on. `onStartCommand` had no
action-awareness at all — every intent, regardless of its `action` string, ran the same
start/auto-load logic. There was no way to stop serving except through the app's own settings
toggle; no notification action existed.

## Fix design
Foreground service behavior: `onStartCommand()` now calls `startForeground()` unconditionally
and immediately for *every* intent, including the new explicit stop path — this satisfies
Android's requirement that any `startForegroundService()`-originated start promptly enter the
foreground state, and is a cheap no-op if already foregrounded. A private `ServingState` enum
(`STARTING`, `LOADING_MODEL`, `SERVING`, `BLOCKED`, `STOPPING`) drives notification content at
each real lifecycle transition already tracked by the RC3.1 coordinator/logging work — no new
polling loop was added.

Notification behavior: `buildNotification(state, detail)` produces distinct text per state:
"Starting Edge API on port 11434…", "Loading model…", "Serving on port 11434 — tap to open
app", "Unavailable: <reason>" (thermal-blocked or other ineligibility, using the exact same
`reason_if_not_eligible` string `/capabilities` already returns), and "Stopping…". A small,
additive, read-only `ApiServer.currentEligibility(app)` function was added
(`app/src/main/java/com/pocketnode/app/inference/ApiServer.kt`) that reuses the existing
private `readEligibility()` computation — no new threshold logic, no `/health`/`/capabilities`
schema change; it simply exposes `(eligible, reason)` for the notification to read. The
notification updates at transition points only (service start, model-load-start, model-load
success/failure, an explicit re-check after a load skip, and the stop path) — it does **not**
continuously poll thermal state in the background; see Follow-ups.

Service actions: added `GenerationService.ACTION_START_SERVING` (unused today, reserved for a
future explicit-start caller — default/no-action intents still take the existing start path
unchanged) and `GenerationService.ACTION_STOP_SERVING`. `onStartCommand` checks
`intent?.action == ACTION_STOP_SERVING` first; if matched, it logs
`ServiceHealthLog.EventType.STOP_ACTION_RECEIVED` (a new, additive enum value — the
`ServiceHealthLog.EventType` enum is only ever compared by equality elsewhere in the codebase,
never matched exhaustively, so this addition is safe), updates the notification to `STOPPING`,
calls `stopSelf(startId)`, and returns `START_NOT_STICKY` (an explicit operator stop must not be
immediately resurrected by the OS the way an OS-initiated kill under `START_STICKY` would be).

Stop behavior: the notification itself now carries a "Stop serving" action (visible only in the
`SERVING`/`BLOCKED` states, where stopping is meaningful — not while still starting/loading, and
not redundantly while already stopping) wired via `PendingIntent.getService(...)` targeting
`GenerationService` with `ACTION_STOP_SERVING`. `stopSelf()` triggers the existing, untouched
`onDestroy()` drain-then-free sequencing from RC2/RC3.1 (`ApiServer.stop()` drain, conditional
`nativeFreeContext`/`nativeFreeModel`, "leak over use-after-free on drain timeout" — none of
that logic was changed, only two log lines (`model_unload_start`/`model_unload_success`) were
already present from RC3.1 around it).

Restart behavior: unchanged and re-verified — `BootReceiver`'s existing
`ACTION_MY_PACKAGE_REPLACED`/`ACTION_BOOT_COMPLETED` handling, and `MainActivity`'s
toggle-driven start/stop, both still function exactly as before. One real-world nuance
confirmed during device testing (not a regression, a pre-existing Compose characteristic):
after the notification's "Stop serving" action stops the service, simply *resuming* the app
(without a true process relaunch) does **not** auto-restart the service, because
`LaunchedEffect(edgeApiEnabled, isPro)` only re-runs when those two specific values change, not
on every resume. A genuine cold start (or toggling the Edge API setting off/on) does correctly
restart it — verified below.

Interaction with `ModelLoadCoordinator`: unchanged from RC3.1 — `autoLoadModel()` still runs
entirely inside `ModelLoadCoordinator.withLifecycleLock("service_auto_load")`, with the same
re-check-under-lock idempotency guard. This phase added notification-refresh calls
(`refreshNotificationForCurrentEligibility`) at the points where a load succeeds, is skipped as
already-active, or fails — none of these touch locking behavior itself.

## Files changed
- `app/src/main/java/com/pocketnode/app/inference/GenerationService.kt`: added
  `ACTION_START_SERVING`/`ACTION_STOP_SERVING` constants, the `ServingState` enum, action-aware
  `onStartCommand` (stop path returns early with `START_NOT_STICKY`), `buildNotification`/
  `updateNotification`/`refreshNotificationForCurrentEligibility` helpers, and notification
  refresh calls at existing RC3.1 transition points. `onDestroy()`'s drain/free sequencing was
  **not** changed.
- `app/src/main/java/com/pocketnode/app/inference/ApiServer.kt`: added
  `fun currentEligibility(app: MainApplication): Pair<Boolean, String?>` — a small, additive,
  read-only wrapper around the existing private `readEligibility()`. No route, schema, or
  threshold changes.
- `app/src/main/java/com/pocketnode/app/diagnostics/ServiceHealthLog.kt`: added one new
  `EventType.STOP_ACTION_RECEIVED` enum value for postmortem-log distinction between an
  operator-triggered stop and an OS/system-triggered one. Verified no exhaustive `when` on this
  enum exists anywhere in the codebase (only equality checks in `DiagnosticsScreen.kt`), so this
  is a safe additive change.
- `POCKET_NODE_RC3_2_FOREGROUND_SERVICE_VALIDATION.md` (new): this document.

## Build result
assembleDebug: **PASS** — `BUILD SUCCESSFUL in 1m 3s`.

## Device/service verification
Process: confirmed live via `pidof` across install, launch, stop, and cold-restart cycles.
Foreground service confirmed: `dumpsys activity services com.pocketnode.app` showed
`isForeground=true`, `foregroundId=1`, `startCommandResult=1` (START_STICKY for the normal
path), and the live notification object attached to the service record.
Notification confirmed: `dumpsys notification --noredact` showed `android.text` transitioning
from `"Loading model…"` to (while genuinely thermally soft-blocked from prior test-run heat)
`"Unavailable: thermal_zone_cpu_soft_block"` — a real, unforced thermal state, not simulated —
proving the blocked-state wiring reads live eligibility correctly. A screenshot of the expanded
notification in the shade confirmed the exact title, body text, and a working `"Stop serving"`
action button.
Stop action confirmed: **yes**, exercised via actual UI interaction (the service is
intentionally `exported="false"`, so `adb shell am startservice -a ACTION_STOP_SERVING` was
correctly refused with `Requires permission not exported from uid` — confirming the security
boundary works as intended). Instead, the notification shade was expanded via
`adb shell cmd statusbar expand-notifications`, the Pocket Node notification was expanded via a
UI tap, and the "Stop serving" action button was tapped directly (`adb shell input tap`).
Logcat confirmed the exact expected sequence:
`STOP_ACTION_RECEIVED` → `model_unload_start` → `model_unload_success` → `SERVICE_STOPPED`, the
notification disappeared from the shade, and `/health` immediately became unreachable
(connection refused), confirming the Ktor server was genuinely stopped, not just the
notification hidden.
Restart confirmed: a mere app *resume* (not relaunch) after the stop did **not** restart the
service (expected Compose `LaunchedEffect` behavior, not a bug — see Fix design). A genuine
cold start (`am force-stop` + relaunch) correctly started a fresh service instance, reloaded the
model, and restored `/health`/`/capabilities` to a working, eligible state.

## API regression checks
`/health`: **PASS** — `{"status":"ok","node":"pocket-node","device":"android","model_loaded":true,...}`
`/capabilities`: **PASS** — all documented fields present; `eligible_for_inference` correctly
reflected real thermal state observed during testing.
`stream=false`: **PASS** — single consolidated `{"response": "...", "done": true}` object.
`stream=true`: **PASS** — NDJSON per-token streaming unchanged.

## Race regression
Iterations: 4 rapid `adb install -r` + `monkey` launch cycles (bounded per instructions), run
after all foreground-service changes were installed.
ggml_abort observed: **no** — zero matches across the full stress-window logcat scan.
SIGABRT observed: **no** — same scan, zero matches.
Duplicate load observed: the RC3.1 coordinator's serialization behavior was preserved and
exercised again during this phase's testing (see `model_load_start`/`SERVICE_STARTED`/
`PACKAGE_REPLACED_START_ATTEMPTED` sequences in logcat across all 4 iterations); no uncontrolled
concurrent native load occurred.
Coordinator logs observed: **yes** — `PACKAGE_REPLACED_START_ATTEMPTED`, `SERVICE_STARTED`,
`model_load_start`, and (in one iteration where a rapid reinstall raced the service's own
in-flight start) `STOP_DRAIN_STARTED`/`STOP_DRAIN_OK`/`SERVICE_STOPPED` all appeared in the
expected order, with the app recovering cleanly afterward (final `pidof` check confirmed a live,
responding process after the loop, once given a moment to settle).

One benign observation during the stress loop: a system log line
`"Bringing down service while still waiting for start foreground"` appeared once, when a rapid
reinstall killed the process before a `startForeground()` call had fully registered with the OS
— this is the expected consequence of intentionally aggressive back-to-back reinstalls, not a
defect in this phase's code; the subsequent iteration's fresh process started cleanly.

## Verdict
**PASS**

- `assembleDebug` passes with no regressions.
- The foreground service is now explicit and observable: a live-updating notification shows
  starting/loading/serving/blocked/stopping state, sourced from the same eligibility
  computation `/capabilities` already uses, with no schema change.
- A working "Stop serving" notification action was implemented, verified via real UI
  interaction (not just code review), and correctly drives the existing, unmodified
  drain-then-free `onDestroy()` sequencing.
- App-upgrade/reinstall restart behavior (`BootReceiver`'s `ACTION_MY_PACKAGE_REPLACED` path)
  was re-verified working, still using the RC3.1 `ModelLoadCoordinator` with no duplicate loads.
- No new inference features; no `/health`/`/capabilities`/`/api/generate` schema changes beyond
  the additive, unused-by-HTTP `ApiServer.currentEligibility()` helper; no thermal threshold
  changes; no Neo/LiteLLM/routing changes.
- 4-iteration bounded race regression check: zero `ggml_abort`/`SIGABRT`, coordinator logs
  present and correct, API still works after restart.

## Follow-ups
- The notification only refreshes at known lifecycle transition points (start, load-start,
  load-success/failure/skip, stop) — it does not continuously poll thermal/eligibility state in
  the background, so a thermal block that begins *after* the notification last updated (e.g.
  the device heats up during a long generation with the notification already showing "Serving")
  will not be reflected until the next transition. Adding a lightweight periodic refresh (or
  having `ApiServer` push a state-change event) is a reasonable enhancement for a later phase,
  not required for this one.
- Resuming (not relaunching) the app after an explicit "Stop serving" action does not
  auto-restart the service, due to `LaunchedEffect(edgeApiEnabled, isPro)` only firing on value
  change. If auto-recovery-on-resume is desired, `MainActivity` would need an explicit
  "service running?" check in `onResume`/a similar lifecycle hook — a deliberate UX decision for
  a future phase, not assumed or implemented here.
- `ACTION_START_SERVING` was added as a named constant for symmetry and future use (e.g. a
  future settings-screen "Start serving" button that wants an explicit, self-documenting
  action rather than a bare intent) but is not yet consumed anywhere — default/no-action start
  behavior is unchanged and remains the only start path actually exercised today.
- No settings-UI changes were made in this phase, per the explicit instruction not to build
  that out yet — the notification's stop action is the only new operator-facing control
  surface added.
