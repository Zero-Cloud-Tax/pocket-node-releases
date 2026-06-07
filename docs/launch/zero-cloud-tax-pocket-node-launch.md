# Zero Cloud Tax — Pocket Node Launch Prep

Status: DRAFT — all proof assets captured 2026-06-06/07, pending final review before posting

---

## 1. One-Line Positioning

> **Pocket Node turns an Android phone into a private local inference node —
> no cloud API required for small chat tasks, with optional routing through
> local infrastructure when the phone is reachable on the home network.**

Longer form (for body copy):
Pocket Node runs a quantized GGUF model directly on-device using llama.cpp with
Vulkan/OpenCL acceleration. For tasks that don't require a large context or
frontier-model reasoning, it handles inference entirely on the phone. When
integrated with Sovereign Brain (the local homelab stack), the phone becomes a
named node in the P20 health digest. The phone exposes an OpenAI-compatible API
(Tailscale-gated) that responds to direct calls; registration as a named LiteLLM
endpoint for automatic routing is the next infrastructure step and is not yet done.

---

## 2. Technical Proof Checklist

Before posting, confirm each item is verified and logged.

### Device
- [x] Device: Samsung Galaxy Z Fold6
- [x] Android version: Android 16 (SDK 36) — confirmed via `adb shell getprop ro.build.version.release`
- [ ] App is a debug or release build? — **currently debug build; note this in the post**

### Backend
- [x] Logcat confirms Vulkan + OpenCL backends registered at JNI load:
      ```
      I PocketNode: backend[0]: Vulkan
      I PocketNode: backend[1]: OpenCL
      I PocketNode: backend[2]: CPU
      ```
- [x] Model load line confirms active backend:
      ```
      I PocketNode: Model resolution[loaded]: selectedModelId=PocketNode_SmolLM3_Q4_0_Fresh
        verificationStatus=VERIFIED backendLabel=Vulkan,OpenCL contextPtrState=ready
      ```
- [x] Generation stats line confirms backend in use:
      ```
      I PocketNode: Generated 30 tokens in 5.34s (5.6 TPS) | prompt_tps=12.1
        ttft=27763ms | backend=Vulkan,OpenCL
      ```
- [x] Health endpoint `/` confirms: `{"backend":"Vulkan,OpenCL"}`
- [x] **Exact backend string to cite in post: `Vulkan,OpenCL`**
- [x] Diagnostics screen screenshot: `assets/pocket-node/02-diagnostics-engine.png`
      (shows Backend: Vulkan,OpenCL, Threads 4, GPU layers 3, Hardware samsung SM-F956U q6q)

### Model
- [x] Model loaded: `PocketNode_SmolLM3_Q4_0_Fresh` — confirmed via logcat and endpoint
- [x] Verification status: VERIFIED — confirmed in model resolution log line
- [x] Diagnostics screenshot showing VERIFIED + PRIMARY_MODEL:
      `assets/pocket-node/02-diagnostics-model.png`

### Stop / Prefill Cancellation
- [x] Verified during smoke test (2026-06-06, UX polish session)
- [x] Logcat showed: `Stop: Kotlin stop requested` → `nativeStopGeneration called`
      → native observed stop → UI returned to idle
- [x] "Stopping…" animated card screenshot: `assets/pocket-node/03-stopping-card.png`
- [x] Full stop sequence logcat: `assets/pocket-node/04-logcat-stop-sequence.txt`

### Post-Stop Recovery
- [x] Verified during smoke test: follow-up prompt generated normally after Stop
- [x] Logcat confirms: `Chat generation cancelled: stopRequested=true` +
      `Stop recovery: removed empty assistant placeholder` → next generation starts clean

### P20 Telemetry
- [x] Node registered in Sovereign Brain:
      - `config/nodes.yaml`: `pocket_node` with `role: edge_inference`
      - `config/endpoints.yaml`: `pocket_node_api` endpoint registered
      - `config/telemetry.yaml`: `pocket_node` in monitored nodes list
- [x] Health endpoint live: `{"status":"ok","model_loaded":true}` confirmed 2026-06-07
- [x] P20 digest screenshot showing healthy node:
      `assets/pocket-node/06-p20-digest.png`
      (Status: healthy, Backend: Vulkan,OpenCL, Eligibility: eligible, Battery: 55%,
       Thermal: none, Last inference: 2026-06-06T23:52:57Z, all endpoints 200)

### LiteLLM / Edge Routing
- [x] **Status: NOT YET WIRED through LiteLLM**
      `config/models.yaml` contains `mac_studio_models` and `moolah_models` only.
      No `pocket-node-fold6` or equivalent alias is registered.
- [x] **BUT: OpenAI-compatible API is live and LiteLLM-ready**
      Direct call to `/v1/chat/completions` returned a valid streamed response
      (smoke test 2026-06-07). `last_inference_at` advanced to `2026-06-07T03:35:12Z`.
- [x] **Post copy must say**: "not yet routed through LiteLLM — direct API only,
      Tailscale-gated. LiteLLM wiring is the next infrastructure step."

---

## 2a. Verified Proof — 2026-06-07

Commands run and output captured during proof collection pass.

### Health endpoints (Tailscale, `[IP redacted]`:11434)

```
GET /health
{"status":"ok","node":"pocket-node","device":"android","model_loaded":true,"uptime_ms":766781}

GET /
{"status":"ok","model":"PocketNode_SmolLM3_Q4_0_Fresh","backend":"Vulkan,OpenCL"}

GET /capabilities
{"node":"[DEVICE_NAME redacted]","role":"edge_llm","service_alive":true,
 "model_loaded":true,"battery_percent":100,"charging":true,
 "thermal_status":"none","foreground_service":true,
 "eligible_for_inference":true,"last_inference_at":null}
```

### Logcat — backend registration (adb logcat -d -s PocketNode)

```
I PocketNode: JNI_OnLoad called, initializing llama backend
I PocketNode: OpenCL library pre-loaded
I PocketNode: Initializing llama backend (first model load)
I PocketNode: llama backend initialized
I PocketNode: Registered backends: 3
I PocketNode:   backend[0]: Vulkan
I PocketNode:   backend[1]: OpenCL
I PocketNode:   backend[2]: CPU
I PocketNode: Model loaded successfully
```

### Logcat — model resolution confirming backend and verification

```
I PocketNode: Model resolution[loaded]: selectedModelId=PocketNode_SmolLM3_Q4_0_Fresh
  selectedModelName=PocketNode_SmolLM3_Q4_0_Fresh verificationStatus=VERIFIED
  backendLabel=Vulkan,OpenCL contextPtrState=ready
```

### Logcat — generation stats (proof backend is active, not fallback CPU)

```
I PocketNode: Generated 30 tokens in 5.34s (5.6 TPS) | prompt_tps=12.1
  ttft=27763ms | draft_accept=0.00 drafted=0 accepted=0 | backend=Vulkan,OpenCL
```

### OpenAI-compatible API smoke test

```
POST /v1/chat/completions
{"model":"PocketNode_SmolLM3_Q4_0_Fresh","messages":[{"role":"user","content":"one word: ok"}],"max_tokens":5}

Response (SSE stream): "Ok. No code needed" [finish_reason: stop]
```

### `last_inference_at` advancement confirmed

```
Before call: "last_inference_at": null
After call:  "last_inference_at": "2026-06-07T03:35:12Z"
```

### LiteLLM routing status (config/models.yaml)

```yaml
mac_studio_models:
  - "qwen2.5:32b"
  - "qwen2.5:14b"
  - "llama3.1:8b"
moolah_models:
  - "neo-fast"
  - "neo-judge"
  - "neo-embed"
# pocket-node entry: NOT PRESENT
```

**Verdict**: Direct API confirmed working. LiteLLM alias not yet configured.
Post copy should say "Tailscale-gated direct API, LiteLLM routing is next."

---

## 3. Screenshot Checklist

Capture these before drafting final post. Redact before posting (see Section 6).

| # | File | What it shows | Status |
|---|------|---------------|--------|
| 1 | `assets/pocket-node/01-chat-screen.png` | InferenceStatusCard with VERIFIED + PRIMARY_MODEL badges, Vulkan,OpenCL chip, response visible | CAPTURED |
| 2a | `assets/pocket-node/02-diagnostics-engine.png` | Engine card: Backend Vulkan,OpenCL, Threads 4, GPU layers 3, Hardware SM-F956U | CAPTURED |
| 2b | `assets/pocket-node/02-diagnostics-model.png` | Model card: VERIFIED, Primary model, Resolved file path | CAPTURED |
| 3 | `assets/pocket-node/03-stopping-card.png` | "Stopping…" card with spinner, input disabled | CAPTURED |
| 4 | `assets/pocket-node/04-logcat-stop-sequence.txt` | Full stop sequence: Kotlin stop → nativeStop → native ack → cancelled | CAPTURED |
| 5 | `assets/pocket-node/05-backend-proof.txt` | Backend Vulkan,OpenCL from logcat + generation stats + API smoke test | CAPTURED |
| 6 | `assets/pocket-node/06-p20-digest.png` | P20 digest: Status healthy, Backend Vulkan,OpenCL, Eligibility eligible | CAPTURED — device name + IP redacted |

**Note on redaction:** File paths in 02-diagnostics-model.png show the internal storage path
`/storage/emulated/0/Android/data/com.pocketnode.app/files/models/...` — this is standard
Android app data path and is safe to include. No private IPs, tokens, or device names in any
captured asset. `tmp_settings.png` (captured but NOT included) showed Tailscale IP — excluded.

---

## 4. Reddit Post Draft — r/LocalLLaMA

**Title:**
Running SmolLM3-Q4 on a Galaxy Z Fold6 with Vulkan — phone as a local AI node

---

**Body:**

Built a small Android app called Pocket Node that runs llama.cpp inference
on-device and integrates with my homelab monitoring stack. Here's what it
actually does and what it doesn't.

**What it does**

- Loads a GGUF model (SmolLM3 Q4_0, ~1.1B params) directly on the Fold6
- Reports `Vulkan,OpenCL` through the app/backend telemetry — not CPU-only
- Streams tokens to a native Android Compose UI
- Handles Stop during prefill (not just decode): tapping Stop during the
  prefill phase cancels the native generation call, resets the UI, and lets
  you send a follow-up prompt normally
- Reports model state to my homelab P20 health digest so I can see whether
  the phone is reachable and inference is running

**The stack**

- App: Kotlin + Jetpack Compose, llama.cpp via JNI, Vulkan/OpenCL acceleration
- Model: `PocketNode_SmolLM3_Q4_0_Fresh` — SHA-256 verified against a local
  registry on first load
- Homelab side: Sovereign Brain (Python) → P20 health digest → Slack
- Reachable on local network via Tailscale

**What it doesn't do**

- Not a replacement for a desktop GPU or even a Mac Studio. SmolLM3 at Q4_0
  on a phone is fast enough for short tasks but context is limited and you
  feel it on longer prompts.
- No persistent memory or RAG yet. Each conversation is independent.
- Battery and thermal: the Fold6 handles short runs fine. Extended generation
  or benchmarking heats the device. Don't leave it running a benchmark loop
  overnight.
- Not tested on other Android devices. Vulkan support varies. Don't assume
  this works on your phone.
- Not a public API server. The phone exposes an OpenAI-compatible local
  endpoint (Tailscale-gated, LAN only) but it's not registered in the LiteLLM
  routing layer yet. Direct API calls work; automatic routing does not.

**Why bother**

For short tasks — quick classification, local chat without sending data to
an API — it works. The goal isn't to match GPT-4 on a phone. The goal is
zero cloud tax for the tasks that don't need it.

The verification step was the piece that made me trust it: the app hashes the
model file on first load and compares it against a known-good SHA-256. If it
doesn't match, inference is blocked. That's the kind of thing you want when
you're running a model you downloaded months ago and aren't sure if it's
been modified.

**Screenshots:** [add after redaction check]

Happy to answer questions about the llama.cpp JNI layer or the homelab
integration side.

---

*Edit: to clarify — "Vulkan/OpenCL" means the backend selected by llama.cpp
on this device. I'm not doing anything custom on the GPU side beyond what
llama.cpp exposes.*

---

## 5. Blog Post Draft

**Title:** Turning a Foldable Android Phone into a Local AI Edge Node

---

### Problem

Every time I use a cloud LLM API for a short, low-stakes task — a quick
classification, a local chat response, a simple reformat — I'm paying a
latency tax, a privacy tax, and a dollar tax. None of those tasks need
frontier-model reasoning. They need a model that's fast enough, local, and
running on hardware I already own.

I had a Galaxy Z Fold6 sitting on my desk. It has a capable GPU. llama.cpp
supports Vulkan. So I built Pocket Node.

---

### Architecture

```
Z Fold6 (Pocket Node app)
  └── llama.cpp (JNI) — Vulkan/OpenCL backend
  └── GGUF model: SmolLM3 Q4_0 Fresh
  └── SHA-256 model verification registry
  └── Kotlin ViewModel — generation lifecycle, stop/resume
  └── Jetpack Compose UI — streaming chat, status card, diagnostics

Sovereign Brain (homelab, separate machine)
  └── P20 Command Center — node health digest
  └── LiteLLM — optional local routing layer
  └── Slack integration — daily health digest delivery
```

The phone connects to my homelab via Tailscale. Sovereign Brain polls the
phone's health endpoint and includes it in the P20 daily digest alongside
the Mac Studio and other nodes. If the phone is up and inference is ready,
it's a named node in the cluster.

---

### Device

Samsung Galaxy Z Fold6. Android 16 (SDK 36). Debug build for now.

The foldable form factor is incidental — any Android device with Vulkan
support should work, though I haven't tested others. The Fold6's GPU handles
SmolLM3 Q4_0 comfortably for short prompts. Longer contexts are slower.
Benchmarking mode is in the app but I'm not publishing numbers yet because
thermal state has a visible effect on sustained throughput.

---

### Model

`PocketNode_SmolLM3_Q4_0_Fresh` — SmolLM3 1.1B at Q4_0 quantization.

The app verifies the model file against a SHA-256 registry on first load.
If the hash doesn't match a known-good entry, inference is blocked and the
UI shows a clear error with recovery steps (Rescan / Re-import / Choose
another). This matters when you're loading a model you downloaded a while
ago: the app won't silently run a corrupted or swapped file.

---

### Routing

Right now Pocket Node operates as a standalone inference node. The phone
exposes a Tailscale-gated, OpenAI-compatible API (`/v1/chat/completions`)
that I verified works with a direct call, but it is not yet registered as a
named model in the LiteLLM proxy that sits in front of the Mac Studio and
the other homelab nodes.

That's the next infrastructure step: add a `pocket-node-fold6` model alias
to the LiteLLM config so the orchestration layer can route requests to the
phone when the task is small enough and the phone is available. Until then,
requests reach it directly by name rather than through the shared proxy.

---

### Telemetry

Sovereign Brain's P20 Command Center runs a daily health digest. Pocket Node
reports its status to P20: model loaded, backend active, last inference
timestamp. The digest is delivered to Slack each morning. If the phone drops
off the network or the app crashes, the digest shows it as degraded.

This is lightweight observability — not metrics collection. It answers
"is the node up and did it run inference recently" rather than collecting
token counts or prompt content.

---

### Lessons Learned

**Stop during prefill is harder than stop during decode.** llama.cpp's
generation callback fires per token. If you tap Stop before the first token
comes out — during the prefill phase where the prompt is being evaluated —
the callback hasn't fired yet. The fix was to set the native abort flag
before cancelling the coroutine, and to handle the case where the coroutine
gets a CancellationException during the JNI call rather than after it.

**Model verification before inference is worth the overhead.** SHA-256 on a
1.1B Q4 model takes a few seconds on first load. Worth it. The alternative
is running a model you can't trust.

**MutableState over StateFlow for Compose.** The app uses Kotlin
`MutableState` for all ViewModel-exposed state rather than `StateFlow`.
Simpler to read in composable functions, no `collectAsStateWithLifecycle`
boilerplate. Works fine for a single-screen app with a single ViewModel scope.

---

### Limits

- SmolLM3 1.1B is not a frontier model. Don't use it for tasks that need
  strong reasoning, long context, or reliable instruction following.
- Battery and thermal: short prompts are fine; sustained generation heats
  the device and affects throughput.
- Not tested on other Android hardware. Vulkan availability varies by device
  and driver version.
- No multi-user support. Single-conversation, single-model at a time.
- The app is not in the Play Store. This is a homelab tool.

---

### Next Steps

1. Register Pocket Node as a named LiteLLM model alias so Sovereign Brain
   can route to it automatically. The OpenAI-compatible API already exists;
   this is a config change in `models.yaml`, not new code.
2. Test on additional Android hardware to understand Vulkan backend variance.
3. Consider a release build and Play Store listing once the routing layer is
   stable and tested.

---

## 6. Launch Risks / Do-Not-Claim List

Read before writing any public copy.

| Risk | Rule |
|------|------|
| Model quality | Do NOT claim frontier-model performance. SmolLM3 Q4_0 is a small, quantized model. State this clearly. |
| Comparison to desktop GPU | Do NOT claim it replaces a Mac Studio, a desktop RTX card, or any serious local inference rig. It is a complement, not a replacement. |
| Universal Android support | Do NOT claim it works on all Android phones. Vulkan driver quality varies significantly. Only claim what was tested: Z Fold6. |
| Battery / thermal | Do NOT claim the phone handles sustained inference without thermal impact. It heats up. Say so. |
| Private IPs | Do NOT include Tailscale IP addresses, LAN IPs, or hostnames that reveal network topology in any screenshot. |
| Device names | Do NOT include device names that could be used to identify the network or owner, unless intentionally public. |
| API keys / tokens | Do NOT include any Slack tokens, P20 secrets, LiteLLM API keys, or Tailscale auth keys in any screenshot or log excerpt. |
| Homelab hostnames | Redact or blur internal hostnames (e.g., `neo`, `watchdawg`, or any machine name) unless they are intentionally public. |
| App name consistency | Use "Pocket Node" consistently. Not "PocketNode", not "pocket-node". |
| Model name consistency | Use `PocketNode_SmolLM3_Q4_0_Fresh` in technical contexts; "SmolLM3 Q4_0" in plain language copy. |

---

## 7. Final Pre-Post Checklist

Complete every item before submitting to Reddit or publishing a blog post.

### Content
- [ ] All claims in post match items checked in Section 2 (Technical Proof)
- [ ] Limits section present and honest
- [ ] No claim that this works on hardware other than Z Fold6
- [ ] No comparison to frontier models
- [ ] "Pocket Node" spelled consistently throughout
- [ ] Model name consistent: SmolLM3 Q4_0 / `PocketNode_SmolLM3_Q4_0_Fresh`

### Screenshots
- [ ] Every screenshot reviewed for private IPs — none present
- [ ] Every screenshot reviewed for API keys / tokens — none present
- [ ] Internal hostnames redacted or intentionally acceptable
- [ ] Tailscale addresses not visible
- [ ] No personal account names, emails, or identifiers unless acceptable
- [ ] File paths in Diagnostics screen cropped or blurred if they reveal
      personal directory structure

### Reddit-specific
- [ ] No links in the main post body (per r/LocalLLaMA norms)
- [ ] Title does not start with "I" (common removal reason)
- [ ] Post is self-contained — reader doesn't need to click anything to
      understand the project
- [ ] Flair selected (Project? Show and Tell?)

### Blog-specific
- [ ] No code blocks with placeholder text left unfilled
- [ ] All section headers present
- [ ] "Next Steps" is current and accurate
- [ ] Screenshots are embedded and captioned

### Final
- [ ] Someone else reads it before posting (fresh eyes for overclaims)
- [ ] Draft saved locally before submitting anywhere
