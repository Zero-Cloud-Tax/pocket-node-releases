# Pocket Node RC3 — Validation Proof Points

All results from direct, on-device measurement during the RC3 hardening
series (RC3.1–RC3.5). No emulation, no synthetic load. Device: Samsung
Galaxy Z Fold 6 (Android 16). Results are specific to this hardware and
app version.

---

## Model-Load Race Serialization (RC3.1)

| Test | Result | Evidence | Caveat |
|------|--------|----------|--------|
| `ggml_abort` race reproduced pre-fix | CONFIRMED | Direct reproduction under 8-iteration rapid install/relaunch stress loop | Regression from RC2, targeted fix |
| Kotlin-side mutex serialization (`ModelLoadCoordinator`) | PASS | `model_load_join_inflight` observed live in logcat during stress loop | Shared across service + ViewModel load/unload/draft/benchmark paths |
| Native-side mutex serialization (`g_lifecycle_mutex`) | PASS | Wraps all 8 lifecycle JNI functions as defense-in-depth | Separate from the existing inference mutex |
| Zero crashes after fix | PASS | 8-iteration stress loop, zero `ggml_abort`/`SIGABRT` | Same stress pattern that reproduced the original race |

---

## Foreground Service Lifecycle (RC3.2)

| Test | Result | Evidence | Caveat |
|------|--------|----------|--------|
| Live state-driven notification (Starting/Loading/Serving/Unavailable/Stopping) | PASS | All states observed rendering on real device behavior | Sourced from existing `ApiServer` eligibility, no schema change |
| "Stop serving" notification action | PASS | Tapped live; confirmed `STOP_ACTION_RECEIVED` → drain → `SERVICE_STOPPED` | — |
| Cold-restart recovery after stop | PASS | Restarted cleanly, model reloaded | — |
| `START_STICKY` / `START_NOT_STICKY` split | PASS | Operator-requested stop is not resurrected by the OS; default path may recover from low-memory kill | — |

---

## Diagnostics Screen (RC3.3)

| Test | Result | Evidence | Caveat |
|------|--------|----------|--------|
| Service card (state/uptime/session/last inference/last error) | PASS | Verified live on-device, sourced from existing internal state | No ADB/logcat dependency for operators |
| Thermal / Eligibility card | PASS | Verified live, reuses existing `ThermalZoneReader`/eligibility logic | No new thresholds introduced |
| All four coarse service states observed | PASS | Serving / Loading / Unavailable / Stopped each independently rendered correctly | Observed against genuine, unforced device behavior |

---

## Model Inventory UX (RC3.4 / RC3.4.1)

| Test | Result | Evidence | Caveat |
|------|--------|----------|--------|
| Export Inventory (JSON, share sheet) | PASS | Real Android share sheet; pulled file showed valid JSON for both installed models with accurate size/SHA-256/GGUF validity | Uses existing FileProvider path, no new permissions |
| Per-model Reverify action | PASS | Live snackbar ("Reverified ... VERIFIED"), log evidence (`model_reverify_start`/`_success`), SHA-256 prefix matched exported inventory exactly | Closed a CONDITIONAL gap from initial RC3.4 pass via RC3.4.1 log evidence |
| Quarantine/cleanup | REUSED, PASS | Existing bulk "Clean Failed Primary" logic covers this; no new logic added | Deliberate scope decision, not a gap |

---

## Boot / Upgrade / Cold-Start Survival (RC3.5)

| Test | Result | Evidence | Caveat |
|------|--------|----------|--------|
| Upgrade survival (`ACTION_MY_PACKAGE_REPLACED`) | PASS | 5 rapid `install -r` cycles, clean start sequence every time, zero duplicate loads, zero `ggml_abort`/`SIGABRT` | — |
| Manual stop safety | PASS | Settings "Enable Edge API" toggle drains and stops cleanly; `/health` unreachable immediately after | — |
| Cold-start recovery (`force-stop` + relaunch) | PASS | `/health` restored within 8s; Diagnostics UI directly showed Loading → Serving transition | — |
| Physical device reboot | NOT PERFORMED | Deliberately skipped on the developer's daily-use device | Underlying start logic is shared with the verified upgrade-restart path; high confidence without disruption |

---

## API Regression (Checked Across All RC3 Phases)

| Test | Result | Evidence | Caveat |
|------|--------|----------|--------|
| `/health` | PASS | Verified before/after every phase's device testing | — |
| `/capabilities` | PASS | Schema unchanged throughout RC3 | — |
| `stream=false` | PASS | Full JSON response verified repeatedly | — |
| `stream=true` | PASS | NDJSON per-token streaming verified, process stability confirmed | — |

---

## Fresh Fold6 Validation (post-hygiene commits)

Fresh Fold6 validation was captured after RC3 hygiene commits. The run
confirmed verified model identity, Vulkan/OpenCL backend, successful
generation, and thermal behavior below the WARN threshold. Detailed
engineering benchmark numbers remain in internal artifacts and are not
part of the public RC3 proof package.

API-visible performance/thermal stats in `/api/generate` and OAI-compatible
responses remain deferred, consistent with earlier RC3 scope decisions —
no change to the API contract was made or is planned for this checkpoint.

---

## What Failed and Was Fixed

| Issue | Initial Behavior | Root Cause | Fix Applied |
|-------|-------------------|------------|-------------|
| `ggml_abort` on rapid reinstall/relaunch | Native crash under concurrent load/unload | No serialization between Kotlin-side and native-side model lifecycle calls | Added `ModelLoadCoordinator` (Kotlin mutex) + `g_lifecycle_mutex` (native mutex), both proven under stress |
| Reverify Room write-back unverifiable off-device | WAL not checkpointed; no `sqlite3` on device | Tooling limitation, not an app defect | Added observability log lines instead of pushing a `sqlite3` binary; confirmed via log + snackbar + matching SHA-256 |
| Notification shade hard to tap precisely via ADB automation | Accidental tap opened an unrelated app's reply compose field | ADB screenshot-pixel tap estimation, not a display-scale-aware coordinate source | Backed out immediately (no message sent); switched to `uiautomator dump` for exact coordinates in later phases |

---

*All tests run on physical hardware (Galaxy Z Fold 6, serial redacted). No
emulation. No synthetic load. Results are specific to the hardware and
app version described in the release notes.*
