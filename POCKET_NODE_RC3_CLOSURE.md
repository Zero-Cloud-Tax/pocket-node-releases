# Pocket Node RC3 Closure

Date/time: 2026-07-02, 18:25 local
Branch: main
Closure commit: (see final response — inserted after this phase's commit is created)
Validated tag: v0.1.0-rc3-p29

## Baseline
RC2 validated tag: v0.1.0-rc2-p28 → e2e56cd
RC3 starting point: 46519aa ("P29: plan Pocket Node RC3 hardening"), branched directly off the
RC2-closed `main` (d159df0) with no intervening unrelated work.

## RC3 workstreams
RC3.1 Model-load race serialization (`c3f6a48`): added `ModelLoadCoordinator`, a single shared
Kotlin mutex now used by both `GenerationService.autoLoadModel()` and `ChatViewModel`'s own
native load/unload/draft/benchmark call sites, plus a second, native-side `g_lifecycle_mutex`
serializing `nativeLoadModel`/`nativeFreeModel`/`nativeCreateContext`/`nativeFreeContext` (and
their draft-model counterparts) as defense-in-depth. The targeted `ggml_abort` race from RC2
was directly reproduced under an 8-iteration rapid install/relaunch stress loop and confirmed
serialized correctly (`model_load_join_inflight` observed live), with zero crashes.

RC3.2 Foreground service lifecycle (`1478686`): made `GenerationService.onStartCommand`
action-aware (`ACTION_STOP_SERVING`, reserved `ACTION_START_SERVING`), added a live
`ServingState`-driven foreground notification (Starting/Loading model…/Serving/
Unavailable: reason/Stopping…) sourced from a new, additive, HTTP-invisible
`ApiServer.currentEligibility()` helper, and a "Stop serving" notification action wired to the
existing drain-then-free `onDestroy()` sequencing (left untouched). Verified via real UI
interaction: tapped the notification action, confirmed the server stopped
(`STOP_ACTION_RECEIVED` → `model_unload_start` → `model_unload_success` → `SERVICE_STOPPED`),
and confirmed cold-restart recovery.

RC3.3 Diagnostics/logs screen (`b38b33e`): added a "Service" card (state/uptime/active
session/last inference/last error) and a "Thermal / Eligibility" card (eligible/reason/peak
OS·CPU·GPU zone temperatures/battery temp) to the existing Diagnostics screen, plus a "Refresh
now" action, all sourced from data `ApiServer`/`ThermalZoneReader` already compute internally —
no ADB/logcat dependency, no schema change. All four coarse service states (Serving/Loading/
Unavailable/Stopped) were independently observed rendering correctly against genuine, unforced
device behavior during testing.

RC3.4 Model inventory UX (`971084c`): added a per-model "Reverify" action (reusing the existing
`ModelArtifactManager.validateExistingFile()` primitive already proven by the import path) and
an "Export Inventory" action (writing a JSON summary via the app's existing FileProvider
`downloads` path, the same one `AppUpdater` already uses, launching a standard share-sheet
intent). No new quarantine logic was added — the existing bulk "Clean Failed Primary" action
already covers that, reused as-is per this phase's own scope instruction.

RC3.4.1 Reverify evidence closure (`e72aad5`): RC3.4 initially closed CONDITIONAL because the
Reverify action's exact Room DB write-back wasn't independently captured (no `sqlite3` on
device, WAL not checkpointed on a plain file pull). Added three explicit log lines
(`model_reverify_start`/`model_reverify_success`/`model_reverify_failed`) — observability only,
no change to validation semantics — and captured a live success snackbar plus matching logcat
output whose SHA-256 prefix matched the earlier exported inventory JSON exactly, independently
confirming correct execution. Verdict upgraded to PASS.

RC3.5 Boot survival re-verification (`47fefd3`): re-verified upgrade survival (5 rapid
`install -r` cycles, each a clean, non-duplicate `ACTION_MY_PACKAGE_REPLACED` →
`SERVICE_STARTED` → `model_load_start` sequence, zero `ggml_abort`/`SIGABRT`), manual-stop
safety (via the existing "Enable Edge API" Settings toggle), and cold-start recovery
(`force-stop` + relaunch correctly restored serving, confirmed live in the Diagnostics UI
showing the Loading → Serving transition). No source changes were needed — inspection found
the existing `BootReceiver`/`MainActivity` lifecycle logic already correct. Closed CONDITIONAL
only because a physical device reboot was deliberately not performed, to avoid disrupting the
user's actively-used personal Fold6 — the underlying code path is proven via the identically-
coded `ACTION_MY_PACKAGE_REPLACED` trigger instead.

## Gates
Build: PASS (`assembleDebug` across every RC3 phase; `assembleRelease` unaffected, still fails
only on the expected missing HMAC secret per RC2)
Install/upgrade: PASS (verified repeatedly across RC3.1–RC3.5, including 5+8 rapid reinstall
stress cycles, always preserving model/Room DB data, never requiring an uninstall)
Health: PASS
Capabilities: PASS
stream=false: PASS
stream=true: PASS
Model-load race: PASS (RC3.1 — race directly reproduced and correctly serialized, zero
`ggml_abort` after the fix)
Foreground service: PASS (RC3.2 — live state-driven notification verified on-device)
Stop action: PASS (RC3.2/RC3.5 — verified via real UI tap and via the Settings toggle)
Cold restart: PASS (RC3.2/RC3.5 — verified via `force-stop` + relaunch, Diagnostics UI
confirmed Loading → Serving)
Diagnostics: PASS (RC3.3 — Service and Thermal/Eligibility cards verified live, all four
service states observed)
Model inventory export: PASS (RC3.4 — real share sheet, JSON pulled and inspected, valid)
Model reverify: PASS (RC3.4.1 — log evidence + snackbar + matching SHA-256 prefix)
Upgrade survival: PASS (RC3.5 — 5-iteration stress, zero duplicate loads, zero crashes)
Physical reboot: NOT PERFORMED (deliberately skipped — see RC3.5 rationale above)

## RC3 verdict
**CONDITIONAL_PASS**

Recommended verdict:
CONDITIONAL_PASS

Reason:
Physical reboot intentionally skipped on daily-use Fold6. All non-disruptive lifecycle,
upgrade, cold-start, foreground, diagnostics, model inventory, and API gates passed.

## Deferred work
1. Physical reboot proof, optional.
2. Generic ARM64/RK3576 build flavor, optional RC3.6 or later.
3. Further operator UX polish.
4. Public release notes if desired.
