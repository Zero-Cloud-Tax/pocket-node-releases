# Pocket Node — Validation Proof Points

All results from direct measurement during the P27 validation series.
No synthetic benchmarks. No extrapolation. Results are specific to the
hardware and configuration described.

---

## Inference Runtime

| Test | Result | Evidence | Caveat |
|------|--------|----------|--------|
| Ollama RC2 running on Galaxy Z Fold6 | PASS | Ollama API responded on device, `/api/tags` returned loaded models | Manual install; not Play Store |
| Vulkan backend active | PASS | `gpu_layers=39` confirmed in Ollama model info; inference speed consistent with GPU offload | Backend config must be set explicitly |
| CPU fallback baseline | MEASURED | CPU-only path tested; significantly slower | Not used in production path |
| SmolLM3 3.1B Q4_0 loaded | PASS | Model listed in `/api/tags`, completions generated | Single model validated; other models not tested |
| ~13+ TPS throughput (Vulkan path) | PASS | Measured via token count / elapsed time in gate response | Hardware-specific; varies with thermal state and concurrent load |
| Throughput stable across 60-min soak | CONDITIONAL | TPS remained in usable range; minor variance observed | Small sample; battery drain not documented |
| stream:false completion | PASS | Full JSON response received end-to-end through gate | — |
| stream:true SSE | PASS | SSE `data:` chunks received at client; `data: [DONE]` terminator confirmed | — |

---

## Routing Gate

| Test | Result | Evidence | Caveat |
|------|--------|----------|--------|
| Gate starts on boot (systemd linger) | PASS | Reboot drill D.1.2: gate active with no manual login, PID confirmed | Requires `loginctl enable-linger` configured |
| `x-edge-gate-route` header present | PASS | Header present on all routed responses | — |
| `allow_fold6` fires for small prompt | PASS | Route header = `allow_fold6`, Fold6 `/capabilities` timestamp advanced | — |
| `bypass_oversized` fires for >8000-char prompt | PASS | 12,500-char payload → route = `bypass_oversized`, Mac Studio answered | Only tested on pocket-node-fold6 alias |
| `bypass_oversized` fires for >12000-byte body | PASS | Confirmed via gate logic; 12,500-byte payload triggered bypass | — |
| `bypass_thermal` fires on thermal event | PASS | 76.8°C spike → `eligible_for_inference=false` → `bypass_thermal` routed | Real hardware event, not synthetic |
| `bypass_model_not_loaded` fires when Fold6 unreachable | PASS | `/capabilities` timeout → correct bypass | — |
| Decision log written (JSONL) | PASS | Log entries verified on gateway host after each routed request | — |
| Canonical alias `pocket-node-fold6` required | CONFIRMED | Alias mismatch (`pocket/fold6`) → `pass_through`, no routing logic applied | Clients must use exact canonical alias |

---

## Thermal Safety

| Test | Result | Evidence | Caveat |
|------|--------|----------|--------|
| `PowerManager.thermal_status` accurate during soak | FAIL (API gap) | Reported `none` while raw sensors showed elevated temps | Do not rely on this API for real-time gating |
| Raw `/sys/class/thermal/thermal_zone*/temp` readings | PASS | Real temperature values observed; 76.8°C spike captured | Not a public stable API; sysfs only |
| 76.8°C hard-block event handled | PASS | Gate blocked inference at spike; routed to Mac Studio; recovered on cooldown | Single event observed during soak |
| Cooldown → eligibility restored automatically | PASS | Gate re-polled after cooldown; `allow_fold6` restored without intervention | Cooldown time not precisely measured |
| No inference sent to phone during thermal block | PASS | Decision log confirmed `bypass_thermal` on all requests during block period | — |

---

## Failover and Mesh

| Test | Result | Evidence | Caveat |
|------|--------|----------|--------|
| Mac Studio reachable via private mesh | PASS | `/api/tags` responded; inference requests served | Tailscale required |
| Moolah reachable via private mesh | PASS | `/api/tags` responded | — |
| Mac Studio → Moolah failover (blackhole drill) | PASS | Mac Studio port blocked; LiteLLM fell through to Moolah automatically | Tests LiteLLM fallback config, not gate logic |
| Continue.dev client integration | PASS | Completions received in IDE via gate | OpenAI-compatible endpoint; no plugin changes needed |
| All mesh nodes reachable post-reboot | PASS | D.1.2: Mac, Moolah, Fold6 all responsive within 24s of Neo reboot | Tailscale mesh reformed automatically |

---

## Reboot Survival (D.1.2)

| Test | Result | Evidence | Caveat |
|------|--------|----------|--------|
| Neo reboot issued | PASS | `sudo systemctl reboot` executed at 2026-06-14T19:40:52Z | — |
| Neo Tailscale recovery | PASS | SSH reachable at T+24s | On private Tailscale mesh |
| Gate auto-started | PASS | systemd linger; gate active, new PID, no manual login | — |
| LiteLLM auto-started | PASS | Docker `restart: always`; container running post-reboot | Fixed from `unless-stopped` in D.1.1 |
| LiteLLM restart policy after reboot | `always` | `docker inspect litellm` confirmed | — |
| Post-reboot smoke A (small) | PASS | `allow_fold6`, Fold6 answered | — |
| Post-reboot smoke B (oversized) | PASS | `bypass_oversized` → Mac Studio | — |
| Post-reboot smoke C (stream) | PASS | `allow_fold6`, 2 SSE chunks, `[DONE]` | — |
| Total recovery time | **24 seconds** | Timestamps in reachability trace | Single measurement; may vary |

---

## What Failed and Was Fixed

| Issue | Initial Behavior | Root Cause | Fix Applied |
|-------|-----------------|------------|-------------|
| LiteLLM down after reboot | LiteLLM not running post-reboot D.1 | `restart: unless-stopped` doesn't retry non-zero exits on daemon start | Changed to `restart: always` |
| Oversized bypass not firing (D.1) | Route returned `pass_through` | Test used alias `pocket/fold6`, not `pocket-node-fold6`; gate skipped all routing logic | Switched to canonical alias |
| Streaming failed (D.1) | LiteLLM model-not-found error | `pocket/fold6` alias not in LiteLLM model registry | Added alias; switched to canonical |
| `PowerManager.thermal_status = none` during spike | Thermal gate saw no threat | Android PowerManager API too coarse for hardware-level gating | Implemented raw thermal zone polling |
| Phase 1 compose regex patched wrong service | `caddy` got `restart: always`, not `litellm` | Regex matched first occurrence in multi-service compose file | Used `yaml.safe_load` + targeted litellm service update |
| `FOLD6_MAX_CHARS` read as `8` not `8_000` | Diagnostic script misread threshold | Python underscore notation in source; regex matched `8` before `_` | Confirmed via direct source read: correctly `8_000` |

---

*All tests run on physical hardware. No emulation. No synthetic l