# Pocket Node

**Turning an Android phone into a thermally-aware local inference node.**

> Status: Working prototype / proof-of-concept. Not a polished consumer product.
> Validation: Completed. Post-reboot survival confirmed. All routing smokes passing.

---

## What Is This?

Pocket Node is a homelab project that routes OpenAI-compatible inference requests
through a Samsung Galaxy Z Fold6 running a local LLM runtime (Ollama RC2, Vulkan
backend). A FastAPI gateway on a homelab machine intercepts every request, checks
the phone's thermal state and model availability, and decides in real time whether
to let the phone handle the request or bypass it to a stronger local machine.

No cloud inference. No subscriptions. Entirely local.

The Fold6 is not a remote client — it is the inference server. The Android device
runs the model, responds to capability queries, and reports its own thermal status
over the private mesh network. The gateway enforces thermal safety and load policies
before any tokens are generated.

---

## Architecture

```
[Continue.dev / OpenAI-compatible client]
           |
           v
  [Neo Edge Gate — FastAPI :4001]
   fold6_preflight_thermal_v2 policy
           |
     +-----+------+
     |             |
  /capabilities  oversized / tools / thermal-unsafe
  poll (Fold6)        |
     |             v
     |        [LiteLLM Router — Docker :4000]
  eligible          |
     |         +----+----+
     v         |         |
  [Fold6]  [Mac Studio] [Moolah]
  Tier 1   Tier 2       Tier 3
```

See [`architecture/pocket_node_architecture_diagram.mmd`](architecture/pocket_node_architecture_diagram.mmd) for a full Mermaid diagram.

---

## Hardware

| Node | Device | Role |
|------|--------|------|
| Fold6 | Samsung Galaxy Z Fold6 | Primary inference node (Tier 1) |
| Mac Studio | Apple Mac Studio | Fallback inference node (Tier 2) |
| Moolah | Home server | Second fallback node (Tier 3) |
| Neo | Homelab x86 machine | Gateway host (LiteLLM + edge gate) |

All nodes communicate over a private Tailscale mesh. No ports are exposed to the
public internet.

---

## Runtime Stack (Fold6)

- **Runtime:** Ollama RC2
- **Backend:** Vulkan + OpenCL
- **Model:** SmolLM3 3.1B Q4_0
- **GPU layers offloaded:** 39
- **Measured throughput:** ~13+ tokens/second (final validated path)
- **Streaming:** SSE (`stream: true`) — validated end-to-end with `[DONE]` terminator
- **Thermal guard:** raw `/sys/class/thermal/thermal_zone*/temp` polling

---

## Routing Policy

The gate applies `fold6_preflight_thermal_v2` to every incoming request with
model alias `pocket-node-fold6`:

| Condition | Route | Destination |
|-----------|-------|-------------|
| All checks pass | `allow_fold6` | Fold6 |
| Raw body > 12,000 bytes | `bypass_oversized` | Mac Studio → Moolah |
| Message text > 8,000 chars | `bypass_oversized` | Mac Studio → Moolah |
| Message count > 5 | `bypass_oversized` | Mac Studio → Moolah |
| Tool calls present | `bypass_tools` | Mac Studio → Moolah |
| Fold6 `/capabilities` unreachable | `bypass_model_not_loaded` | Mac Studio → Moolah |
| `eligible_for_inference = false` | `bypass_thermal` | Mac Studio → Moolah |
| Thermal zone ≥ 65°C | `bypass_thermal` | Mac Studio → Moolah |

Every decision is logged to a JSONL decision log on the gateway host.

See [`docs/routing_policy.md`](docs/routing_policy.md) for full routing documentation.

---

## Thermal Safety

The Android `PowerManager` thermal API (`thermal_status`) reported `none` even
during sustained inference loads. This API is not reliable for real-time thermal
gating at the hardware level.

The gate instead reads raw thermal zone temperatures from the Fold6 over the
mesh network via a custom `/capabilities` endpoint. During a validated 60-minute
soak, a real temperature spike to **76.8°C** was observed. The gate:

1. Detected the spike via the `/capabilities` poll.
2. Immediately returned `bypass_thermal` for the next request.
3. Routed the request to Mac Studio without contacting the phone.
4. Re-polled after cooldown and restored `allow_fold6` eligibility automatically.

The phone was never sent an inference request while at unsafe temperature. No
manual intervention was required.

See [`docs/thermal_safety.md`](docs/thermal_safety.md) for full thermal documentation.

---

## Validation Summary

| Test | Result |
|------|--------|
| Fold6 Ollama RC2 local inference | PASS |
| Vulkan + OpenCL backend, 39 GPU layers | PASS |
| ~13+ TPS on SmolLM3 3.1B Q4_0 | PASS |
| stream:false completion | PASS |
| stream:true SSE + [DONE] | PASS |
| Thermal hard-block at 76.8°C | PASS |
| Automatic cooldown recovery | PASS |
| Oversized bypass → Mac Studio | PASS |
| Mac Studio blackhole → Moolah failover | PASS |
| 60-minute soak with tooling notes | PASS |
| Continue.dev client integration | PASS |
| Post-reboot: gate auto-start (systemd linger) | PASS |
| Post-reboot: LiteLLM auto-start (Docker restart:always) | PASS |
| Post-reboot routing smokes | PASS |
| Reboot recovery time | 24 seconds |

See [`docs/validation_results.md`](docs/validation_results.md) for full proof-point evidence.

---

## Known Limitations

- **Model size:** The Fold6 can only run small quantized models comfortably.
  SmolLM3 3.1B Q4_0 was the validated path. Larger models may not fit or may
  run at unacceptable speed or thermal cost.
- **Thermal API gap:** Android `PowerManager.thermal_status` is not granular
  enough for real-time inference gating. Raw thermal zone polling is required
  and is not a public stable API.
- **Single-device Tier 1:** If the Fold6 is off, charging, or the screen is
  locked in a way that pauses Ollama, the gate bypasses automatically, but Tier 1
  becomes unavailable until the device recovers.
- **No APK distribution:** The Android-side runtime is not packaged for
  general distribution. Setup requires manual Ollama installation and Tailscale
  configuration.
- **Not a consumer product:** This is a homelab proof-of-concept. Configuration
  requires familiarity with Docker, systemd, Tailscale, and Android developer
  tools.
- **Release migration deferred:** A formal release packaging pass (Dockerfiles,
  install scripts, documentation site) is planned but not yet done.

---

## What Was Fixed During This Project

This project went through multiple validation phases. Key issues discovered and
fixed during the proof run:

- **LiteLLM `restart: unless-stopped`:** Did not reliably restart the container
  after a system reboot when the container had exited non-zero. Fixed to
  `restart: always`.
- **Model alias routing:** The gate only applies routing logic to the canonical
  alias `pocket-node-fold6`. Using any other alias falls through as a
  pass-through without thermal or oversize checking. All clients must use
  the canonical alias.
- **`FOLD6_MAX_CHARS = 8_000`:** Python underscore notation in the gate script
  was misread by initial diagnostic tooling as `8`. Confirmed correctly as 8,000.
- **Thermal API gap:** `PowerManager.thermal_status = none` during real spike.
  Raw thermal zone polling was the only reliable signal.

---

## What Remains Deferred

- Formal release migration (install scripts, Dockerfile packaging)
- APK packaging and distribution
- Automated benchmark suite
- Multi-model support on Fold6
- Battery impact documentation
- Formal API documentation
- Web UI for routing dashboard
- Alerting on thermal bypass events

---

## Next Steps

1. Add screenshots: Ollama running on device, Continue.dev in use, decision log
2. Publish gate source (planned for next iteration)
3. Post to r/LocalLLaMA
4. Begin release migration planning

---

## Repository Structure

```
pocket-node/
  README.md                               <- this file
  LICENSE
  .gitignore
  architecture/
    pocket_node_architecture_diagram.mmd  <- Mermaid diagram
  docs/
    routing_policy.md                     <- full routing policy reference
    thermal_safety.md                     <- thermal safety documentation
    validation_results.md                 <- full proof-point table
  examples/
    gate.env.example                      <- redacted gate env template
    docker-compose.example.yml            <- redacted compose template
    litellm_config.example.yaml           <- redacted LiteLLM config template
  screenshots/
    redacted_terminal_evidence_only/      <- terminal evidence (no device photos yet)
```

---

*Pocket Node is a personal homelab project. It is shared as a working proof
that