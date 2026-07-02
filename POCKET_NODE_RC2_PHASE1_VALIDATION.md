# Pocket Node RC2 Phase 1 Validation

Date/time: 2026-07-02, 14:50–15:05 local
Host: Windows 11 Pro, PowerShell/Git Bash environment
Android device: Samsung SM-F956U (Galaxy Z Fold 6), Android 16, serial RFCX60BRDWA (USB)
Branch: main
HEAD SHA: a940aab ("Harden Pocket Node thermal gating and Fold6 Vulkan build")

Dirty state before work:
- `app/src/main/cpp/CMakeLists.txt` — modified, uncommitted. Changes the native build march
  from `armv8.4-a+dotprod+i8mm` (Fold6/Snapdragon 8 Gen 3 optimized) to generic `armv8-a`,
  with a comment claiming this is "for RK3576 Apolo tablet and generic ARM64 devices."
  **This was NOT touched or reverted in this phase** — flagged for Phase 2 decision since it
  de-optimizes the Fold6 build the mission is centered on.
- `screen.png`, `window_dump.xml` — modified (stale UI-dump artifacts from prior session work).
- ~50 untracked files/directories at repo root (P27 closure artifacts, audit docs, python
  scripts, `pocket-node-public-repo/`, `release-artifacts/`, a snapshot `.db`/`.db-wal` pair,
  two `.fuse_hidden*` stray files). None of these were touched, deleted, or moved.

Dirty state after work: same as above, plus one additional modified file:
`app/src/main/java/com/pocketnode/app/inference/ApiServer.kt` (+96/-2 lines — see Changes made).

## Build environment
Java: OpenJDK 21.0.10 (Microsoft build)
Gradle: 8.13 (wrapper)
Android Gradle Plugin: (standard AGP toolchain via Gradle 8.13 / compileSdk 35)
Kotlin: 2.1.0 (project plugin version) / Gradle-reported Kotlin tooling 2.0.21
NDK/CMake: CMake via `externalNativeBuild` (app/build.gradle.kts:43,107); one non-fatal warning
during configure: `[CXX5304] This version only understands SDK XML versions up to 3 but an SDK
XML file of version 4 was encountered` — cosmetic version-skew warning between Android Studio
and command-line tools, did not block the build.

applicationId: com.pocketnode.app
versionCode: 4
versionName: 0.1.0-rc2
compileSdk/targetSdk: 35, minSdk: 28

## Build gates
assembleDebug: **PASS** (both before and after the API fix below)
assembleRelease: **FAIL — expected.** Fails at `:app:validateReleaseSecrets` with
`POCKETNODE_PRO_HMAC_SECRET is not set. Export the env var before running assembleRelease.`
This matches the documented acceptable release-failure mode (missing signing/HMAC secret).
Release failure reason: missing `POCKETNODE_PRO_HMAC_SECRET` env var (intentional gate).

## APK install/upgrade
APK path: `app/build/outputs/apk/debug/app-debug.apk`
Install command: `adb -s RFCX60BRDWA install -r app-debug.apk` (run twice: once for baseline,
once after the ApiServer.kt fix)
Install result: `Success` both times (streamed install, upgrade over existing install)
Data preserved: **yes** — same app data directory, model file, and Room DB were reused across
both upgrade installs (model reload picked up the existing on-device
`PocketNode_SmolLM3_Q4_0_Fresh.gguf` file both times without re-provisioning). No `uninstall`
was run.

## Runtime
Package: com.pocketnode.app
Process: launched via `monkey -p com.pocketnode.app -c android.intent.category.LAUNCHER 1`;
confirmed running via `pidof` on both launches. Foreground service `GenerationService` started
(`PocketNode.ServiceHealth: SERVICE_STARTED`), Ktor logged `pocketnode_edge_api` notification
channel active.
Serving port: 11434 on-device, forwarded to host 11435 via `adb forward tcp:11435 tcp:11434`.

Health result: `{"status":"ok","node":"pocket-node","device":"android","model_loaded":true,"uptime_ms":...}`
Capabilities result summary: all documented RC2 fields present (see below); `eligible_for_inference`
correctly toggled false→true as model finished loading and again false→true across a real
thermal hard-block/cooldown cycle observed live during testing (`thermal_zone_gate_reason`
populated with a descriptive block/cooldown message while blocked, `null` while eligible).

## API compatibility
`/health`: **PASS**
`/capabilities`: **PASS** — all fields present under their actual (non-renamed) RC2 names.
`/api/generate` `stream=false`: **FAIL on first attempt → FIXED, then PASS.** See Changes made.
`/api/generate` `stream=true`: **PASS**

### Critical finding (native, not fixed in this phase)
During non-stream testing, the app process crashed with `SIGABRT` /
`JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8: illegal continuation
byte` in `LlamaInference.nativeGenerate`'s token callback → `NewStringUTF`. Root cause: the
native token-generation loop calls `NewStringUTF` per-token without buffering multi-byte UTF-8
sequences that get split across BPE token boundaries — when a token boundary falls inside a
multi-byte UTF-8 character (observed here with Cyrillic output), the partial byte sequence is
invalid UTF-8 and JNI aborts the entire process. This is a **pre-existing native bug shared by
both the streaming and non-streaming code paths** (both ultimately go through the same
`LlamaCallback.onToken` → `NewStringUTF` call) — it is NOT something introduced by the
`stream=false` fix below, and it reproduced before that fix was made. The foreground service
auto-restarted after the crash (Android's crashed-service restart policy) and the model
reloaded automatically, so the node self-healed, but a live in-flight request is lost and any
client mid-request sees a dropped connection. **Not fixed in Phase 1** — this is a native/JNI
change (buffering partial UTF-8 sequences before calling `NewStringUTF`), which is out of
scope for "small compatibility fix" and belongs in Phase 2.

## RC2 required fields
(Actual field names differ from the Ollama-style names assumed in the phase brief; the code
has an explicit "DO NOT rename or remove — backward compat" comment guarding these names, so
they were left as-is.)

eligible → `eligible_for_inference`: present (bool)
reason → `reason_if_not_eligible`: present (string/null)
model → not present as a distinct field (no model-name field in `/capabilities`; `model_loaded`
bool is present instead)
backend → not present as a distinct field in `/capabilities` (backend list is logged, e.g.
`Vulkan, OpenCL, CPU`, but not surfaced over HTTP)
cpuTempC → `peak_cpu_zone_c`: present (double)
gpuTempC → `peak_gpu_zone_c`: present (double)
osZonePeakC → `peak_thermal_zone_c`: present (double)
thermalCode → not present as a distinct enum/code field
thermalStatus → `thermal_status`: present (string, e.g. `"none"`)
hysteresisActive → not present as a distinct boolean; `thermal_zone_gate_reason` carries
equivalent info as a descriptive string (e.g. `"thermal_zone_hard_block (peak=78.7°C >=
65.0°C; cooldown to 58.0°C)"`) when a hysteresis/cooldown gate is active, `null` otherwise.

## Changes made
1. `app/src/main/java/com/pocketnode/app/inference/ApiServer.kt`
   - Added `stream: Boolean? = null` to `GenerateRequest` and `ChatRequest` data classes.
   - Added `NonStreamGenerateResponse` (`{"response": "...", "done": true}`) data class.
   - Added `nonStreamResponse(...)` function (mirrors the existing `nonStreamOaiResponse`
     pattern already used by `/v1/chat/completions`): buffers all generated tokens into a
     `StringBuilder` and returns one consolidated JSON object.
   - `/api/generate` and `/api/chat` handlers now branch on `req.stream == false` →
     `nonStreamResponse`, else (default/true) → existing `streamResponse` (NDJSON, unchanged).
   - Why: `GenerateRequest`/`ChatRequest` previously had no `stream` field at all, so a
     `"stream":false` body was silently ignored by kotlinx.serialization (unknown field) and
     `/api/generate` always returned per-token NDJSON regardless of the requested mode — a
     direct violation of the RC2 API-compatibility contract this phase exists to verify.
     `/v1/chat/completions` already correctly implemented this branch; the fix reuses the same
     pattern for parity.
   - Rebuilt `assembleDebug` (PASS), reinstalled over existing app (data preserved), reverified
     `/health`, `/capabilities`, `/api/generate` stream=false and stream=true.
2. No other source files were changed. `app/src/main/cpp/CMakeLists.txt` was inspected
   (pre-existing uncommitted change, see Dirty state) but not modified or reverted, per the
   "inspect before changing" / minimal-scope rule — flagged for Phase 2 owner decision instead.

## Phase 1 verdict
**CONDITIONAL**

Rationale: all build, install, launch, and API-shape gates pass, including the `stream=false`
compatibility bug found and fixed during this phase. The one open item — the native JNI
UTF-8-boundary crash — did not block install/launch/health/capabilities and the service
self-recovers, but it is a genuine reliability gap in `/api/generate` and `/api/chat` (and the
OAI streaming path, which shares the same callback) that can drop in-flight requests. Per the
verdict rules this keeps Phase 1 at CONDITIONAL rather than PASS or FAIL: build/install/launch
all succeeded, release build failed only for the expected secret reason, but one API-reliability
follow-up is warranted before calling the RC2 API surface fully trustworthy.

## Follow-ups for Phase 2
- Fix the native JNI `NewStringUTF` UTF-8-boundary crash in the token callback path shared by
  `streamResponse`, `nonStreamResponse`, and `streamOaiResponse` (buffer partial multi-byte
  UTF-8 sequences across token boundaries before crossing the JNI boundary). This is a
  reliability fix, not new scope, but touches native/JNI code so was deferred out of Phase 1's
  "small compatibility fix" bound.
- Decide intentionally on the uncommitted `app/src/main/cpp/CMakeLists.txt` change (currently
  de-optimizes the Fold6 build from `armv8.4-a+dotprod+i8mm` to generic `armv8-a` for a
  different device). Commit, revert, or branch this per the actual multi-device build strategy
  before RC3 — do not carry it forward silently uncommitted.
- Clean up or decide the fate of the large number of untracked P27 artifacts, audit docs, and
  scratch scripts at the repo root before any public packaging pass.
- No thermal soak testing, route-away/fallback testing, or public packaging was performed —
  out of scope for Phase 1 by design.
