# Pocket Node RC3.5 Boot Survival Validation

Date/time: 2026-07-02, 18:05–18:20 local
Branch: main
Starting HEAD: e72aad5 ("P29: close model reverify validation evidence")
Final HEAD: (see final response — inserted after this phase's commit is created)
Device: Samsung SM-F956U (Galaxy Z Fold 6), Android 16, serial RFCX60BRDWA
APK: app/build/outputs/apk/debug/app-debug.apk (debug build, unchanged from RC3.4.1 — no
source changes were needed this phase)

## Current-state inspection
Boot receiver actions: `BootReceiver` listens for exactly two actions —
`android.intent.action.BOOT_COMPLETED` and `android.intent.action.MY_PACKAGE_REPLACED` — and
ignores everything else. Both are handled by the same code path: read
`edge_api_enabled` from DataStore, and if true, call `startEdgeApiService()`
(`ContextCompat.startForegroundService(...)` with a plain, no-action `Intent`, wrapped in a
try/catch for `ForegroundServiceStartNotAllowedException` on Android 12+). Intentionally does
**not** handle `LOCKED_BOOT_COMPLETED` — the code comment explains this correctly:
DataStore/app files are encrypted at rest before first unlock, so nothing useful could be read
that early in boot anyway.
Manifest receiver: `<receiver android:name=".BootReceiver" android:exported="false">` with an
`<intent-filter>` containing both actions — matches the code exactly, `exported="false"` is
correct (no other app should be able to trigger this receiver).
Boot permission: `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />`
present in the manifest.
Upgrade restart path: `ACTION_MY_PACKAGE_REPLACED` (fired by the OS after every
`adb install -r`/Play-Store-style upgrade) → `BootReceiver` → `ServiceHealthLog.record(
PACKAGE_REPLACED_START_ATTEMPTED)` → `startEdgeApiService()` → `GenerationService` starts →
RC3.1's `ModelLoadCoordinator`-guarded `autoLoadModel()` runs. Already exercised and proven
safe across all of RC3.1–RC3.4's device testing.
Cold start path: this is a **different** code path — not `BootReceiver` at all.
`MainActivity`'s `LaunchedEffect(edgeApiEnabled, isPro)` fires on first composition once the
`edgeApiEnabled`/`isPro` `StateFlow`s emit their first values (which happens naturally on a
fresh process/Activity, since Compose treats "no prior value → first value" as a key change),
and if both are true, calls `startForegroundService(...)` directly from the Activity — the
same underlying `GenerationService` start, just triggered by the app being opened rather than
by a system broadcast.
Manual stop behavior: confirmed still exactly as documented in RC3.2 — stopping the service
(via the "Enable Edge API" toggle in Settings, calling `stopService()`) does not get
auto-restarted by a mere Activity *resume* (since `edgeApiEnabled`/`isPro` haven't changed
value), only by a genuine cold start (new process) or by explicitly re-enabling the toggle.
Auto-start setting: **yes** — `edge_api_enabled`, a DataStore boolean, defaults to false/off
until the operator explicitly turns on "Enable Edge API" in Settings. Both `BootReceiver` and
`MainActivity`'s `LaunchedEffect` gate their start attempts behind this same flag — there is
exactly one source of truth for "should this app be serving," not two independent ones.
Service start mode: `START_STICKY` for the default/normal start path (OS may restart the
service after a low-memory kill); `START_NOT_STICKY` for the explicit `ACTION_STOP_SERVING`
path (an operator-requested stop must not be resurrected by the OS).
Foreground notification behavior: unchanged from RC3.2/RC3.3 — live `ServingState`-driven text
(Starting/Loading model…/Serving/Unavailable: reason/Stopping…), with a "Stop serving" action
visible while Serving or Unavailable.

## Changes made
None. Inspection and device testing found the existing lifecycle implementation to already be
correct and safe; per this phase's own scope rule ("only make source changes if inspection or
device testing shows a real lifecycle bug"), no source files were modified.

## Build result
assembleDebug: **PASS** — `BUILD SUCCESSFUL in 58s` (no source changed, confirms the already-
built RC3.4.1 APK is what was tested).

## Upgrade survival
Iterations: 5 rapid `adb install -r` cycles, each followed by a 3-second settle and an
immediate `/health` check.
`ACTION_MY_PACKAGE_REPLACED` observed: **yes, all 5 iterations** —
`PACKAGE_REPLACED_START_ATTEMPTED` → `SERVICE_STARTED` → `model_load_start` logged in that
exact order for every iteration (pids 32332, 999, 3765, 4713, 4929).
Duplicate load observed: **no** — exactly one `model_load_start` per iteration, no
`model_load_join_inflight` this round (each cycle completed cleanly before the next reinstall
began).
ggml_abort observed: **no** — zero matches across the full logcat scan for the entire probe
window.
SIGABRT observed: **no** — same scan, zero matches.
Service recovered: **yes** — every iteration's `/health` call returned `{"status":"ok",...}`
within 3 seconds of the install completing (model still loading at that point, which is
correct/expected — `/health` reports server-alive state, not model-loaded state).

## Manual stop / cold-start recovery
Stop action: used the existing "Enable Edge API" toggle in Settings (an already-existing UI
control, calling the same `stopService()` path documented since RC2) rather than the
notification action this time — the notification shade proved difficult to interact with
reliably via ADB UI automation in this session (an accidental tap landed on an unrelated
Messages notification's reply compose field; backed out immediately with no message sent, no
app state affected). The toggle-based stop is functionally equivalent for this phase's purpose
and is itself one of the app's real, existing operator controls.
Health unreachable after stop: **yes** — confirmed `curl` returned "Empty reply from server"
immediately after toggling the setting off; logcat confirmed
`STOP_DRAIN_STARTED`/`STOP_DRAIN_OK`/`SERVICE_STOPPED` in the correct order.
Cold start: re-enabled the toggle (to restore the "operator wants Edge API on" precondition),
confirmed served again, then used `adb shell am force-stop com.pocketnode.app` (killing the
entire process, the closest ADB-available equivalent to a genuine cold start) followed by
`adb shell monkey -c android.intent.category.LAUNCHER` to relaunch.
Health restored: **yes** — `/health` returned `{"status":"ok",...,"model_loaded":false}`
within 8 seconds of relaunch (model still loading), and `model_loaded:true` within the next
polling cycle.
Capabilities restored: **yes** — `eligible_for_inference: true` once the model finished
loading; schema unchanged.
Diagnostics state: **confirmed live on-device via screenshot** — caught mid-load
("Loading model…", Server alive: true, Eligible: false, Reason: model_not_loaded) in one
screenshot, then, after tapping "Refresh now" ~20 seconds later, showed **"Serving"** in green,
Eligible: true, Reason: —, with the correct active session model name. This is the full
Loading → Serving transition, observed directly in the Diagnostics UI, not inferred.

## Real reboot test
Performed: **no**. This device is the user's personal, actively-used Fold6 (not a disposable
test rig) — a full reboot is disruptive (closes all running apps, requires re-unlock, interrupts
whatever else the user has running) for a check whose underlying code path is already strongly
validated by other means: `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED` are handled
by the exact same `BootReceiver.onReceive()` → `startEdgeApiService()` function, differing only
in which system broadcast triggers it and in the `ServiceHealthLog` event type recorded
(`BOOT_START_ATTEMPTED` vs. `PACKAGE_REPLACED_START_ATTEMPTED`). The `MY_PACKAGE_REPLACED` path
was exercised 5 times in this phase with zero failures, giving high confidence in the shared
code path without requiring the more disruptive full-boot test.
Auto-start after boot: not directly observed this phase (see above) — based on shared code path
with the verified `MY_PACKAGE_REPLACED` path, expected to work identically when
`edge_api_enabled` is on, subject to the same Android 12+
`ForegroundServiceStartNotAllowedException` caveat already documented and handled
(logs `BOOT_START_DENIED`, relies on `MainActivity`'s own cold-start recovery on next app open —
itself independently verified in this phase).
Manual launch after boot: not applicable (reboot not performed).
Health: not applicable (reboot not performed).
Capabilities: not applicable (reboot not performed).
Conclusion: the boot-specific code path was not directly exercised via a physical reboot in
this phase, but its two real-world triggers (upgrade-restart and app-cold-start-after-manual-
stop) were both directly verified with zero failures using the identical underlying start
logic. This is a deliberate, judgment-based scope decision to avoid unnecessary disruption to
the user's device, not an oversight.

## API regression
`stream=false`: **PASS** — `{"response": "...", "done": true}` returned correctly after cold-
start recovery.
`stream=true`: **PASS** — NDJSON per-token streaming confirmed working, process remained stable
(same pid, 13584, throughout).

## Verdict
**CONDITIONAL**

- Build passes (no source changes needed).
- Upgrade survival (5 iterations of `install -r` / `ACTION_MY_PACKAGE_REPLACED`): **PASS** — no
  duplicate loads, no `ggml_abort`/`SIGABRT`, service recovers correctly every time.
- Manual stop behaves safely: **PASS** — stopping via the existing Settings toggle correctly
  drains and stops the server; `/health` becomes unreachable; no crash.
- Cold-start recovery: **PASS** — a genuine process kill (`force-stop`) followed by relaunch
  correctly restarts serving, reloads the model, and restores `/health`/`/capabilities`;
  Diagnostics screen directly confirmed the Loading → Serving transition.
- No `ggml_abort`/`SIGABRT` anywhere in this phase's testing.
- API regression (`stream=false`, `stream=true`) both pass.
- Evidence doc written (this document).
- The only gap: a **real physical device reboot** was deliberately not performed, to avoid
  disrupting the user's actively-used personal device, given the underlying code path is
  already proven via the equivalent, identically-coded `ACTION_MY_PACKAGE_REPLACED` trigger.
  Per this phase's own verdict rules ("Mark CONDITIONAL if: everything passes except real
  reboot was skipped or boot autostart is intentionally absent"), this is exactly that case —
  CONDITIONAL, not FAIL, and not a blocker to proceeding.

## Follow-ups
- If a real reboot test is ever wanted (e.g. before a public release candidate), schedule it at
  a time convenient for the device owner rather than mid-development-session, since it is
  disruptive to normal device use.
- The resume-does-not-auto-restart nuance (documented since RC3.2) remains a known, deliberate
  characteristic, not a bug — no action needed unless a future UX decision wants auto-recovery
  on resume specifically (would require an explicit `onResume` check, out of scope here).
- No RC3.6 (generic ARM64 flavor) work was started here, per this phase's explicit scope
  boundary.
