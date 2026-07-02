# Pocket Node RC3 Plan

Date/time: 2026-07-02, 16:00 local
Starting branch: main
Starting HEAD: d159df0 ("Merge origin/main into P28 RC2 closure branch")
Validated RC2 tag: v0.1.0-rc2-p28 → e2e56cd (published to origin)
Planning scope: this document only. No app, native, or Neo/routing changes were made. No
thermal tests were run. This is planning, not implementation.

## RC2 baseline
RC2 (closed via P28, tags `v0.1.0-rc2-p28`/historical `v0.1.0-rc2`) proved, with live evidence:
Android build (`assembleDebug` PASS, `assembleRelease` fails only on expected missing HMAC
secret), APK install/upgrade over the existing Fold6 app with data/model preservation,
`/health` and `/capabilities` API contracts, `/api/generate` correctness for both `stream=false`
(fixed in Phase 1 — previously silently ignored and always streamed) and `stream=true`, native
JNI UTF-8 callback stability (fixed in Phase 2 — a token-boundary UTF-8 split previously
aborted the whole process via `NewStringUTF`), the Fold6-optimized native build baseline
(`-march=armv8.4-a+dotprod+i8mm`, restored and committed as an explicit decision in Phase 2.6),
real thermal hard-block and cooldown/hysteresis behavior (Phase 3 — genuine 78.7°C hard-block
from a single generation, clean recovery once below the 58°C cooldown line), and live
route-away proof against the actual homelab Neo edge gate (thermal-aware
`fold6_preflight_thermal_v2` policy correctly routing to `mac-studio-edge-fallback` when the
Fold6 is ineligible). All of this is committed and pushed; the validated tag is published.

RC2 did **not** touch app lifecycle/service robustness, diagnostics UX, model-inventory UX, or
boot behavior beyond what already existed. Inspection for this plan found that several of
those areas already have real (if partial) implementations already in the codebase — this is
not greenfield work; RC3 is about hardening and completing what's there.

## RC3 principle
Hardening and operator UX only. No new inference features unless directly required for
reliability (e.g. serializing model load is a reliability fix, not a feature). No changes to
`/health`, `/capabilities`, `/api/generate`, or any other public API contract unless a
hardening fix genuinely requires it — and if so, treat it with the same care as RC2's
`stream=false` fix (small, additive, documented, not a silent rename).

## Workstream A — Model-load race hardening
Problem: Phase 2.5/2.6 observed a native `ggml_abort()` crash (distinct from the already-fixed
`NewStringUTF` UTF-8 bug) during an install/model-load race — a second model load appeared to
overlap a first one still in flight after a rapid `adb install -r` + relaunch cycle.

Evidence from RC2: logcat showed two `nativeLoadModel`/`nativeCreateContext` sequences
(different thread ids) racing within about a second of each other around the crash timestamp,
immediately after a reinstall. This was documented as an out-of-scope, unrelated finding in
`POCKET_NODE_RC2_CHECKPOINT.md` and `POCKET_NODE_RC2_PHASE2_UTF8_VALIDATION.md` follow-ups.

Likely root causes, confirmed by direct code inspection in this planning pass (not yet fixed):
- `GenerationService.onStartCommand()` (`app/src/main/java/com/pocketnode/app/inference/GenerationService.kt:76-78`)
  unconditionally launches `autoLoadModel()` on `serviceScope` whenever `app.activeSession == null`
  at service start, with **no lock or in-flight guard** — if `onStartCommand` fires twice in
  quick succession (e.g. the OS restarting a `START_STICKY` service, or a manual start
  overlapping a `START_STICKY` auto-restart), two `autoLoadModel()` coroutines can run
  concurrently, both calling `nativeLoadModel`/`nativeCreateContext` against the same native
  `llama.cpp`/ggml global state.
- `BootReceiver.kt` explicitly listens for `Intent.ACTION_MY_PACKAGE_REPLACED` (an app
  upgrade/reinstall) and calls `ContextCompat.startForegroundService(...)` when the
  `edge_api_enabled` preference is on (`BootReceiver.kt:21-37`). This is a second, independent
  path that can start `GenerationService` right after an `adb install -r`, at almost exactly the
  moment the OS/launcher may also be (re)starting the service or the user is relaunching the
  app manually — three plausible starters (BootReceiver's `PACKAGE_REPLACED`, OS respawn of a
  `START_STICKY` service, manual relaunch) with no shared coordination.
- `MainApplication.activeSession` (`MainApplication.kt:24-25`) is a plain `@Volatile var`, not
  guarded by any lock. Both `GenerationService.autoLoadModel()` and `ChatViewModel`'s own
  model-switch path (`ChatViewModel.kt:354-395`) independently call
  `nativeLoadModel`/`nativeCreateContext` and then assign `activeSession` — there is currently
  no single serialization point across *all* model-load call sites, only the existing
  `g_inference_mutex` inside native `nativeGenerate` (which guards generation, not loading).

Inspection targets: `GenerationService.onStartCommand`/`autoLoadModel`, `BootReceiver.onReceive`,
`MainApplication.activeSession`, `ChatViewModel`'s model-switch code path, and the native
`nativeLoadModel`/`nativeCreateContext`/`nativeFreeModel`/`nativeFreeContext` JNI functions in
`pocketnode_jni.cpp` (confirm whether they touch any process-global/static ggml/llama.cpp state
that isn't inherently safe under concurrent calls with different pointers).

Implementation options (to be decided during RC3.1, not here):
1. Introduce a single app-wide `Mutex` (Kotlin coroutines) around every model-load/unload call
   site (`GenerationService.autoLoadModel`, `ChatViewModel`'s model switch, any future reverify/
   quarantine flow that unloads a model) so at most one load/unload is ever in flight.
2. Add an idempotency/in-flight guard so a second `autoLoadModel()` invocation while one is
   already running becomes a no-op (check-then-launch under the same mutex, not a bare
   `activeSession == null` check).
3. Consider whether `BootReceiver`'s `PACKAGE_REPLACED` start should be suppressed if
   `GenerationService` is already running/starting (needs an app-level "service starting" flag,
   since `Service` lifecycle alone doesn't expose this cleanly).
4. Optionally push serialization into native code as a second mutex guarding model
   load/create/free (symmetric with the existing `g_inference_mutex` for generation), so the
   guarantee holds even if a future Kotlin call site forgets to take the app-level lock.

Acceptance gates:
- Reproduce the race deterministically (e.g. scripted rapid `adb install -r` + relaunch loop,
  or two overlapping `startService` calls) and confirm it crashes on the current RC2 code.
- After the fix, run the same repro N times (suggest N=10) with zero `ggml_abort`/crashes.
- No regression to Phase 1/2/3 gates (`stream=false`/`stream=true`, UTF-8 stress, thermal
  hard-block/cooldown, route-away) — rerun the existing Phase 1-3 evidence checks, not new ones.
- No change to `/health`/`/capabilities` schema.

Risks: over-broad locking could introduce a deadlock or a UI-visible stall if the mutex is held
across a slow native call from the main/UI thread; must confirm all lock acquisition happens on
background dispatchers. Getting the repro to reproduce reliably enough to prove the fix may take
iteration — worth budgeting real device time for this, not just code review.

## Workstream B — Foreground service hardening
Problem: `GenerationService` already implements a real Android foreground service (persistent
low-importance notification, `START_STICKY`, wake lock, drain-before-free stop sequencing via
`ApiServer.stop()`) — this is more mature than "add a foreground service," but has gaps: the
notification is static (`"Running on port 11434 — tap to open app"`) and never reflects actual
serving state (model loaded, eligible, thermal-blocked, busy), and there is no user-visible
"stop serving" action from the notification itself (only via the app UI, and only implicitly via
Settings toggling `edge_api_enabled` off).

Desired lifecycle: notification content reflects live state (idle / model loading / serving /
thermally blocked / stopped) using the same `readEligibility()`/`/capabilities` data already
computed by `ApiServer`; a notification action (or at least the app screen it opens) makes
stop/start unambiguous; `onDestroy`'s existing drain-then-free sequencing
(`GenerationService.kt:117-149`) is preserved exactly as-is (it is already a carefully reasoned
"leak over use-after-free" safety tradeoff — do not simplify it away).

Notification behavior: low-importance channel (already correct, keeps it out of heads-up),
update the notification text/using `NotificationCompat.Builder.setContentText` on a throttled
interval (e.g. only on real state transitions, not every request) to avoid notification-manager
churn; consider adding a "Stop serving" action button wired to stopping the service cleanly
through the existing `stop()`/drain path.

Start/stop behavior: no change to when the service *starts* in this workstream (that is boot
survival, Workstream C) — this workstream is about what the service *shows and does* once
running, and ensuring stop always goes through the existing drain sequencing rather than a raw
`stopSelf()`/OS kill.

Acceptance gates:
- Notification text changes correctly across model-loading → eligible/serving →
  thermally-blocked → stopped transitions, verified by screenshot/log evidence on the Fold6.
- Stopping via the notification action (if added) produces the same `STOP_DRAIN_*` /
  `SERVICE_STOPPED` `ServiceHealthLog` events as stopping via the app UI today.
- No change to `/health`/`/capabilities` schema; no change to port or Ktor server behavior.

Risks: notification updates on every token/request would be excessive and battery-unfriendly —
must throttle to state-transition edges only. Adding a notification action increases the
`AndroidManifest.xml`/`PendingIntent` surface area slightly — keep it minimal (one action, no
new activities).

## Workstream C — Boot survival / serve-on-start
Problem: this is **already implemented**, not missing — `BootReceiver.kt` already gates
`ACTION_BOOT_COMPLETED`/`ACTION_MY_PACKAGE_REPLACED` behind an explicit `edge_api_enabled`
DataStore preference (default false, i.e. off unless the operator opts in), correctly skips
`LOCKED_BOOT_COMPLETED` (files are encrypted at rest before first unlock — the existing code
comment is correct and should not be touched casually), and already has a documented, deliberate
fallback for the Android 12+ `ForegroundServiceStartNotAllowedException` case (logs
`BOOT_START_DENIED` and relies on `MainActivity`'s own `LaunchedEffect` to start the service next
time the app is opened). This workstream's job in RC3 is to **verify and harden**, not build from
scratch.

Operator setting: confirm the existing `edge_api_enabled` toggle is clearly surfaced in
`SettingsScreen.kt` (inspect during RC3.5, not assumed here) with accurate copy describing that
enabling it starts inference automatically on boot/app-update.

Charging/network prerequisites: **not currently implemented** — `BootReceiver` starts the
service purely based on the `edge_api_enabled` flag, with no battery/charging or network-state
gate at boot time (thermal/battery eligibility is only checked later, per-request, by
`ApiServer.readEligibility()`). Whether to add a boot-time charging/network prerequisite (e.g.
skip auto-start entirely if battery is critically low) is a real product decision for RC3.5, not
assumed in this plan.

Safe defaults: default `edge_api_enabled = false` is already correct and should not change.
Confirm the same default applies cleanly after the Workstream A serialization fix (i.e. that
boot-triggered starts still go through whatever new load-serialization guard is added).

Acceptance gates:
- `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED` paths both verified live on the Fold6
  with `edge_api_enabled` on and off, confirming the service starts/doesn't start correctly in
  each case, and that a Workstream A fix does not change this observed behavior.
- `BOOT_START_DENIED` path (Android 12+ foreground-start-not-allowed) still logs correctly and
  `MainActivity` still recovers it on next open.
- No silent behavior change to a currently-opted-in operator's boot experience.

Risks: this is the lowest-implementation-risk workstream since most of it exists — the main
risk is scope creep into adding new gating logic (charging/network) that constitutes a feature
change rather than hardening; keep any such addition minimal and clearly optional/off-by-default
if pursued at all.

## Workstream D — Diagnostics/logs screen
Fields to show: server status, model loaded, active backend, current eligibility (and reason),
thermal fields (`peak_thermal_zone_c`, `peak_cpu_zone_c`, `peak_gpu_zone_c`,
`thermal_zone_gate_reason`), last request time, last error, recent TPS/TTFT if available, and
process/server uptime — this is exactly the field list the mission specifies, and it maps almost
one-to-one onto data `ApiServer`'s `readEligibility()`/`CapabilitiesResponse` already compute
internally, plus `ServiceHealthLog`'s existing event stream.

Data sources (already present, to be wired into the UI rather than invented): `DiagnosticsScreen.kt`
+ `DiagnosticsViewModel.kt` + `DiagnosticMetrics.kt` + `HardwareMetricsProvider.kt` +
`ThermalZoneReader.kt` already exist and already expose OS-level thermal-zone and hardware
metrics; `ServiceHealthLog.kt` already has a typed `EventType` enum
(`SERVICE_STARTED`/`SERVICE_STOPPED`/`BOOT_START_ATTEMPTED`/`BOOT_START_DENIED`/
`PACKAGE_REPLACED_START_ATTEMPTED`/`STOP_DRAIN_STARTED`/`STOP_DRAIN_OK`/`STOP_DRAIN_TIMEOUT`/
`NATIVE_FREE_SKIPPED`) and a `serviceEvents: StateFlow<List<ServiceEvent>>` already surfaced in
`DiagnosticsViewModel`. What's missing, based on this inspection, is the *inference-serving*
side of the picture: `ApiServer`'s internal eligibility/thermal/backend/last-request/last-error
state is not currently exposed to `DiagnosticsViewModel` at all — today it's only visible over
HTTP via `/capabilities`. RC3's job here is largely plumbing: expose `ApiServer`'s existing
internal state (already computed for `/capabilities`) to the Diagnostics screen in-process,
without duplicating the thermal-threshold logic.

Acceptance gates:
- Diagnostics screen shows all the fields listed above, sourced from the same underlying data
  `/capabilities` already returns (no drift between what the API reports and what the UI shows).
- TPS/TTFT/backend surfaced from the last real generation (this data already exists in native
  `nativeGenerate`'s stats callback per `pocketnode_jni.cpp`/`onStats`, currently logged but not
  fully surfaced to Kotlin state beyond logcat — confirm exact plumbing during implementation).
- No new HTTP endpoints required for this — it's an in-process read of state `ApiServer` already
  maintains.

Risks: temptation to duplicate thermal-threshold logic in the UI layer instead of reading the
single source of truth in `ApiServer`/`ThermalZoneReader` — must not fork the threshold
constants (`55/60/65/58 °C`) into a second copy in the UI.

## Workstream E — Model inventory UX
Fields to show: active model name/path, SHA-256 (prefix or full), verification status (already
modeled by `VerificationStatus.kt`: `NOT_CHECKED`/`HASHING`/`VERIFIED`/`UNKNOWN_HASH`/`FAILED`),
file size, role (already modeled by `ModelRole.kt`, not inspected in depth this pass).

Actions: reverify (recompute hash and compare against expected/known hash — the hashing
primitive already exists in `HashUtils.sha256`/`ModelArtifactManager.validateExistingFile`, but
there is currently no single-tap "reverify this model" UI action, only an "audit installed
models" bulk action `ModelsViewModel.auditInstalledModels()` and a bulk
`cleanupFailedPrimaryModels()`); quarantine failed model (closest existing analog is
`cleanupFailedPrimaryModels()`/`ModelArtifactManager.cleanupFailedPrimaryArtifact` — confirm
during implementation whether "quarantine" should mean "move aside and mark unusable" versus the
existing cleanup's apparent delete-and-recover behavior, since those are different operator
guarantees); export model inventory (not currently implemented — would be a new, additive,
read-only export of the existing `LocalModel`/`ModelAuditRecord` data, e.g. to a JSON/text file
via the existing `StorageUtils`/`file_paths.xml` FileProvider setup).

Acceptance gates:
- Per-model reverify button recomputes SHA-256 and updates `VerificationStatus` without
  re-downloading or re-importing the file.
- Quarantine action clearly distinguishes "hide/mark unusable" from "delete" — confirm the
  operator cannot lose a model file's data unintentionally (this is a Room DB + file-system
  state, same care as the "preserve model files/Room DB" rule that has applied since RC2).
  Prefer sending a flagged/quarantined model to Android's Trash/RecycleBin equivalent
  or a dedicated quarantine subfolder over hard-deleting it outright.
- Export produces a human-diffable file (JSON preferred) with model name, sha256, size,
  verification status, and role — no secrets or device-identifying data beyond what's already
  visible in the app.

Risks: "quarantine" is the vaguest-specified action in the mission and needs an explicit
operator-facing definition before implementation — recommend nailing this down as the first
step of RC3.4, not assuming a design in this plan.

## Workstream F — Generic ARM64/RK3576 flavor
Why deferred from RC2: Phase 2.6 found an uncommitted, undocumented change to
`app/src/main/cpp/CMakeLists.txt` that silently swapped the Fold6-optimized
`-march=armv8.4-a+dotprod+i8mm` build flags for generic `-march=armv8-a` (citing an "RK3576
Apolo tablet" in a comment, with zero corresponding build/test evidence for that device anywhere
in the repo). RC2 restored the Fold6-optimized baseline as an explicit, committed decision
(`e7e94f7`) specifically because RC2 validation was Fold6-only, and flagged the generic-ARM64
work as a separate, deliberate build-flavor decision rather than an in-place flag swap — this
workstream is that deferred decision, not new discovery.

Proposed design: a Gradle product flavor (or NDK ABI-conditional CMake option) so the Fold6
build keeps `armv8.4-a+dotprod+i8mm` and a second, explicitly-named flavor (e.g. `genericArm64`)
uses `armv8-a`, each independently buildable and each with its own `abiFilters`/APK output — this
mirrors exactly what the *original* (now-restored) `CMakeLists.txt` comment already recommended
before it was overwritten.

Acceptance gates:
- Both flavors build cleanly and independently (`assembleFold6Debug`/`assembleGenericArm64Debug`
  or equivalent task names, exact naming TBD).
- The Fold6 flavor's `assembleDebug` output is byte-for-byte equivalent in behavior to what RC2
  validated (same march flags, same APK behavior) — i.e. this workstream must not regress RC2.
- The generic flavor installs and runs (build-only proof is not sufficient; at minimum confirm
  it launches and serves `/health` on a generic ARM64 device or emulator) before calling it done.

Risks: this is explicitly the lowest-priority, most speculative workstream (no second device is
currently in-repo evidence for this need) — should only be attempted "if time remains" per the
phase ordering below, and should not block or delay any hardening workstream.

## Proposed RC3 phases
Phase RC3.1 — Model-load race investigation and serialization (Workstream A). Highest priority:
this is a real crash with live evidence, not a hypothetical.
Phase RC3.2 — Foreground service lifecycle hardening (Workstream B). Builds directly on
whatever locking/state Phase RC3.1 introduces (the notification's "state" needs a clean signal
to show, ideally the same one used to gate Workstream A's serialization).
Phase RC3.3 — Diagnostics/logs screen (Workstream D). Mostly plumbing existing state into a
screen that already exists in skeleton form; benefits from RC3.1/RC3.2's cleaner state model.
Phase RC3.4 — Model inventory UX (Workstream E). Independent of A/B/C; can run in parallel with
RC3.3 if resourced, but sequenced after for a single-threaded plan.
Phase RC3.5 — Boot survival and optional autostart (Workstream C). Mostly verification of
existing behavior plus the optional charging/network-prerequisite decision; sequenced last among
the hardening workstreams because it depends on RC3.1's fix being in place first (boot-triggered
starts are one of the racing paths).
Phase RC3.6 — Generic ARM64 flavor spike, only if time remains (Workstream F). Explicitly
lowest priority and optional per the mission's own framing.

## Definition of done
RC3 closes when, with evidence captured the same way RC2's phases were (live Fold6 device
testing, evidence docs per phase, no silent scope creep):
1. The model-load race is reproduced pre-fix and proven eliminated post-fix (N repeated
   scripted install/relaunch cycles, zero `ggml_abort`/crashes) — Workstream A.
2. The foreground service notification accurately reflects live serving state across all
   observed transitions, and stop always goes through the existing drain sequencing —
   Workstream B.
3. The Diagnostics screen displays all mission-specified fields, sourced from the single
   existing source of truth (`ApiServer`/`ThermalZoneReader`), with no duplicated threshold
   constants — Workstream D.
4. Model inventory supports per-model reverify, a clearly-defined non-destructive quarantine
   action, and inventory export, without risking accidental data loss — Workstream E.
5. Boot survival behavior is re-verified (not just assumed) to still work correctly after the
   Workstream A fix, in both the enabled and disabled operator-setting states — Workstream C.
6. (Optional) A generic ARM64 build flavor exists, builds independently, and does not regress
   the Fold6-optimized RC2 baseline — Workstream F, only if pursued.
7. All existing RC1/RC2 gates (build, install/upgrade, `/health`, `/capabilities`,
   `stream=false`/`stream=true`, UTF-8 stability, thermal hard-block/cooldown, route-away) are
   re-verified with no regression before RC3 is tagged.
8. An RC3 evidence trail exists (phase-by-phase docs, following the same pattern as
   `POCKET_NODE_RC2_PHASE1/2/3_*.md`) and a clean, targeted RC3 closure commit/tag is created,
   following the same discipline P28 used (no bundling of unrelated dirty/untracked files,
   explicit tag decisions, no force-push).

## Non-goals
RC3 explicitly will NOT: add new inference features, new model formats, new sampling/decoding
strategies, new API endpoints beyond what a hardening fix strictly requires, change the
`/capabilities` field contract, change the `stream=true`/`stream=false` response schemas, modify
Neo/LiteLLM/Mac Studio/Moolah or any routing/fallback policy, run prolonged thermal soak or
stress testing, rotate or introduce new secrets, or silently resolve the generic-ARM64 build
question by re-editing `CMakeLists.txt` in place (Workstream F is the only sanctioned path for
that, and only as an explicit, separate flavor).
