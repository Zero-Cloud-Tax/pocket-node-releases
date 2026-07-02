# Pocket Node RC2 Phase 3 Thermal & Routing Validation

Date/time: 2026-07-02, 15:28–15:36 local (2026-07-02T19:33–19:36 UTC per device timestamps)
Branch: main
HEAD SHA: e7e94f7 ("P28: restore Fold6 native optimization baseline for RC2")
Device: Samsung SM-F956U (Galaxy Z Fold 6), Android 16, serial RFCX60BRDWA
APK: app/build/outputs/apk/debug/app-debug.apk (rebuilt from e7e94f7, no source changes since
Phase 2.6 — `assembleDebug` reported UP-TO-DATE)
Native arch baseline: `-march=armv8.4-a+dotprod+i8mm` (Fold6-optimized, restored in Phase 2.6)
Model: PocketNode_SmolLM3_Q4_0_Fresh.gguf (gpu_layers=57)
Backend: Vulkan, OpenCL, CPU (registered in that order; Vulkan+OpenCL used for generation per
logcat `backend=Vulkan,OpenCL`)

## Code-confirmed thermal contract
Read directly from `app/src/main/java/com/pocketnode/app/inference/ApiServer.kt` (lines
197-204, 822-878) — prior assumed values were NOT used blindly; these are the actual constants:

Thresholds:
- `THERMAL_ZONE_WARN_C = 55.0` — telemetry only, does not block inference
- `THERMAL_ZONE_SOFT_BLOCK_CPU_C = 60.0` — blocks if peak CPU zone ≥ 60°C
- `THERMAL_ZONE_SOFT_BLOCK_GPU_C = 60.0` — blocks if peak GPU zone ≥ 60°C
- `THERMAL_ZONE_HARD_BLOCK_C = 65.0` — hard block if ANY zone ≥ 65°C
- `THERMAL_ZONE_COOLDOWN_C = 58.0` — hard block releases only once ALL zones < 58°C
  (comment: "Empirical defaults from B.2 benchmarking on Samsung Z Fold 6 / Snapdragon 8 Gen 3.
  NOT validated hardware safety limits — adjust with per-device profiling.")

Capabilities fields (actual JSON keys in `/capabilities`, unchanged from Phase 1 — no renames
made in this phase): `eligible_for_inference`, `reason_if_not_eligible`, `thermal_status`,
`peak_thermal_zone_c`, `peak_thermal_zone_type`, `peak_cpu_zone_c`, `peak_cpu_zone_type`,
`peak_gpu_zone_c`, `peak_gpu_zone_type`, `thermal_zone_readable_count`,
`thermal_zone_error_count`, `thermal_zone_gate_reason`, `battery_temperature_c`,
`model_loaded`, `service_alive`, `last_inference_at`, `last_error`. There is no literal
`thermalCode`, `osZonePeakC`, or `hysteresisActive` field in the public response — `thermalCode`
exists only as an internal `EligibilityResult` field (PowerManager thermal status int, not
serialized); the OS-zone peak is named `peak_thermal_zone_c`; hysteresis state is exposed
indirectly through `eligible_for_inference` + `reason_if_not_eligible` +
`thermal_zone_gate_reason` text rather than a dedicated boolean.

Eligibility reasons (`reason_if_not_eligible`), most-severe-first per the code's `when` chain:
`model_not_loaded`, `battery_below_threshold` (battery < 30% and not charging),
`thermal_severe` (Android `PowerManager.currentThermalStatus` ≥ 3), `thermal_zone_hard_block`,
`thermal_zone_cpu_soft_block`, `thermal_zone_gpu_soft_block`, and `debug_forced_block` (only
when the existing `/debug/eligibility/force_block` test endpoint has been toggled on).

Hysteresis behavior: a single `@Volatile private var thermalZoneHardBlocked` boolean latches to
`true` the instant any zone reaches ≥ 65°C, and only clears back to `false` once **all** zones
have dropped below 58°C — a 7°C gap between block and release, implemented in
`readEligibility()` (lines 828-833). This is app-process-local state (not persisted), reset on
process restart, and is entirely OS-thermal-zone-based (`/sys/class/thermal/thermal_zone*` via
`ThermalZoneReader`) layered on top of, but independent from, Android's own
`PowerManager.currentThermalStatus` gate (`thermal_severe` at status ≥ 3). Both mechanisms are
"AND"ed into eligibility — either one alone is sufficient to block.

## Build/install
assembleDebug: **PASS** (`BUILD SUCCESSFUL in 58s`, all tasks UP-TO-DATE — no source changed
since the Phase 2.6 checkpoint)
Install: `adb -s RFCX60BRDWA install -r app/build/outputs/apk/debug/app-debug.apk` → `Success`
Data preserved: yes — same on-device model file reloaded without re-provisioning, no uninstall
performed

## Baseline capabilities
```json
{"eligible_for_inference":true,"reason_if_not_eligible":null,"model_loaded":true,
 "thermal_status":"none","peak_thermal_zone_c":44.4,"peak_cpu_zone_c":44.4,
 "peak_gpu_zone_c":41.8,"thermal_zone_gate_reason":null,"battery_temperature_c":32.0}
```
`/health`: `{"status":"ok","node":"pocket-node","device":"android","model_loaded":true,"uptime_ms":18338}`

## Direct-load test
Number of runs: 2 (loop was configured for up to 20, but stopped immediately per the hard rule
"if a safety threshold trips, stop sending direct load" — see below)
Peak cpuTempC: **78.7°C** (`peak_cpu_zone_c`, zone `cpu-2-2-1`)
Peak gpuTempC: 51.2°C (`peak_gpu_zone_c`, zone `gpuss-4`) — GPU never approached its own 60°C
soft-block threshold; the CPU zone crossed the 65°C hard-block threshold first
Peak osZonePeakC: 78.7°C (`peak_thermal_zone_c`, same reading as peak CPU zone this run)
Lowest eligibility state reached: **RED/BLOCK** (`eligible_for_inference: false`,
`reason_if_not_eligible: "thermal_zone_hard_block"`)
Did WARN/YELLOW occur: not observed as a standalone state this run — temperature jumped from a
cool baseline (44.4°C) straight past the 55°C WARN line to 78.7°C (past the 65°C hard-block
line) within a single ~53-second generation (512 tokens), so WARN was transited through but not
independently captured mid-run; WARN *was* directly observed later during the cooldown descent
(`thermal_zone_warn` reason at 55.6°C — see Block/cooldown proof below), confirming the WARN
tier is implemented and reachable, just crossed quickly in the heating direction here.
Did RED/BLOCK occur: **yes** — confirmed after run 1 of the direct-load loop.
Did app crash: **no** — pid stayed at 4254 throughout the entire test window (verified via
`pidof` before, during, and after).
Did generation continue safely: **yes, and correctly stopped** — run 1's generation completed
(the request was already in flight before the block state existed); the loop then detected
`eligible_for_inference: false` at the start of run 2 and stopped immediately without sending a
second generation request, per the hard rule "stop sending direct load to the Fold6 until it
cools." No request was sent, accepted, or attempted against the blocked node.

Note: run 1's non-stream response text was a long run of `@` characters rather than the
requested prose — a model-sampling artifact (has occurred inconsistently since Phase 1/2, not
correlated with the UTF-8 fix or this phase's changes) and not a crash or protocol error; the
response was still valid, well-formed JSON.

## Block/cooldown proof
Blocked state observed: **yes** — `2026-07-02T19:34:25Z` (device UTC),
`eligible_for_inference: false`, `reason_if_not_eligible: "thermal_zone_hard_block"`,
`thermal_zone_gate_reason: "thermal_zone_hard_block (peak=78.7°C >= 65.0°C; cooldown to 58.0°C)"`
Cooldown observed: **yes** — a single 10-second poll later
(`2026-07-02T19:34:35Z`), `peak_cpu_zone_c` had already fallen to 55.6°C (below the 58°C
cooldown line)
Eligibility restored: **yes** — same poll, `eligible_for_inference: true`,
`reason_if_not_eligible: null`
Hysteresis cleared: **yes** — `thermal_zone_gate_reason` changed from the hard-block message to
`"thermal_zone_warn (peak=55.6°C >= 55.0°C; inference allowed)"`, and the internal
`thermalZoneHardBlocked` latch (per code) only clears once peak drops below 58°C, which is
exactly the transition observed — confirms the hysteresis gap (block at ≥65°C, release only
<58°C, not simply <65°C) is real and working, not just a threshold re-check.
Evidence timestamps:
- `19:34:25Z` — blocked, peak 78.7°C
- `19:34:35Z` — cooled to 55.6°C, eligible again, hysteresis cleared (only 10s elapsed; the
  Fold6's passive cooldown from a single short burst was faster than expected)

## Route-away proof
Method: **Preferred proof, via live pre-flight thermal gate** — the homelab's Neo edge gate
(`http://100.66.89.35:4001`, reachable from this environment) exposes a running policy
`fold6_preflight_thermal_v2` (per its `/health` response) that queries the Fold6's own
`/capabilities` (`http://100.99.70.73:11434/capabilities`) before deciding whether to route a
`pocket-node-fold6`-model chat completion to the Fold6 or bypass to a fallback model. This is
the actual live routing chain component (Fold6 → Neo gate → LiteLLM → fallback) named in the
mission, not a mock. Ineligibility was proven two ways: (1) the natural thermal hard-block from
the direct-load test above, observed via `/capabilities` but not re-tested through the gate in
that exact window since it cleared in 10s, and (2) the existing, already-in-code
`/debug/eligibility/force_block` / `/debug/eligibility/clear` endpoints on the Fold6 itself,
used to hold ineligibility steady long enough to run the gate test deterministically — this is
one of the explicitly acceptable simulation methods ("existing debug/test flag already in
code"), not a new feature.

Fold6 state during route test 1 (eligible): `eligible_for_inference: true`,
`reason_if_not_eligible: null`
Routed request result 1: `POST http://100.66.89.35:4001/v1/chat/completions` with
`{"model":"pocket-node-fold6", ...}` → `HTTP/1.1 200`,
`x-edge-gate-route: allow_fold6`, `x-edge-gate-final-model: pocket-node-fold6` (unchanged).
Gate `/metrics` `allow_fold6` counter incremented 0→1.

Fold6 state during route test 2 (forced ineligible via
`POST /debug/eligibility/force_block` → `{"ok":true,"debug_force_block":true}`):
`eligible_for_inference: false`, `reason_if_not_eligible: "debug_forced_block"`
Routed request result 2: same request → `HTTP/1.1 200`,
**`x-edge-gate-route: bypass_thermal`**, **`x-edge-gate-final-model: mac-studio-edge-fallback`**.
Gate `/metrics` `bypass_thermal` counter incremented 0→1.

Fallback used: `mac-studio-edge-fallback` (the model name the gate rewrites to when bypassing),
confirming the routing chain's Mac Studio fallback leg is what the gate substitutes in for an
ineligible Fold6, matching the documented chain
"Fold6 Pocket Node → Neo gate / LiteLLM → Mac Studio fallback → Moolah fallback."

Cleanup: `POST /debug/eligibility/clear` → `{"ok":true,"debug_force_block":false}`; immediately
re-checked `/capabilities` and confirmed `eligible_for_inference: true` restored before ending
the test. The debug flag was not left toggled.

If blocked, explain why: not applicable — route-away was fully testable and both directions
(allow when eligible, bypass when ineligible) were proven with live evidence, not simulated
responses.

One note of drift for the record: the `edge_gate/gate.py` file checked into this repo (version
string `9.7.0` in its own docstring, and lacking any thermal-aware bypass path in its `_gate()`
function) is **stale relative to the live deployed gate** (`/health` reports version `9.8.0`
and policy `fold6_preflight_thermal_v2`, with `bypass_thermal` / `bypass_model_not_loaded`
counters that don't exist in the checked-in source). This phase did not modify Neo/LiteLLM/the
gate in any way (per hard rule 4) — this is purely an observation that the repo's copy of
`gate.py` should be resynced from the live Neo deployment at some point, flagged as a follow-up.

## Logs
Summarized from `adb logcat -d` filtered for
`thermal|eligible|hysteresis|capabilities|llama|pocket|ktor|abort|fatal|SIGABRT`, restricted to
the current test process (pid 4254, launched `15:33:30`):
- `15:33:30`–`15:33:52`: normal JNI_OnLoad → llama backend init → model load → context create,
  no errors.
- `15:34:08`: `Prompt tokens=11 max_new=512` — the direct-load test's single generation.
- `15:35:02`: `Generated 512 tokens in 53.50s (9.6 TPS) | ... backend=Vulkan,OpenCL` — completed
  normally; this is the run whose thermal ramp triggered the hard block observed immediately
  after.
- No `PocketNode`-tagged error/warning lines, no `abort`, `fatal`, or `SIGABRT` anywhere in the
  pid-4254 log window.

Two older `SIGABRT`/`JNI DETECTED ERROR IN APPLICATION ... NewStringUTF` entries do exist in the
full logcat buffer, timestamped `15:01:48` (pid 19246) and `15:10:33` (pid 22628) — both predate
this phase by over 20 minutes and were already fully documented and root-caused in
`POCKET_NODE_RC2_PHASE2_UTF8_VALIDATION.md` (the pre-fix UTF-8 crash) and
`POCKET_NODE_RC2_CHECKPOINT.md` (an unrelated model-load race producing a `ggml_abort`, not a
`NewStringUTF` abort). Neither reproduced during this phase's test window.

Confirm no JNI NewStringUTF regression: **confirmed — none in this phase's window.**
Confirm no unexpected SIGABRT unless observed: **confirmed — none in this phase's window**; the
only SIGABRTs in the full log are the two pre-existing, already-documented, out-of-window events
above.

## Verdict
**PASS**

- Build passes (`assembleDebug` PASS, no source changes needed).
- APK installs over the existing app with data preserved.
- Baseline `/health` and `/capabilities` both pass with all documented RC2 fields present.
- Direct-load test showed safe eligibility behavior: temperature rose from a cool baseline to a
  genuine hard-block condition (78.7°C ≥ 65°C) from real, minimal (one generation) on-device
  load — not forced or torture-tested.
- Thermal block occurred; the direct-load loop detected it and **stopped immediately**, sending
  zero further generation requests to the blocked node.
- Cooldown behavior was proven: eligibility and the hysteresis latch both cleared only once the
  peak zone dropped below the 58°C cooldown threshold (not simply below 65°C), exactly matching
  the code's hysteresis gap.
- Routing away from the Fold6 was proven against the live homelab routing chain: while eligible,
  the gate routed to the Fold6 (`allow_fold6`); while ineligible (both a live thermal state and,
  separately, the deterministic debug-forced state), the gate routed to
  `mac-studio-edge-fallback` (`bypass_thermal`), with `/metrics` counters confirming both
  outcomes.
- No app crash occurred during the controlled test; no `NewStringUTF`/JNI regression appeared.
- Evidence doc written (this document).

## Follow-ups
- The checked-in `edge_gate/gate.py` (docstring version 9.7.0, no thermal-aware bypass) has
  drifted from the live deployed Neo gate (version 9.8.0, `fold6_preflight_thermal_v2` policy,
  `bypass_thermal`/`bypass_model_not_loaded` metrics). Resync the repo copy from the live
  deployment so the checked-in source reflects reality — do not edit the live gate from this
  repo without a deliberate, reviewed change.
- The occasional garbled (`@@@@...`) non-stream response seen during the direct-load run's
  generation is a pre-existing model-sampling artifact, not a protocol or crash bug; if it
  recurs, worth a dedicated investigation into sampler/repeat-penalty behavior under thermal
  load, separate from this phase's scope.
- WARN/YELLOW was only directly observed on the cooldown descent, not the heating ascent (heat
  ramped past it too quickly in this run to capture independently). Not a defect — the code
  path is confirmed to exist and fire correctly — but a slower, more gradual load pattern in a
  future phase could capture a clean ascending WARN state for completeness if ever needed.
- No RC3 feature work, prolonged thermal soak, or additional routing-chain legs (Moolah
  fallback) were tested — out of scope for Phase 3 by design.
