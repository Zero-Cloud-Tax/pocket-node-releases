# Terminal Evidence — Redacted

This directory contains redacted terminal evidence from the P27 validation run.
All Tailscale IPs have been replaced with `[MESH-IP]`.
All `/home/[user]/` paths have been anonymized.
The `/capabilities` response `node` field has been replaced with `[owner]'s Z Fold6`.

---

## Evidence Included

Paste these code blocks directly into documentation or posts.
See `approved_screenshot_manifest.md` (internal) for the source of each block.

---

### TE-01 — Gate Health Check

```json
{
  "status": "ok",
  "service": "pocket-edge-gate",
  "version": "9.8.0",
  "upstream": "[MESH-IP]:4000"
}
```

---

### TE-02 — Decision Log Excerpt

```jsonl
{"ts":"2026-06-14T19:41:54Z","msg_count":1,"body_bytes":113,"final_model":"pocket-node-fold6"}
{"ts":"2026-06-14T19:41:54Z","msg_count":1,"body_bytes":12594,"final_model":"mac-studio-edge-fallback"}
{"ts":"2026-06-14T20:02:52Z","msg_count":1,"body_bytes":122,"final_model":"pocket-node-fold6"}
{"ts":"2026-06-14T20:02:53Z","msg_count":1,"body_bytes":12594,"final_model":"mac-studio-edge-fallback"}
{"ts":"2026-06-14T20:03:06Z","msg_count":1,"body_bytes":118,"final_model":"pocket-node-fold6"}
```

Reading guide:
- `body_bytes=113` → small prompt → `pocket-node-fold6` (Fold6, Tier 1)
- `body_bytes=12594` → oversized (>12,000 bytes) → `mac-studio-edge-fallback` (Mac Studio, Tier 2)

---

### TE-03 — Fold6 /capabilities Response (Redacted)

```json
{
  "node": "[owner]'s Z Fold6",
  "role": "edge_llm",
  "service_alive": true,
  "model_loaded": true,
  "battery_percent": 99,
  "charging": true,
  "thermal_status": "none",
  "eligible_for_inference": true,
  "reason_if_not_eligible": null,
  "last_inference_at": "2026-06-14T20:02:53Z",
  "peak_thermal_zone_c": 37.0,
  "peak_thermal_zone_type": "pmr735d_tz",
  "peak_cpu_zone_c": 36.6,
  "peak_gpu_zone_c": 34.8,
  "thermal_zone_readable_count": 81,
  "thermal_zone_error_count": 0,
  "thermal_zone_gate_reason": null,
  "battery_temperature_c": 30.9
}
```

Note: `thermal_status: "none"` is the PowerManager API response — unreliable.
`peak_thermal_zone_c: 37.0` is the raw sysfs poll — used by the gate.

---

### TE-04 — LiteLLM Restart Policy Proof

```sh
$ docker inspect litellm --format '{{.HostConfig.RestartPolicy.Name}}'
always

$ docker ps --filter name=litellm --format 'table {{.Names}}\t{{.Status}}\t{{.RestartCount}}'
NAMES     STATUS          RESTARTCOUNT
litellm   Up 21 minutes   0
```

---

### TE-05 — Gate Systemd Status

```sh
$ systemctl --user is-enabled pocket-edge-gate
enabled

$ systemctl --user is-active pocket-edge-gate
active
```

---

### TE-06 — Smoke Test Route Headers

```
Smoke A (small prompt):
  x-edge-gate-route:       allow_fold6
  x-edge-gate-final-model: pocket-node-fold6

Smoke B (oversized, 12,500 chars):
  x-edge-gate-route:       bypass_oversized
  x-edge-gate-final-model: mac-studio-edge-fallback

Smoke C (stream:true):
  x-edge-gate-route:       allow_fold6
  x-edge-gate-final-model: pocket-node-fold6
  content-type:            text/event-stream
  SSE chunks received: 2, [DONE] received: true
```

---

## Device Screensho