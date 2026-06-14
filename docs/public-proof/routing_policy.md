# Pocket Node — Routing Policy Reference

Gate version: Phase 9.8.0
Policy name: `fold6_preflight_thermal_v2`

---

## Overview

The edge gate intercepts every OpenAI-compatible inference request and applies
routing logic before any tokens are generated. The gate only applies routing
policy to requests using the canonical model alias `pocket-node-fold6`. Requests
using any other alias pass through directly to LiteLLM without gate routing.

---

## Canonical Alias Requirement

**The gate ONLY routes `pocket-node-fold6`.** All other aliases are `pass_through`.

This is not a bug — it is intentional. The gate is designed to intercept a
specific alias that represents "route this through the Pocket Node stack with
full thermal + oversize checking". Using any other alias (including the
convenience alias `pocket/fold6`) bypasses all routing logic.

Clients must set their model to `pocket-node-fold6` to use gate routing.

---

## Routing Decision Table

| Priority | Condition | Route Name | Destination |
|----------|-----------|------------|-------------|
| 1 | Model alias is not `pocket-node-fold6` | `pass_through` | LiteLLM (direct) |
| 2 | `/capabilities` unreachable or timeout | `bypass_model_not_loaded` | Mac Studio → Moolah |
| 3 | `eligible_for_inference = false` from capabilities | `bypass_thermal` | Mac Studio → Moolah |
| 4 | Any thermal zone ≥ 65°C from capabilities | `bypass_thermal` | Mac Studio → Moolah |
| 5 | Raw body > 12,000 bytes | `bypass_oversized` | Mac Studio → Moolah |
| 6 | Message text > 8,000 characters | `bypass_oversized` | Mac Studio → Moolah |
| 7 | Message count > 5 | `bypass_oversized` | Mac Studio → Moolah |
| 8 | Tool calls present in request | `bypass_tools` | Mac Studio → Moolah |
| 9 | All checks pass | `allow_fold6` | Fold6 (Tier 1) |

Conditions are evaluated top to bottom. First match wins.

---

## Response Headers

Every routed response includes gate headers:

| Header | Example Value | Meaning |
|--------|--------------|---------|
| `x-edge-gate-route` | `allow_fold6` | Route taken |
| `x-edge-gate-final-model` | `pocket-node-fold6` | Model alias used by LiteLLM |
| `x-edge-gate-est-tokens` | `28` | Estimated token count of input |

---

## Oversized Thresholds

| Dimension | Threshold | Notes |
|-----------|-----------|-------|
| Raw request body | > 12,000 bytes | Includes all message JSON overhead |
| Message text | > 8,000 characters | Sum of all message content strings |
| Message count | > 5 messages | Includes system + user + assistant history |

Any one of these triggers `bypass_oversized`. All three are checked independently.

---

## Bypass Destinations

All bypass routes target the Mac Studio (Tier 2) as primary destination, with
automatic LiteLLM fallback to Moolah (Tier 3) if Mac Studio is unavailable.
This fallback is handled by LiteLLM's router configuration, not the gate.

---

## Decision Log

Every routing decision is appended to a JSONL log file on the gateway host.
Log schema:

```json
{"ts": "2026-06-14T19:41:54Z", "msg_count": 1, "body_bytes": 113, "final_model": "pocket-node-fold6"}
{"ts": "2026-06-14T19:41:54Z", "msg_count": 1, "body_bytes": 12594, "final_model": "mac-studio-edge-fallback"}
```

Fields:
- `ts` — ISO 8601 timestamp of the decision
- `msg_count` — number of messages in the request
- `body_bytes` — raw request body size in bytes
- `final_model` — model alias actually used (indicates route taken)

Route is inferred from `final_model`:
- `pocket-node-fold6` → `allow_fold6`
- `mac-studio-edge-fallback` → one of the bypass routes

---

## Pre-Flight Check (/capabilities)

Before routing to `allow_fold6`, the gate polls the Fold6's `/capabilities`
endpoint. This is a custom endpoint served by the Ollama companion service
on the Android device.

Key fields used for routing:

| Field | Type | Used For |
|-------|------|---------|
| `eligible_for_inference` | bool | Direct block if false |
| `peak_thermal_zone_c` | float | Block if ≥ 65°C |
| `thermal_status` | string | **NOT used** — see thermal safety docs |
| `model_loaded` | bool | Block if false |
| `service_alive` | bool | Block if false |

See [`thermal_safety.md`](thermal_safety.md) for why `thermal_status` is not
used despite being present in the response.

---

## Gate Source

Gate source (`gate.py`) is not yet included in this repository. A cleaned
release version with all constants externalized to environment variables is
planned for the n