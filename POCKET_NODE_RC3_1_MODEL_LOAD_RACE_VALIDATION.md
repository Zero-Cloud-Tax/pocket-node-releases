# Pocket Node RC3.1 Model-Load Race Validation

Date/time: 2026-07-02, 16:00–16:35 local
Branch: main
Starting HEAD: 46519aa ("P29: plan Pocket Node RC3 hardening")
Final HEAD: c3f6a48 ("P29: serialize Pocket Node model lifecycle")
Device: Samsung SM-F956U (Galaxy Z Fold 6), Android 16, serial RFCX60BRDWA
APK: app/build/outputs/apk/debug/app-debug.apk (debug build)

## Root cause analysis
Observed RC2 race: Phase 2.5/2.6 device testing observed a native `ggml_abort()` crash
(distinct call stack from the earlier-fixed `NewStringUTF` UTF-8 abort) during an overlapping
install/model-load race — logcat showed two `nativeLoadModel`/`nativeCreateContext` sequences
on different threads within about a second of each other, immediately following a rapid
`adb install -r` + relaunch cycle.

Code paths involved (confirmed by direct inspection in this phase, before any fix):
- `GenerationService.onStartCommand()` unconditionally launched `autoLoadModel()` on
  `serviceScope` whenever `app.activeSession == null` at service start, with no lock or
  in-flight guard protecting the launch.
- `BootReceiver.kt` handles both `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED`
  (the latter fires on every app upgrade/reinstall) and independently starts
  `GenerationService` via `ContextCompat.startForegroundService(...)` whenever the
  `edge_api_enabled` preference is on — a second, independent path that can trigger a
  service (re)start at almost exactly the same moment as a manual relaunch or an OS respawn
  of a `START_STICKY` service.
- `ChatViewModel` already had its own model-switch path
  (`loadModel()`/`unloadDraftModel()`/the Ask-Image and benchmark paths) guarded by a
  **private** `nativeSessionMutex: Mutex`, but that mutex was never shared with
  `GenerationService` — so the UI's own serialization gave zero protection against the
  service racing it.
- Native `nativeLoadModel`/`nativeFreeModel`/`nativeCreateContext`/`nativeFreeContext` (and
  their draft-model counterparts `nativeLoadDraftModel`/`nativeFreeDraftModel`/
  `nativeCreateDraftContext`/`nativeFreeDraftContext`) took **no** mutex around the actual
  `llama_model_load_from_file`/`llama_init_from_model`/`llama_model_free`/`llama_free` calls —
  the existing `g_inference_mutex` was only ever taken briefly inside those functions to guard
  the `g_n_past` bookkeeping map, and separately around the whole body of `nativeGenerate`
  (generation), never around model/context load or free.

Why `@Volatile activeSession` was insufficient: `@Volatile` on
`MainApplication.activeSession` only guarantees that a write to the field by one thread is
immediately visible to reads on another thread — it says nothing about the sequence of
operations (free old pointers → call native load → call native create-context → assign new
session) being atomic as a whole. Two threads can both observe `activeSession == null`,
both proceed to call `nativeLoadModel`/`nativeCreateContext` concurrently, and only then race
to assign `activeSession` — the volatile field cannot prevent the concurrent native calls that
happen *before* either assignment.

Native lifecycle risk: because none of the native load/free functions took any lock at all
(other than the narrow `g_n_past` map access), two concurrent `nativeLoadModel` calls could
call `llama_model_load_from_file` at the same time. `llama.cpp`/GGML is not documented or
expected to be safe under arbitrary concurrent load calls (backend registration, allocator, and
mmap/file-descriptor state are not designed for concurrent independent loads), which is
consistent with the observed `ggml_abort()` — an internal GGML assertion/abort path, not a JNI
UTF-8 issue.

## Fix design
Coordinator/mutex strategy: added `ModelLoadCoordinator`
(`app/src/main/java/com/pocketnode/app/inference/ModelLoadCoordinator.kt`), a singleton object
exposing a single shared `kotlinx.coroutines.sync.Mutex` and a `suspend fun
withLifecycleLock(op, block)` helper that logs `model_load_join_inflight` when another
operation already holds the lock, then runs `block()` under it. This is the one serialization
point now shared by every model load/unload/session-replacement call site in the app.

Service path changes: `GenerationService.autoLoadModel()` now runs its entire body inside
`ModelLoadCoordinator.withLifecycleLock("service_auto_load") { ... }`. The pre-existing
`if (app.activeSession == null)` check before launching the coroutine is kept as a cheap
fast-path filter, but the **authoritative** check is now re-done *inside* the lock
(`if (app.activeSession != null) { log skip; return }`), since activeSession being null before
acquiring the lock is no longer a valid guarantee by the time the coroutine actually runs.
Added logs: `model_load_start`, `model_load_skip_already_active`, `model_load_failed` (two
distinct reasons: native load vs. native context-create failure), `model_load_success`. Also
added `model_unload_start`/`model_unload_success` logs around `onDestroy()`'s existing
drain-then-free sequencing (that sequencing itself — "leak over use-after-free" on drain
timeout — was **not** changed; only logging was added around it).

UI/model switch path changes: `ChatViewModel`'s existing `private val nativeSessionMutex =
Mutex()` was repointed to `private val nativeSessionMutex = ModelLoadCoordinator.mutex` — a
one-line change. All five of `ChatViewModel`'s existing `nativeSessionMutex.withLock { ... }`
call sites (main model load, draft model load, draft model unload, the Ask-Image
mmproj/image-embed path, and the speculative-decoding benchmark) were **not** restructured —
they now transparently share the same lock the service uses, with zero risk of breaking their
existing labeled returns/control flow.

Native lifecycle changes: added a second, dedicated `static std::mutex g_lifecycle_mutex` in
`pocketnode_jni.cpp`, intentionally separate from `g_inference_mutex` (generation is never
blocked by a load/unload and vice versa). Wrapped the **entire body** of all 8 lifecycle
functions in `std::lock_guard<std::mutex> lifecycle_lock(g_lifecycle_mutex)`:
`nativeLoadModel`, `nativeFreeModel`, `nativeCreateContext`, `nativeFreeContext`,
`nativeLoadDraftModel`, `nativeFreeDraftModel`, `nativeCreateDraftContext`,
`nativeFreeDraftContext`. This is deliberate defense-in-depth: it guarantees serialization at
the native boundary regardless of whether every future Kotlin call site remembers to take
`ModelLoadCoordinator.mutex`, and it is what actually protects `GenerationService.onDestroy()`'s
synchronous (non-suspend) free calls, which were intentionally **not** wrapped in the
suspend-based Kotlin coordinator (touching `onDestroy`'s already-carefully-reasoned
drain/free/leak-avoidance sequencing was out of scope for "smallest safe change"). Lock order is
always "acquire `g_lifecycle_mutex` first" wherever both mutexes could be relevant in the same
call, so there is no cross-lock cycle with `g_inference_mutex`. `g_inference_mutex` itself was
**not** weakened or touched.

Logging added: `model_load_start`, `model_load_skip_already_active`,
`model_load_join_inflight`, `model_load_success`, `model_load_failed` (with reason),
`model_unload_start`, `model_unload_success` — all under the `PocketNode.ModelLoad` log tag,
distinct from the existing plain `PocketNode` tag used by native logs.

## Files changed
- `app/src/main/java/com/pocketnode/app/inference/ModelLoadCoordinator.kt` (new): the shared
  lifecycle mutex/coordinator described above.
- `app/src/main/java/com/pocketnode/app/inference/GenerationService.kt`: wrapped
  `autoLoadModel()` in `ModelLoadCoordinator.withLifecycleLock`, added the re-check-under-lock
  idempotency guard, added `Log`/`TAG` and the load/unload log lines described above. No change
  to `onStartCommand`'s notification/foreground-service setup, and no change to `onDestroy`'s
  drain-then-free control flow (only two new log lines added around the existing free calls).
- `app/src/main/java/com/pocketnode/app/inference/ChatViewModel.kt`: one-line change repointing
  `nativeSessionMutex` to `ModelLoadCoordinator.mutex`; no other lines touched.
- `app/src/main/cpp/pocketnode_jni.cpp`: added `g_lifecycle_mutex` declaration (with an
  explanatory comment) and 8 `std::lock_guard` insertions, one per lifecycle function listed
  above. No other native logic changed; `g_inference_mutex` and `nativeGenerate` untouched.
- `POCKET_NODE_RC3_1_MODEL_LOAD_RACE_VALIDATION.md` (new): this document.

## Pre-fix reproduction
Commands: the exact pre-fix crash was not re-reproduced from scratch in this phase — it was
already directly observed and logged during Phase 2.5/2.6 device testing (documented in
`POCKET_NODE_RC2_CHECKPOINT.md`), with a real `ggml_abort()`/`SIGABRT` captured in logcat
following an overlapping `adb install -r` + relaunch cycle. This phase treated that prior,
already-captured crash as the reproduction baseline rather than re-triggering a crash against
unpatched code, since the fix was implemented and device-verified together as one change set.
Result: race reproduced (from prior phase's captured evidence).
Crash reproduced: yes (previously captured; not re-triggered against unpatched code in this
phase — see Follow-ups).

## Build result
assembleDebug: **PASS** — `BUILD SUCCESSFUL in 1m 4s` after all Kotlin and native changes;
native `configureCMakeDebug`/`buildCMakeDebug`/`kspDebugKotlin`/`compileDebugKotlin` tasks all
ran and succeeded.

## API regression checks
`/health`: **PASS** — `{"status":"ok","node":"pocket-node","device":"android","model_loaded":true,...}`
`/capabilities`: **PASS** — all RC2-documented fields present, `eligible_for_inference`
correctly toggling with real thermal state observed during testing (a genuine, unforced
hard-block/soft-block from the test device's own build/install heat, not a regression).
`stream=false`: **PASS** — single consolidated `{"response": "...", "done": true}` object
returned correctly.
`stream=true`: **PASS** — NDJSON per-token streaming unchanged (one attempt was correctly
refused with `{"error":"node_unavailable","reason":"thermal_zone_cpu_soft_block",...}` while
genuinely thermally soft-blocked from the prior request's heat — confirmed as real thermal
gating working as designed, not a bug, and succeeded once retried after natural cooldown).

## Post-fix race stress
Install/relaunch iterations: 8 rapid `adb install -r` + `monkey` launch cycles, back-to-back
with no artificial delay beyond a 3-second settle before checking `pidof`.
Process survived: **yes** — every one of the 8 iterations produced a live, responding pid
(3612 → 3900 → 4164 → 4426 → 4573 → 4742 → 4883 → 5022), with the final pid (5022) still alive
and serving correctly after the full stress run and subsequent API checks.
ggml_abort observed: **no** — a full logcat scan across the entire stress window
(`grep -iE "ggml_abort|SIGABRT|Fatal signal|JNI DETECTED ERROR"`) returned zero matches.
SIGABRT observed: **no** (same scan as above).
Duplicate load observed: **the race condition itself was reproduced and correctly handled** —
at pid 5022, logcat shows a real overlap: `model_load_start` at `16:32:05.491` (thread 5071)
followed by a second, independent trigger at `16:32:16.315` (thread 5067) which found the
mutex already locked and logged `model_load_join_inflight op=service_auto_load` instead of
proceeding into native code — it then waited, and the first load completed cleanly
("Model loaded successfully" / "Context created" at `16:32:23.207`/`16:32:23.563`). This is
direct, positive proof that two calls into the model-load path were attempted concurrently on
this device during the stress run, and the coordinator serialized them exactly as designed,
with zero crash.
Coordinator logs observed: **yes** — `model_load_start`, `model_load_join_inflight`, and
`model_load_success` all appeared in the expected order during the stress run (see above); a
subsequent unload/reload cycle within the same process (pid 5022, consistent with the app's own
service being stopped and restarted once more shortly after, e.g. via the app's own
edge-API-toggle lifecycle handling) also completed cleanly — `Context freed`/`Model freed`
followed by a fresh `model_load_start` → `model_load_success` — with no overlap or crash,
further exercising the native `g_lifecycle_mutex` defense-in-depth path.

## Verdict
**PASS**

- `assembleDebug` passes with no regressions.
- Install/upgrade over the existing app, launch, `/health`, `/capabilities`, `stream=false`,
  and `stream=true` all verified working post-fix, with the one "failure" encountered
  (`bypass`/refusal under `thermal_zone_cpu_soft_block`) being genuine, correct thermal gating,
  not a regression.
- The race condition this phase targets was not just theoretically closed — it was **directly
  observed occurring** during the 8-iteration stress run and correctly serialized by the new
  coordinator (`model_load_join_inflight`), with the process surviving every iteration and zero
  `ggml_abort`/`SIGABRT` anywhere in the stress window.
- No new inference features were added; no `/health`/`/capabilities`/`/api/generate` schema
  changes; no thermal threshold changes; no native architecture flag changes; no P27/evidence
  artifact deletions.

## Follow-ups
- This phase did not build and test an *unpatched* binary side-by-side to force a clean
  "before" crash reproduction in this exact session — the pre-fix crash evidence relied on is
  from Phase 2.5/2.6's prior device testing. If a from-scratch, git-bisectable repro is ever
  needed (e.g. for a regression test harness), that would require checking out the pre-RC3.1
  commit, rebuilding, and re-running the same stress loop deliberately expecting a crash.
- No unit/instrumentation test was added for `ModelLoadCoordinator` — this project has no
  existing `app/src/test`/`app/src/androidTest` source sets or test framework (e.g. Robolectric)
  wired into the Gradle build, and `GenerationService`/`ChatViewModel` are heavily
  Android-framework-bound (Service lifecycle, DataStore, Room, JNI), making a from-scratch test
  harness a larger scope change than this phase's "smallest safe change" mandate. Device stress
  verification was used instead, per the phase's own fallback guidance. Introducing a test
  framework (Robolectric or similar) so coordinator logic can be unit-tested going forward is a
  reasonable RC3 follow-up in its own right, separate from this fix.
- `GenerationService` and `ChatViewModel` still track their model/context pointers
  independently (`ownedModelPtr`/`ownedCtxPtr` vs. `modelPtr`/`contextPtr`) while sharing one
  `activeSession` reference — this phase's fix prevents concurrent *native calls*, but does not
  change the fact that the service and the UI can still each load their own separate model into
  the single shared `activeSession` slot, with the loser's pointers silently orphaned from
  `activeSession` (though not leaked, since each side still owns and frees its own pointers).
  Whether that dual-ownership model itself should be unified is a larger design question for a
  future phase, not this one.
