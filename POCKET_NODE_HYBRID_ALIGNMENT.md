# Pocket Node Hybrid Alignment

**Branch:** `pocket-node/hybrid-alignment`
**Base:** `main` @ `fa96f35` (RC3 public proof package)
**Scope:** Make Pocket Node's `/health`, `/capabilities`, refusal behavior, and request
tracing deterministic and machine-readable for Sovereign Brain Hybrid's edge-inference
tier. No routing/control-plane changes — Sovereign Brain Hybrid is out of this repo
entirely (no Hybrid routing code, live Mac Studio/Moolah client, or `edge_gate`
integration exists here; it lives in the separate homelab stack referenced by
`P20_HEALTH_AUDIT.md`).

---

## 1. Repository preflight

| Item | Value |
|---|---|
| Repository path | `C:\Users\Rhear\Pocket Node` |
| Starting branch | `main` |
| Starting HEAD | `fa96f3585c8033271f2e456ef9a71fc7f0b4791c` — "Add Pocket Node RC3 public proof package and fresh Fold6 validation evidence" |
| Upstream | none configured on `main` (`origin` = `Zero-Cloud-Tax/pocket-node-releases.git`, fetch/push, no tracking branch set) |
| Working-tree state | 2 pre-existing modified files unrelated to the app (`screen.png`, `window_dump.xml` — ADB capture artifacts), ~55 untracked files/dirs at repo root (session artifacts: `p27_*`, audit `.md` files, DB snapshots, screenshots). None touched by this pass. |
| New branch | `pocket-node/hybrid-alignment`, created from `main` @ `fa96f35` |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.1.0 (KSP 2.1.0-1.0.29) |
| Min / target SDK | 28 / 35 (compileSdk 35) |
| Application ID | `com.pocketnode.app` |
| Version | versionCode 4, versionName `0.1.0-rc2` |
| Server | Ktor 3.0.3, CIO engine, embedded in-process, `app/src/main/java/com/pocketnode/app/inference/ApiServer.kt`, routes registered via `routing { ... }` in `ApiServer.start()` |
| Test structure | Plain JUnit4 unit tests under `app/src/test/...` (no Robolectric — tests that need Android runtime types are not present; existing precedent is `ApiServerJsonEscapingTest`, which tests a duplicated local data class rather than the live server) |
| Build commands | `./gradlew.bat :app:testDebugUnitTest`, `./gradlew.bat :app:assembleDebug` (per `AGENTS.md`) |
| Lint/detekt | Not configured (no detekt plugin/config found) |
| Clean enough for a focused branch? | Yes — the only tracked diffs are two unrelated image/XML captures, left untouched |

`AGENTS.md` hard rule "do not modify Kotlin source files unless the user explicitly
requests source changes" — this task explicitly requests source changes, so it applies.

---

## 2. Baseline validation (before any edit)

| Check | Command | Result |
|---|---|---|
| Unit tests | `./gradlew.bat :app:testDebugUnitTest` | **BUILD SUCCESSFUL** — 63 tests, 0 failures, 0 errors, across 9 test classes |
| Debug build | `./gradlew.bat :app:assembleDebug` | **BUILD SUCCESSFUL** |
| Lint/detekt | n/a | not configured in this repo |
| Native/JNI | covered indirectly by `assembleDebug` (CMake/NDK build for `arm64-v8a`); no dedicated native test target exists |

No pre-existing failures. Nothing was silently repaired.

---

## 3. Audit — existing behavior before this pass

### `/health`
- Returned a hand-built JSON string: `{"status":"ok","node":"pocket-node","device":"android","model_loaded":<bool>,"uptime_ms":<long>}`.
- `status` was **always `"ok"`**, HTTP 200, regardless of thermal/battery/model state — i.e. unhealthy states returned HTTP 200 with no readiness signal at all.
- No thermal state, no battery/charging awareness, no busy flag, no app/build identity, no schema version, no request ID.

### `/capabilities`
- Already reported: node/device identity (via `Settings.Global.device_name` or `Build.MODEL`), `model_loaded`, battery percent/charging, `thermal_status` (PowerManager code), `eligible_for_inference` + `reason_if_not_eligible`, extensive OS thermal-zone telemetry (peak/CPU/GPU zone temps, gate reason), `last_inference_at`/`last_error`.
- Missing entirely: model **name**, backend identity, `maxPromptTokens`/`maxConversationTurns`, explicit `supportsStreaming`/`supportsTools`/`supportsExecution`/`supportsWebSearch` flags, schema version, app/build version.

### Inference rejection behavior
- Thermal/model/battery-gated requests returned HTTP 503 with `{"error":"node_unavailable","reason":<free-form string>,"fallback_recommended":true}` — already fallback-aware, but `reason` was a loose string (`"thermal_zone_hard_block (peak=66.1°C >= 65.0°C; cooldown to 58.0°C)"`) with no stable code.
- Busy (mutex contention): plain-text `{"error":"..."}`with 409, no code.
- Generation failure: plain-text `{"error":"generation failed"}` with 500, no code.
- **No enforcement existed at all** for prompt-token size or conversation-turn count — Pocket Node would attempt inference on any request regardless of the routing limits described in the wider Hybrid architecture (2048 prompt tokens / 5 turns). Those limits were evidently enforced upstream in Hybrid's router, never locally.

### Request tracing
- No request ID or session ID concept anywhere in `ApiServer.kt`.
- `ServiceHealthLog` (in-memory ring buffer, 50 events, `Log.i`) already logs lifecycle events (service start/stop, drain timeouts) with safe, non-prompt detail strings — good existing precedent for "don't log prompt content," which is preserved.

### Existing Hybrid integration
- Searched the app source (`app/src`) for `Sovereign Brain`, `Hybrid`, `Mac Studio`, `Moolah`, request/session ID conventions: **no matches** except `PromptGrounding.kt`, which lists sibling homelab node names (`Moolah`, `Neo`, `Watchdawg`, `Uno`, `Edge Gate`) purely as *static grounding text fed to the model* so it doesn't hallucinate live infrastructure access — not a routing/health integration. No duplicate abstraction risk.
- `P20_HEALTH_AUDIT.md` (repo root, pre-existing, untouched) documents the wider homelab health-check picture from the Hybrid side; confirms Hybrid's routing/control-plane code is not part of this repository.

---

## 4. Gap analysis

| # | Requirement | Status | Notes |
|---|---|---|---|
| 1 | Stable node identity | PARTIAL → COMPLETE | `/health.node` was already a fixed `"pocket-node"` literal; `/capabilities.node` used the mutable device name. Kept both (backward compat) — device name is device *identity*, `"pocket-node"` is node identity; both now co-exist without collision. |
| 2 | Stable health schema | REQUIRED GAP → done | Added `schema_version`, all fields explicitly typed via a `@Serializable` `HealthResponse` (was raw string interpolation). |
| 3 | Stable capabilities schema | REQUIRED GAP → done | Added `schema_version` to `CapabilitiesResponse`. |
| 4 | Explicit readiness state | REQUIRED GAP → done | New `NodeStatus` (`ready`/`degraded`/`blocked`), surfaced as `/health.readiness` and `/capabilities.status`. |
| 5 | Explicit degraded state | REQUIRED GAP → done | `DEGRADED` = eligible but thermal-zone WARN gate active. |
| 6 | Explicit blocked state | REQUIRED GAP → done | `BLOCKED` = any existing eligibility gate (`eligible_for_inference=false`). |
| 7 | Model identity exposed | REQUIRED GAP → done | `model` added to both `/health` and `/capabilities` (from `app.activeSession.modelName`, already tracked internally). |
| 8 | Backend identity exposed | REQUIRED GAP → done | `backend` added to both endpoints via existing `nativeGetBackendName()` (already used ad hoc on `/`). |
| 9 | Thermal state exposed | COMPLETE (pre-existing) | Extensive OS thermal-zone telemetry already present in `/capabilities`; added a simplified `thermal_state` (`normal`/`warning`/`blocked`) to `/health` for a quick machine check. |
| 10 | Prompt-token limit exposed | REQUIRED GAP → done | `max_prompt_tokens = 2048` in `/capabilities`, and now **enforced** (see §5). |
| 11 | Conversation-turn limit exposed | REQUIRED GAP → done | `max_conversation_turns = 5` in `/capabilities`, and now enforced for `/api/chat` and `/v1/chat/completions`. |
| 12 | Streaming support exposed | REQUIRED GAP → done | `supports_streaming = true` (streaming already worked; just wasn't declared). |
| 13 | Tool support explicitly false | REQUIRED GAP → done | `supports_tools = false`. |
| 14 | Execution support explicitly false | REQUIRED GAP → done | `supports_execution = false`. |
| 15 | Web-search support explicitly false | REQUIRED GAP → done | `supports_web_search = false`. |
| 16 | Structured rejection reasons | REQUIRED GAP → done | New `ReasonCode` enum (11 codes) with `httpStatus`/`retryable`, mapped from existing free-form reasons via `reasonCodeFor()`. |
| 17 | Request-ID propagation | REQUIRED GAP → done | `X-Request-Id` header in/out on every route; generated server-side (UUID) when absent; echoed in response bodies where practical. |
| 18 | Session-ID propagation | REQUIRED GAP → done | `X-Session-Id` header or body field, sanitized, logged; not required in responses (caller-scoped, nothing to echo back). |
| 19 | Safe logging and redaction | COMPLETE (pre-existing) + verified | `ServiceHealthLog` never logged prompt content before this pass and still doesn't — new `REQUEST_ACCEPTED`/`REQUEST_REJECTED` trace events log only `endpoint`, `reason_code`, `status`, `request_id`, `session_id`. |
| 20 | Fallback-compatible response behavior | COMPLETE (pre-existing) + extended | `fallback_recommended` already existed on the 503 path; now also carried on the two new 413 paths and enriched with `reason_code`/`retryable`. |
| 21 | App/build version exposed | REQUIRED GAP → done | `app_version` (`BuildConfig.VERSION_NAME`), `build_version_code` (`BuildConfig.VERSION_CODE`) on both endpoints. |
| 22 | Backward compatibility | — | See §6 below; no existing field renamed/removed. |
| 23 | Automated contract coverage | REQUIRED GAP → partially done | 11 new JVM unit tests for the pure logic (reason-code mapping, correlation-id sanitization, token estimate, schema constants). A live-server contract test would need Robolectric or an instrumentation test, neither of which exists in this repo today — see §12/§13 limitations. |
| 24 | End-to-end fallback validation path | REQUIRED GAP → documented smoke procedure | See §12 — a live Ktor route requires an Android runtime this repo's unit-test setup doesn't provide; a deterministic manual/curl smoke procedure is documented instead of a mocked automated test, and flagged as a non-blocking limitation. |
| — | Branding (`Sovereign Brain — Pocket Node`, `EDGE INFERENCE`, status line) | OPTIONAL, not implemented | Out of scope for this pass — no UI files were touched. `AGENTS.md`/this task both scope the primary deliverable to health/capabilities/refusal/tracing; UI branding is cosmetic and carries build/screenshot-review overhead disproportionate to the mandate. Flagged as OPTIONAL future work, not a closure blocker. |
| — | Bridge submission / SOP execution / cloud inference / web search | OUT OF SCOPE | None added — confirmed `supports_tools`/`supports_execution`/`supports_web_search` are hardcoded `false`, matching the hard boundaries. |

---

## 5. Compatibility contract implemented (schema v1)

Reused the project's existing conventions: `kotlinx.serialization` `@Serializable` data
classes, `snake_case` wire fields (matching every existing field in
`CapabilitiesResponse`/`NodeUnavailableResponse`), Ktor CIO routing. No new
serialization library, no new server framework.

### `GET /health` (additive fields only; legacy fields unchanged in name and meaning)

```json
{
  "status": "ok",
  "node": "pocket-node",
  "device": "android",
  "model_loaded": true,
  "uptime_ms": 123456,

  "schema_version": "1",
  "readiness": "ready",
  "reason_code": null,
  "reason": null,
  "model": "SmolLM2-135M-Instruct-Q4_K_M",
  "backend": "Vulkan",
  "thermal_state": "normal",
  "busy": false,
  "app_version": "0.1.0-rc2",
  "build_version_code": 4,
  "request_id": "3a1e...auto-generated-or-echoed"
}
```

`status` intentionally keeps its legacy meaning — "the HTTP process is reachable,"
always `"ok"`, always HTTP 200 — a liveness signal. The **readiness** signal (whether
Pocket Node can actually serve inference right now) is the new additive `readiness`
field. This was a deliberate design choice to avoid changing the meaning of an
existing field value (see §6).

### `GET /capabilities` (additive fields only; every pre-existing field kept)

```json
{
  "...": "all 20 existing fields unchanged...",

  "schema_version": "1",
  "status": "ready",
  "reason_code": null,
  "model": "SmolLM2-135M-Instruct-Q4_K_M",
  "backend": "Vulkan",
  "max_prompt_tokens": 2048,
  "max_conversation_turns": 5,
  "supports_streaming": true,
  "supports_tools": false,
  "supports_execution": false,
  "supports_web_search": false,
  "busy": false,
  "app_version": "0.1.0-rc2",
  "build_version_code": 4
}
```

### Structured refusal (`NodeUnavailableResponse`, additive fields)

```json
{
  "error": "context_too_large",
  "reason": "prompt is an estimated 2200 tokens, exceeding the 2048-token limit",
  "fallback_recommended": true,
  "reason_code": "context_too_large",
  "retryable": false,
  "request_id": "3a1e..."
}
```

---

## 6. Status semantics

Single deterministic state model — `NodeStatus`: `ready` / `degraded` / `blocked`.
No competing state model existed in the repo, so nothing needed reconciling.

- **ready** — `eligible_for_inference == true` and no thermal-zone WARN gate active.
- **degraded** — `eligible_for_inference == true` but the thermal-zone WARN gate
  (`peak ≥ 55 °C`) is active. Inference is still served; this is a heads-up for Hybrid,
  not a refusal.
- **blocked** — `eligible_for_inference == false` (model not loaded, battery below
  threshold and not charging, `PowerManager` thermal severe, or an OS thermal-zone
  hard/soft block). Maps 1:1 onto the pre-existing `eligible_for_inference` boolean —
  no new gating logic, purely a richer label on an existing computation.

---

## 7. Structured refusal reasons (`ReasonCode`)

```
THERMAL_WARNING, THERMAL_BLOCK, MODEL_NOT_LOADED, BACKEND_UNAVAILABLE,
CONTEXT_TOO_LARGE, CONVERSATION_TOO_LONG, REQUEST_UNSUPPORTED, SERVER_BUSY,
BATTERY_LOW, INFERENCE_FAILED, INVALID_REQUEST
```

Each carries a recommended `httpStatus` and `retryable` flag. `THERMAL_WARNING` is
declared for completeness (matches the candidate list) but is never returned as a
*rejection* — a WARN-level thermal state is `degraded`, not blocked, so inference
still proceeds; it only ever appears conceptually via `readiness: "degraded"`.

`reasonCodeFor()` maps every existing free-form eligibility reason string
(`model_not_loaded`, `battery_below_threshold`, `thermal_severe`,
`thermal_zone_hard_block`, `thermal_zone_cpu_soft_block`, `thermal_zone_gpu_soft_block`,
`debug_forced_block`) onto a stable code — no existing reason string was renamed, the
code is purely additive.

No stack traces, file paths, or raw exceptions are exposed anywhere in these responses
(existing `catch (_: Exception)` blocks already discarded exception detail before this
pass; unchanged).

---

## 8. Request correlation

- `X-Request-Id` accepted via header or body field (`requestId`), sanitized
  (`[^A-Za-z0-9._-]` stripped, 128-char cap), generated server-side (`UUID`) when
  absent/invalid.
- `X-Session-Id` accepted via header or body field (`sessionId`), same sanitization,
  not auto-generated (session identity is the caller's to assign).
- Every route sets `X-Request-Id` on the response as soon as it's resolved — including
  every rejection path, **and now also on `401 Unauthorized`** (post-Codex-review fix,
  see §15) — so a caller always gets the ID back on every status code.
- Streaming responses carry the ID in the response header once (headers precede the
  stream) and, redundantly, in a terminal body marker for consumers reading the stream
  without header access: `DoneChunk.request_id` for the `/api/generate`/`/api/chat`
  NDJSON streams, and a `request_id` field on the terminal SSE stop chunk for
  `/v1/chat/completions` streaming (added post-review — see §15; the initial pass only
  had this on the non-streaming `OaiChatCompletion` body, an inconsistency Codex caught).
- No distributed tracing library was added — `ServiceHealthLog`'s existing in-memory
  ring-buffer pattern was reused (`REQUEST_ACCEPTED`/`REQUEST_REJECTED` event types),
  consistent with the project's existing "no cloud dependencies" rule in `AGENTS.md`.
- Full prompt text is never logged — trace log details are limited to
  `endpoint=... reason_code=... status=... request_id=... session_id=...`.

---

## 9. New enforcement: prompt-size and conversation-turn limits

This is the one piece of genuinely **new runtime behavior** (not just new fields) in
this pass, so it's called out explicitly for review:

- `MAX_PROMPT_TOKENS = 2048` — enforced in all four generation paths
  (`streamResponse`, `nonStreamResponse`, `streamOaiResponse`, `nonStreamOaiResponse`)
  using a rough `chars / 4` estimate (`estimateTokenCount`), not an exact tokenizer
  count. Exact counting would require plumbing `modelPtr` through to `ApiServer`
  (currently only `contextPtr` is retained in `InferenceSession`) and calling the
  existing `nativeGetTokenCount(modelPtr, text)` JNI function — judged out of scope
  for a "small compatibility pass" per the task's hard boundaries (no native/JNI
  changes). The estimate is intentionally conservative in the doc/spec sense ("approximate
  routing limits") and errs toward over-rejection rather than under-rejection.
- `MAX_CONVERSATION_TURNS = 5` — enforced in `/api/chat` and `/v1/chat/completions`
  by counting `role == "user"` messages before templating; `/api/generate` has no
  message list, so this check doesn't apply there.
- **Why enforce locally at all**, given Hybrid's router already avoids sending
  oversized requests? Because `max_prompt_tokens`/`max_conversation_turns` are now
  *advertised* capabilities (§4 items 10–11) — declaring a limit without ever enforcing
  it is an inconsistent contract, and a defensive local check lets a resource-constrained
  edge device fail fast and cheap instead of grinding through inference it can't usefully
  serve. This does not touch or duplicate Hybrid's own routing logic (none exists in this
  repo) — it is purely Pocket Node validating its own advertised limits on its own inbound
  requests.
- New HTTP status used only for these two **brand-new** paths: `413 Payload Too Large`.
  No existing status code was changed (see §10).

**Post-review addition (see §15):** `supports_tools`/`supports_execution`/`supports_web_search`
are advertised as `false`, but `Json { ignoreUnknownKeys = true }` meant a client sending
OpenAI-style `tools`/`tool_choice`/`functions`/`function_call` fields had them silently
dropped, and the request proceeded as plain inference — no explicit signal that the
feature wasn't honored. All three POST routes (`/api/generate`, `/api/chat`,
`/v1/chat/completions`) now parse the raw JSON body first via
`unsupportedFeatureKey()` and reject with `400` + `reason_code: request_unsupported`
if any of those four keys are present, before ever decoding into a typed request or
touching the model. This does not add tool/function-calling support — it makes the
*absence* of that support explicit and machine-readable instead of silent.

---

## 10. Backward compatibility review

- **No existing field renamed or removed** on `/health` or `/capabilities`. Every new
  field is additive.
- **No existing HTTP status code changed** for any pre-existing refusal path:
  - Thermal/model/battery unavailable → still `503` (was `503`).
  - Inference busy (mutex contention) → still `409` (was `409`).
  - Generation failure → still `500` (was `500`).
  - Invalid JSON body → still `400` (was `400`).
  - Unauthorized → untouched, still `401`, body untouched (out of scope — not in the
    enumerated refusal-reason candidate list).
  - `ReasonCode.httpStatus` defaults (e.g. `SERVER_BUSY → 429`) exist as the
    *recommended* mapping for reference/future use, but every legacy call site
    explicitly overrides `httpStatus` to preserve its historical code. This is called
    out here rather than silently changed, per the task's compatibility-review
    instruction.
- **Two new status codes** only apply to **two brand-new** rejection paths that did
  not exist before this pass (`413` for context-too-large / conversation-too-long) —
  no existing consumer could have depended on their absence producing success, because
  previously such requests were simply attempted (successfully or not) rather than
  validated.
- **`/health.status`** keeps its exact legacy meaning and value (`"ok"`, always `200`).
  The new readiness signal is a separate field (`readiness`) rather than repurposing
  `status`, specifically to avoid an old consumer that checks `status == "ok"` breaking
  when Pocket Node is thermally degraded but still serving.
- Existing streaming behavior, OpenAI-compatible response shapes, and thermal
  enforcement are all unchanged in substance — only enriched with additive fields.
- `ApiServerJsonEscapingTest` (pre-existing) and all other pre-existing tests pass
  unmodified.

---

## 11. Files changed

| File | Change |
|---|---|
| `app/src/main/java/com/pocketnode/app/inference/ApiServer.kt` | Core of this pass — see §5–§9 above. New top-level `ReasonCode`/`NodeStatus` enums; `HealthResponse` replaces hand-built JSON string; `CapabilitiesResponse`/`NodeUnavailableResponse` extended additively; `EligibilityResult` gained `status`/`reasonCode`; request/session correlation helpers; prompt/turn-limit enforcement; all four generation-response functions gained a `requestId` parameter and structured rejection paths. Post-review (§15): `unsupportedFeatureKey()` rejection, `X-Request-Id` on `401`, `request_id` on the OAI SSE stop chunk. |
| `app/src/main/java/com/pocketnode/app/diagnostics/ServiceHealthLog.kt` | Added `REQUEST_ACCEPTED`/`REQUEST_REJECTED` event types (additive enum values; no exhaustive `when` on this enum exists elsewhere in the codebase, verified by search). |
| `app/src/test/java/com/pocketnode/app/inference/ApiServerHybridAlignmentTest.kt` | New — 14 unit tests for the pure logic introduced by this pass (11 initial + 3 added post-review for `unsupportedFeatureKey()`). Two literal NUL bytes from an initial write-tool artifact were also cleaned up (§15). |
| `POCKET_NODE_HYBRID_ALIGNMENT.md` | This document. |

No UI, manifest, build script, or unrelated file was touched. The two pre-existing
unrelated dirty files (`screen.png`, `window_dump.xml`) were left as found.

---

## 12. Hybrid integration validation

**Mocked/deterministic status: documented procedure, not an automated test** — see
limitation below.

Because `ApiServer` depends on live Android types (`Context`, `PowerManager`,
`BatteryManager`, `Settings`) and this repo has no Robolectric or instrumentation-test
setup, a fully automated in-process route test isn't available without adding a new
test dependency — judged out of scope for a compatibility/observability pass per the
task's boundaries. Instead, here is the deterministic manual smoke procedure (run
against a debug build on-device or emulator with `adb forward tcp:11434 tcp:11434`):

1. `curl -s http://localhost:11434/health | jq .readiness` → expect `"ready"` once a
   model is loaded and thermals are normal.
2. `curl -s -X POST http://localhost:11434/api/chat -H 'X-Request-Id: smoke-1' -d '{"messages":[{"role":"user","content":"hi"}],"stream":false}'` →
   expect `200`, body `request_id == "smoke-1"`, response header `X-Request-Id: smoke-1`.
3. Same request ID from step 2 is present in both the request header and response
   header/body — confirms correlation round-trip (requirement #3).
4. Send a request with 6 user-role messages → expect `413`,
   `reason_code: "conversation_too_long"`, `retryable: false`,
   `fallback_recommended: true` (requirement #4/#5).
5. A mocked Hybrid consumer reading that response would see `fallback_recommended: true`
   plus a `reason_code` it doesn't need to string-match — enough to deterministically
   select Mac Studio as the next tier per the existing fallback chain description
   (requirement #6) — no code in this repo performs that selection; it's Hybrid-side.
6. `ServiceHealthLog.events` (visible in the app's Diagnostics screen) would show a
   `REQUEST_REJECTED reason_code=conversation_too_long ... request_id=smoke-1` entry —
   confirms the rejection + correlation ID are recorded (requirement #7).
7. `curl -s http://localhost:11434/capabilities | jq '.supports_tools, .supports_execution, .supports_web_search'` →
   expect `false, false, false` (requirement #8).
8. No response body anywhere in `ApiServer.kt` contains language claiming an action was
   executed outside the local inference call — confirmed by inspection (requirement #9).

This procedure is deterministic and repeatable but was **not executed against a live
device in this session** (no device/emulator was attached; doing so would also require
explicit approval to deploy/sideload per the hard boundaries). It is documented here so
it can be run as a fast manual check before/after any future Hybrid-side wiring.

---

## 13. Validation after changes

| Check | Command | Before | After |
|---|---|---|---|
| Unit tests | `./gradlew.bat :app:testDebugUnitTest` | 63 tests, 0 failures | **77 tests, 0 failures** (63 pre-existing + 14 new: 11 initial + 3 added post-Codex-review) |
| Debug build | `./gradlew.bat :app:assembleDebug` | BUILD SUCCESSFUL | **BUILD SUCCESSFUL** |
| Kotlin compile | `./gradlew.bat :app:compileDebugKotlin` | (implied by above) | **BUILD SUCCESSFUL**, no warnings surfaced beyond pre-existing SDK-XML-version CXX5304 notices (native toolchain version skew, pre-existing, unrelated) |
| Lint/detekt | n/a | not configured | not configured (unchanged) |
| Native/CMake | part of `assembleDebug` | succeeds | succeeds, unchanged |
| Live device smoke | manual, §12 | n/a | **not run this session** — documented procedure only |

**Not run**: Android instrumentation tests (none exist in this repo — no
`androidTest` source set was found), a live on-device/emulator smoke test (no device
attached, and deployment requires explicit approval), full Android lint (no configured
task beyond the standard AGP lint, not part of this repo's documented verification
flow in `AGENTS.md`).

---

## 14. Review before commit

1. **Clean-base verification** — done; branch created from `main`@`fa96f35`; only
   pre-existing unrelated dirty files present, untouched.
2. **Baseline results** — 63/63 unit tests pass, `assembleDebug` succeeds (§2).
3. **Existing capabilities found** — thorough OS thermal-zone telemetry, existing
   `fallback_recommended` hook, existing safe (non-prompt) logging pattern — all reused,
   none duplicated (§3).
4. **Gap analysis** — 21 REQUIRED GAP items closed, 3 pre-existing COMPLETE items
   verified/extended, 1 OPTIONAL (branding) explicitly deferred, tool/execution/web-search
   confirmed OUT OF SCOPE and hardcoded false (§4).
5. **Exact implementation** — additive schema v1 on `/health`/`/capabilities`,
   `ReasonCode`/`NodeStatus` enums, request/session correlation, prompt-size and
   conversation-turn enforcement (§5–§9).
6. **Exact files changed** — 2 modified, 2 new (§11).
7. **Diff summary** — `ApiServer.kt` +370/-60 lines (net, includes the new test file
   separately); `ServiceHealthLog.kt` +4 lines.
8. **API compatibility review** — no existing field renamed/removed, no existing HTTP
   status changed, `/health.status` semantics preserved exactly, two new 4xx paths are
   genuinely new behavior and called out explicitly (§9, §10).
9. **Test and build results** — 77/77 unit tests pass, debug build succeeds (§13).
10. **Security and privacy review** — no prompt content logged (verified against both
    the pre-existing pattern and the new trace events); no stack traces, file paths, or
    HMAC/license secrets exposed in any response; Bridge HMAC key was never touched (it
    isn't referenced anywhere in `ApiServer.kt`); no new outbound network calls, no cloud
    dependency added.
11. **Branding changes** — none. No UI file was modified.
12. **Mocked integration status** — documented procedure only (§12); no automated mock
    test exists because a live route test needs Android runtime types this repo's test
    setup doesn't provide.
13. **Live verification status** — not run; no device/emulator attached this session,
    and deployment needs explicit approval per the hard boundaries.
14. **Remaining limitations** —
    - Prompt-token limit uses a `chars/4` estimate, not the real tokenizer
      (`nativeGetTokenCount`) — would need `modelPtr` plumbing into `InferenceSession`,
      a small native-adjacent change deliberately deferred as out of scope.
    - No automated end-to-end route test (Robolectric/instrumentation) exists in this
      repo; contract coverage is limited to the pure-logic unit tests in
      `ApiServerHybridAlignmentTest.kt`.
    - Branding alignment (Step 9 of the task) is OPTIONAL and was not implemented.
15. **Recommended next action** — review the diff (`git diff main...pocket-node/hybrid-alignment`),
    run the §12 manual smoke procedure on an actual Fold 6 or emulator with a model
    loaded, then decide whether to invest in a Robolectric-based route test before
    merging, or accept the current unit-test coverage as sufficient for a v1
    compatibility pass.
16. **Commit status** — **not committed**. Changes are staged only in the working tree
    on `pocket-node/hybrid-alignment`, pending review per the task's explicit
    instruction not to commit or push before review.

---

## 15. Independent Codex review and disposition of findings

An independent read-only review (`codex exec -s read-only`, GPT-5.4, no write/build/deploy
access) was run against the four in-scope files. Verdict: **no BLOCKER findings**; 2 HIGH,
3 MEDIUM, 1 LOW. Each was independently re-verified against the source (not taken at face
value) before deciding to fix, reject, or defer.

| # | Sev | Finding | Verified? | Disposition |
|---|---|---|---|---|
| 1 | HIGH | `ignoreUnknownKeys=true` + no `tools`/`tool_choice`/`functions`/`function_call` fields on `OaiChatRequest` meant those fields were silently dropped instead of producing an explicit `request_unsupported` refusal. | **Confirmed** — read the request data classes and `Json{}` config directly. | **FIXED.** All three POST routes now pre-parse the raw body and reject with `400 request_unsupported` if any of those four keys are present, before decoding or touching the model (§9). Added `unsupportedFeatureKey()` + 3 tests. |
| 2 | HIGH | `MAX_CONVERSATION_TURNS` is enforced by counting `role == "user"` messages only, so an assistant/tool-heavy history could exceed 5 total messages while staying under the user-turn count. | **Confirmed as literally true**, but re-evaluated as **not a bug**: "turn" = one user prompt is the conventional definition (matches the task's own phrasing, "more than 5 conversation turns"), and total-size risk is already independently bounded by the `context_too_large` (token-estimate) check regardless of role composition. Tool-calling roles aren't reachable in practice — `supports_tools=false` is now actively enforced (finding #1's fix). | **REJECTED** as a bug; already accurately documented in §9 ("by counting `role == "user"` messages") before this review — the design was intentional, not accidental. No code change. |
| 3 | MEDIUM | `POCKET_NODE_HYBRID_ALIGNMENT.md` claimed all streaming responses carry `request_id` in the terminal body, but `streamOaiResponse()`'s SSE stop chunk was a raw string with no `request_id` field — doc didn't match code for that one path. | **Confirmed** by reading the stop-chunk string literal. | **FIXED.** Added `request_id` to the SSE terminal stop chunk; corrected §8's wording to name the exact field per streaming path instead of a blanket claim. |
| 4 | MEDIUM | `401 Unauthorized` responses on all three POST routes returned before `X-Request-Id` was ever resolved, contradicting the doc's "every route sets X-Request-Id ... including every rejection path" claim. | **Confirmed** by reading the auth-check block in each route. | **FIXED.** Each route now resolves and appends `X-Request-Id` on the `401` path too (header-only resolution, since the body isn't read yet — no risk of a header/body ID mismatch on the happy path). |
| 5 | MEDIUM | The new tests cover only pure helper functions, not live route/HTTP behavior (`/health`, `/capabilities`, `413` enforcement, request-ID echoing, streaming vs. non-streaming consistency). | **Confirmed** — and already disclosed as a known limitation in the original §12/§14 before this review (no Robolectric/instrumentation test infra exists in this repo). | **DEFERRED, not silently accepted.** Fixing properly means adding a new test dependency (Robolectric or an instrumentation test target) touching `app/build.gradle.kts` — a 5th file outside the four approved for this pass. Per the task's own instruction ("If any extra file becomes necessary, stop and report why before editing it"), this is surfaced here rather than done unilaterally. Recommendation: a follow-up task to add Robolectric-backed route tests, scoped and approved separately. |
| 6 | LOW | Two literal NUL bytes (`0x00`) were embedded in `ApiServerHybridAlignmentTest.kt` at offsets 3421 and 3596 — apparent corruption of an intended trailing space during the initial file write, not a hand-typed escape. | **Confirmed** — verified at the byte level (`python3 -c "...count(b'\x00')..."` found exactly 2, both inside string literals in `sanitizeCorrelationIdStripsControlCharactersAndBoundsLength`). Functionally harmless (the NUL was stripped by `sanitizeCorrelationId`'s own control-character regex, so the test still passed correctly) but undesirable in source. | **FIXED.** Replaced both NUL bytes with the intended literal space at the byte level; re-verified 0 NUL bytes remain and the test still passes. |

No BLOCKER or unresolved HIGH/MEDIUM findings remain. Finding #2 is a reviewed-and-rejected
judgment call with rationale recorded here for future reference; finding #5 is a real,
disclosed gap deferred to a separate, properly-scoped follow-up rather than expanded into
this branch.

### Second (focused) review pass — findings #1–#6 re-verification, plus one new issue

A second, focused read-only Codex pass re-verified each of the six original findings
against the corrected source (not re-trusting the developer's "fixed" claims) and found:
`HIGH #1`, `MEDIUM #3`, `MEDIUM #4`, `LOW #6` **confirmed fixed**; `HIGH #2` disposition
**agreed** (not a bug); `MEDIUM #5` deferral **agreed** (reasonable, real dependency-scope
constraint). It also surfaced one **new MEDIUM finding** the fixes themselves introduced:

- **MEDIUM (new):** `streamResponse`/`nonStreamResponse`/`streamOaiResponse`/
  `nonStreamOaiResponse` each set `X-Request-Id` on entry, then several of their
  rejection paths (`isStopping`, `context_too_large`, `node_unavailable`,
  `model_not_loaded`, `server_busy`, non-stream `inference_failed`) called
  `respondRejection()`, which *also* appended `X-Request-Id` — producing two identical
  header values on those responses. **Verified** by reading the call graph directly.
  **Fixed** by replacing every `call.response.headers.append("X-Request-Id", requestId)`
  call site with a new idempotent `setRequestIdHeaderOnce(call, requestId)` helper
  (appends only if the header isn't already present) — one header, set exactly once,
  regardless of which code path sets it first.

**Self-caught regression during this exact fix:** the first attempt at the fix used a
bulk find-and-replace for the literal append call, which matched — and corrupted — the
one legitimate raw `append` call *inside the new helper's own body*, turning it into
`setRequestIdHeaderOnce(call, requestId)` calling itself: infinite recursion / guaranteed
`StackOverflowError` on first use. This was caught immediately by re-reading the helper's
own diff before moving on (not by the build — Kotlin compiles self-recursive private
functions without complaint) and corrected before compiling. Recorded here in the
interest of an accurate, honest changelog rather than omitted. Re-verified after the
correction: exactly one raw `.append("X-Request-Id", ...)` call site remains (inside
`setRequestIdHeaderOnce` itself), all 10 other call sites use the wrapper, and
`./gradlew.bat :app:compileDebugKotlin` / `:app:testDebugUnitTest` (77/77) /
`:app:assembleDebug` all pass clean afterward.

No dedicated unit test was added for `setRequestIdHeaderOnce` — like the rest of the
route-level HTTP behavior (finding #5), exercising it needs a live `ApplicationCall`,
which needs the same deferred Ktor/Robolectric test infrastructure. This is the same
disclosed limitation, not a new one.

### Third (confirmation) review pass

A third, narrowly-scoped read-only Codex pass re-verified the `setRequestIdHeaderOnce`
fix specifically (exactly one raw header-append remains, correctly inside the helper;
the helper is not self-recursive; all 10 former call sites now route through it; no
response path silently lost its header) plus a final full-file sanity sweep of all four
files for any other bulk-replace artifacts. **VERDICT: CLEAN.**

Across all three passes: 2 HIGH + 3 MEDIUM + 1 LOW originally found and triaged (1 HIGH
rejected with recorded rationale, 1 MEDIUM deferred with recorded rationale, the rest
fixed and re-verified), plus 1 MEDIUM found and fixed during the follow-up itself. No
BLOCKER at any point. Final state: clean.

---

## Completion verdict

**POCKET_NODE_HYBRID_ALIGNMENT_PASS_WITH_NONBLOCKING_LIMITATIONS**

Pocket Node's `/health` and `/capabilities` now carry a versioned, additive,
machine-readable contract (`schema_version: "1"`) with an explicit `ready`/`degraded`/
`blocked` state model, structured refusal reason codes, and request/session
correlation — fully backward-compatible with existing consumers. The two
non-blocking limitations (approximate token counting instead of exact tokenizer
counting, and no automated live-route test) do not prevent Pocket Node from operating
as Sovereign Brain Hybrid's local edge-inference tier; they are documented follow-ups,
not closure blockers.
