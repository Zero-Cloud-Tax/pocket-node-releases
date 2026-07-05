# Pocket Node RC3 Fresh Benchmark — Fold6

Captured: 2026-07-05T16:11:00Z – 16:12:36Z (device local ~12:11–12:12)

## Build under test

- Commit tested: `ac676e3b00f190940cc0562db956ce07970c4302` (`ac676e3`) — "Document Pocket Node model identity policy", `main`
- APK/build tested: `app/build/outputs/apk/debug/app-debug.apk` (assembleDebug, fresh rebuild confirmed up-to-date against this commit; installed via `adb install -r`)
- App versionCode / versionName: `4` / `0.1.0-rc2` (unchanged since RC2 — no version bump has occurred across the RC3 hardening/hygiene commits tested here)
- Package: `com.pocketnode.app`

## Device

- Model: `SM-F956U` (Samsung Galaxy Z Fold6)
- Android: 16 (`BP4A.251205.006`, build `F956USQS4DZF2`)
- Connectivity used: Tailscale (`[REDACTED_PRIVATE_MESH_IP]:11434`)
- Pre-run state: battery 92-97%, temperature ~31-33°C, plugged in via USB (not wireless), device otherwise idle

## Model identity (pulled from on-device Room DB, `models` table)

| id | name | role | sha256 | verification_status | size_bytes |
|---|---|---|---|---|---|
| 4b7609cd-fcf4-489c-8b7b-41ab4d53f5d0 | `PocketNode_SmolLM3_Q4_0_Fresh` | MAIN | `dde7bbbffea19de3760c543661eb92fa2ae5946ad5561ad0d39a99f99c096c35` | **VERIFIED** | 1,805,813,792 |
| e8dd8be6-6e85-469d-bacb-ee10cb5b1fac | `SmolLM2 135M Draft (Q4_0)` | DRAFT | `bcc3af2849ad6095af57e9b5cd43775256efdc66e306acb529172f92d0c04b03` | **VERIFIED** | 91,893,088 |

SHA matches `HashUtils.KNOWN_HASHES["PocketNode_SmolLM3_Q4_0_Fresh"]` in source at commit `ac676e3` — confirms the model-identity policy documented in [docs/pocket-node/model-identity.md](../docs/pocket-node/model-identity.md) is functioning as described (filename→hash lookup drives `VERIFIED`, independent of GGUF `general.name`).

## Backend

`Vulkan,OpenCL` (confirmed in native generation logs, all 3 passes).

## Endpoint checks

- `/health` (pre and post): `{"status":"ok","node":"pocket-node","device":"android","model_loaded":true,...}`
- `/capabilities` (pre-run): `eligible_for_inference: true`, `thermal_status: "none"`, `peak_thermal_zone_c: 39.3`
- Short generation smoke test (`stream=false`, `max_tokens=16`): succeeded, ~1.9s wall

## Benchmark: 3× 128-token passes (`/api/generate`, `stream=false`, `max_tokens=128`)

Stats sourced from native (`pocketnode_jni.cpp`) `LOGI` output — the API's JSON response body does not currently surface `tps`/`ttft`/`prompt_tps` (all `onStats` callbacks in `ApiServer.kt`'s `/api/generate` and OAI handlers are no-ops; this is a pre-existing API contract limitation, not part of this benchmark task).

| Pass | Wall time (client, ms) | Decode TPS | Prefill/prompt TPS | TTFT (ms) | Total tokens | Backend |
|---|---|---|---|---|---|---|
| 1 | 10,690 | 12.6 | 35.6 | 338 | 128 | Vulkan,OpenCL |
| 2 | 10,667 | 12.6 | 36.3 | 331 | 128 | Vulkan,OpenCL |
| 3 | 10,701 | 12.6 | 35.6 | 339 | 128 | Vulkan,OpenCL |

Native log lines (raw):
```
Generated 128 tokens in 10.13s (12.6 TPS) | prompt_tps=35.6 ttft=338ms | draft_accept=0.00 drafted=0 accepted=0 | backend=Vulkan,OpenCL
Generated 128 tokens in 10.13s (12.6 TPS) | prompt_tps=36.3 ttft=331ms | draft_accept=0.00 drafted=0 accepted=0 | backend=Vulkan,OpenCL
Generated 128 tokens in 10.13s (12.6 TPS) | prompt_tps=35.6 ttft=339ms | draft_accept=0.00 drafted=0 accepted=0 | backend=Vulkan,OpenCL
```

## Thermal telemetry

| Checkpoint | peak_thermal_zone_c | peak_cpu_zone_c | peak_gpu_zone_c | thermal_status | eligible | reason |
|---|---|---|---|---|---|---|
| Pre-run | 39.3 | 39.3 (`cpu-2-2-1`) | 36.3 (`gpuss-0`) | none | true | null |
| Post-run (after 3× 128-tok passes) | 44.25 (`pm8550_tz`) | 43.2 (`cpu-2-0-1`) | 41.8 (`gpuss-0`) | none | true | null |

Delta over the run: +4.95°C on peak thermal zone. All values remained well below `THERMAL_ZONE_WARN_C=55.0`, soft-block (60.0), and hard-block (65.0) thresholds. `thermal_zone_gate_reason` was `null` throughout; no `thermal_block`/`hard_block`/`soft_block` log lines found in the capture window (checked against the full system logcat at capture time — zero hits; only the Pocket Node-scoped excerpt is retained in this artifact, see below).

## Failures

None. All endpoint checks, smoke test, and 3 benchmark passes completed successfully; no thermal gating triggered; no generation errors.

## Files in this artifact directory

- `rc3_benchmark_summary.md` — this file
- `gen_out_1.json`, `gen_out_2.json`, `gen_out_3.json` — raw HTTP response bodies from each 128-token pass
- `pocket_node_log_excerpt.txt` — Pocket Node-scoped log lines only (native generation stats), filtered from the full on-device system logcat. The full, unfiltered system-wide logcat was captured locally during this session but is intentionally **not included** in this artifact or committed to history — a full system logcat can incidentally contain private LAN/network details and unrelated OS/vendor service noise that has no bearing on Pocket Node and is not appropriate to retain or publish.
