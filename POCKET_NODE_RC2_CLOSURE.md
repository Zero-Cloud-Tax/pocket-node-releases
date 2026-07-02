# Pocket Node RC2 Closure

Date/time: 2026-07-02, 15:40 local
Branch: main
Closure commit: (see final response — inserted after this phase's evidence commit is created)
RC2 tag: v0.1.0-rc2
Device: Samsung SM-F956U (Galaxy Z Fold 6), Android 16
Native baseline: `-march=armv8.4-a+dotprod+i8mm` (Fold6-optimized, restored in Phase 2.6,
commit e7e94f7)
APK type: debug (`app-debug.apk`); release build intentionally fails only on missing
`POCKETNODE_PRO_HMAC_SECRET` signing material, per Phase 1

## Source commits
- 2438369 — P28: close Pocket Node RC2 API and UTF-8 stability gates
- e7e94f7 — P28: restore Fold6 native optimization baseline for RC2
- Phase 3 evidence commit: (see final response)

## Gates
Build: PASS (`assembleDebug`; `assembleRelease` fails only on expected missing HMAC secret)
Install/upgrade: PASS (`adb install -r` over existing app, model/Room DB/app data preserved
across every phase, no uninstall performed at any point in P28)
Health: PASS (`/health` returns `status: ok`, `model_loaded: true`)
Capabilities: PASS (`/capabilities` returns all documented RC2 fields; no field renamed across
any phase)
stream=false: PASS (Phase 1 fix — `/api/generate` returns a single consolidated
`{"response": "...", "done": true}` object when `stream:false`)
stream=true: PASS (unchanged NDJSON per-token streaming, verified after both the Phase 1 and
Phase 2 fixes)
JNI UTF-8: PASS (Phase 2 — native token callback buffers bytes and only emits complete, valid
UTF-8 through `NewStringUTF`; four Unicode stress probes and the Phase 3 direct-load run all
completed with zero `NewStringUTF`/JNI aborts)
Native arch baseline: PASS (Phase 2.6 — Fold6-optimized `armv8.4-a+dotprod+i8mm` restored and
committed as an explicit decision; generic-ARM64 change identified as pre-existing, unrelated,
and deliberately deferred)
Thermal hard-block: PASS (Phase 3 — real hard-block reached naturally from a single 512-token
generation, at 78.7°C against a 65°C threshold; direct-load loop stopped immediately per the
"do not keep sending load once blocked" rule)
Cooldown/hysteresis: PASS (Phase 3 — eligibility and the hard-block hysteresis latch both
cleared only once the peak zone fell below the 58°C cooldown threshold, not merely below 65°C,
matching the code's actual gap)
Route-away: PASS (Phase 3 — live Neo edge gate policy `fold6_preflight_thermal_v2` correctly
routed to the Fold6 while eligible and to the `mac-studio-edge-fallback` fallback while
ineligible, confirmed via response headers and `/metrics` counters)

## Thermal proof summary
Baseline: eligible, cool (peak CPU/GPU zones 44.4°C / 41.8°C, `thermal_zone_gate_reason: null`)
Peak: 78.7°C peak CPU/OS-zone temperature (zone `cpu-2-2-1`), reached from one real 512-token
generation — not forced or stress-tested
Hard-block reason: `thermal_zone_hard_block` (peak ≥ 65.0°C hard-block threshold)
Cooldown: peak zone fell to 55.6°C within a single 10-second poll interval after the load
stopped
Eligibility restored: yes — `eligible_for_inference: true`, `reason_if_not_eligible: null`,
gate reason downgraded to `thermal_zone_warn` (peak ≥ 55°C, telemetry-only, inference allowed)

## Routing proof summary
Gate: Neo edge gate, `http://100.66.89.35:4001`
Policy: `fold6_preflight_thermal_v2` (live, pre-flight-checks the Fold6's own `/capabilities`
before routing)
Eligible result: `x-edge-gate-route: allow_fold6`, model left as `pocket-node-fold6`,
`/metrics` `allow_fold6` counter incremented
Ineligible result: `x-edge-gate-route: bypass_thermal` (ineligibility held via the existing
`/debug/eligibility/force_block` test endpoint — an already-in-code simulation method, not a
new feature — then cleared afterward), `/metrics` `bypass_thermal` counter incremented
Fallback: `mac-studio-edge-fallback`, matching the documented chain
"Fold6 Pocket Node → Neo gate / LiteLLM → Mac Studio fallback → Moolah fallback"

## Known follow-ups
1. Repo hygiene: checked-in `edge_gate/gate.py` is stale at v9.7.0 (its own docstring, and no
   thermal-aware bypass path in its `_gate()` function) while the live deployed Neo gate is
   v9.8.0 and thermal-aware (`fold6_preflight_thermal_v2` policy, `bypass_thermal`/
   `bypass_model_not_loaded` metrics observed live in Phase 3). This repo's copy does not
   reflect what is actually running in production.
2. Consider syncing the live gate's source back into this repo as a separate,
   non-runtime-changing documentation/reconciliation phase — pull the current `gate.py` from
   the Neo host, diff it against the checked-in copy, and commit the reconciled version with a
   commit message that makes clear no live behavior changed, only the repo's record of it.
3. Generic ARM64/RK3576 Apolo tablet support remains deferred to a future, explicit
   build-flavor phase (Phase 2.6's Option C) — not part of RC2.
4. RC3 planning should focus on app service hardening (e.g. the model-load race `ggml_abort`
   noted during Phase 2.5/2.6 testing, unrelated to the UTF-8 fix) and operator UX, not new
   inference features.

## RC2 verdict
PASS
