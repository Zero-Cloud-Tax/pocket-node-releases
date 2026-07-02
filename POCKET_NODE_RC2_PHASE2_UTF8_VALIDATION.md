# Pocket Node RC2 Phase 2 UTF-8 Validation

Date/time: 2026-07-02, 15:05–15:16 local
Branch: main
HEAD SHA: a940aab (uncommitted changes on top, see below)

Dirty state before work: same as Phase 1 close —
`app/src/main/cpp/CMakeLists.txt` (pre-existing uncommitted march change, untouched),
`app/src/main/java/com/pocketnode/app/inference/ApiServer.kt` (Phase 1's stream=false fix),
`screen.png` / `window_dump.xml` (stale artifacts), plus the ~50 untracked P27
artifacts/scripts/docs at repo root. None deleted or moved.

Dirty state after work: same, plus one additional modified file:
`app/src/main/cpp/pocketnode_jni.cpp` (+~50 lines — see Files changed). No files deleted.

## Root cause
`Java_com_pocketnode_app_inference_LlamaInference_nativeGenerate` (pocketnode_jni.cpp) runs an
autoregressive decode loop and, for every sampled token, calls `llama_token_to_piece()` to get
that token's raw UTF-8 bytes, then immediately called `env->NewStringUTF(piece.c_str())` and
invoked the Kotlin `onToken(String)` callback with the result (`emit_token` lambda, formerly
around line 502-523).

The bug: `llama_token_to_piece()` operates on the model's BPE/byte-level vocabulary, where a
single Unicode character (e.g. Cyrillic, CJK, or an emoji) is frequently split across two or
more vocabulary tokens at the *byte* level, not the *character* level. When the sampler emits
such a token in isolation, `piece` can contain a truncated lead byte plus a dangling
continuation byte, or a lone continuation byte, which is not valid UTF-8 on its own — even
though the two adjacent tokens *together* would form a valid character. Android's JNI CheckJNI
layer validates every `NewStringUTF()` argument as Modified UTF-8; the invalid partial sequence
made it call `art::JavaVMExt::JniAbort`, which raises `SIGABRT` and kills the entire process
(observed as `JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8: illegal
continuation byte`). This affected `/api/generate`, `/api/chat`, and `/v1/chat/completions`
streaming identically, since all three call the same native `nativeGenerate` → `onToken`
pathway.

## Files changed
- `app/src/main/cpp/pocketnode_jni.cpp` — the only file changed in this phase.
  - Added `LOGW` macro (file previously only had `LOGI`/`LOGE`) for the new non-fatal warning
    paths.
  - Added a static helper `utf8_valid_prefix_len(const std::string&)` that returns the length
    of the longest prefix of a byte buffer consisting entirely of complete, valid UTF-8
    sequences, leaving any trailing incomplete/invalid bytes unclaimed.
  - Added a per-call `std::string pending_utf8` buffer inside `nativeGenerate`, alongside the
    existing `accumulated` (stop-string) buffer.
  - Rewrote the tail of the `emit_token` lambda: instead of calling `NewStringUTF` on the raw
    `piece` directly, it now appends `piece` to `pending_utf8`, extracts the longest valid
    UTF-8 prefix via the helper, and only calls `NewStringUTF`/`onToken` with that validated
    prefix. Any incomplete trailing bytes stay in `pending_utf8` for the next token.
  - Added a safety valve: if the buffer grows past 4 bytes (the max length of any valid UTF-8
    sequence) with zero valid prefix, the leading byte is treated as genuinely invalid (not
    just truncated) and dropped with a `LOGW`, so a malformed byte can never stall the buffer
    forever.
  - Added an end-of-generation flush, inserted at the single point where both the standard and
    speculative-decoding loops converge (right before the existing stats-reporting block, so it
    fires regardless of which branch produced the exit — EOG, stop-string match, max_tokens
    reached, or a decode failure `break`). If bytes remain in `pending_utf8` and they form
    valid UTF-8, they are emitted as-is; if they are incomplete/invalid (true truncation at the
    end of generation, e.g. `max_tokens` cut off mid-character), the buffer is replaced with the
    Unicode replacement character (U+FFFD) and a `LOGW` records how many bytes were discarded.
  - No changes to `GenerateRequest`/`ChatRequest`/`onToken` signatures, to the streaming
    (`x-ndjson`) or non-streaming JSON schema from Phase 1, or to any other native function.

## Implementation
UTF-8 boundary-safe buffering, entirely inside the native layer, per the requested flow:

```
native token bytes (piece)
  -> pending_utf8 += piece
  -> valid_len = utf8_valid_prefix_len(pending_utf8)
  -> if valid_len == 0 and pending_utf8.size() > 4: drop 1 invalid lead byte, recompute
  -> if valid_len > 0: NewStringUTF(pending_utf8[0:valid_len]) -> onToken(...); erase that prefix
  -> remaining incomplete suffix stays in pending_utf8 for the next token
  -> at generation end: flush remaining bytes as-is if valid, else emit U+FFFD and log
```

`pending_utf8` and `accumulated` are both plain stack-local `std::string` objects scoped to a
single `nativeGenerate` call, so they are naturally cleared at the start of each generation
(fresh local variable) and go out of scope at the end (implicit clear). Thread-safety: the
entire function body is already serialized by the pre-existing
`std::lock_guard<std::mutex> lock(g_inference_mutex)` at the top of `nativeGenerate`, so no
additional locking was needed for the new buffer — only one generation (and therefore one
`pending_utf8`) can be in flight at a time, matching the "safe for concurrent calls from both
the chat UI and the Edge API service" comment already in the file.

No change was made to the streaming response schema (`{"token": "..."}` NDJSON lines, `{"done":
true}` terminator) or the non-stream schema (`{"response": "...", "done": true}`, added in
Phase 1) — the fix only changes *when* and *what* bytes are handed to each `onToken` call, not
the shape of the callback or the HTTP response.

## Build result
assembleDebug: **PASS** — `BUILD SUCCESSFUL in 1m 1s`, native library rebuilt
(`configureCMakeDebug`/`buildCMakeDebug` re-ran, confirming the CMake/NDK toolchain picked up
the `pocketnode_jni.cpp` change).

## Install result
Device: Samsung SM-F956U (Galaxy Z Fold 6), serial RFCX60BRDWA
Install command: `adb -s RFCX60BRDWA install -r app/build/outputs/apk/debug/app-debug.apk`
Install result: `Success` (streamed install, upgrade over the Phase-1-fixed app already
installed)
Data preserved: yes — same on-device model file
(`PocketNode_SmolLM3_Q4_0_Fresh.gguf`) was reloaded without re-provisioning; no uninstall was
performed.

## API regression checks
`/health`: **PASS** — `{"status":"ok","node":"pocket-node","device":"android","model_loaded":true,...}`
`/capabilities`: **PASS** — all RC2 fields present; observed a live thermal hard-block →
cooldown → eligible transition during model-load warmup (pre-existing gating behavior, unrelated
to this fix, working correctly).
`/api/generate` `stream=false`: **PASS** — returned a single consolidated
`{"response": "...", "done": true}` object (Phase 1 fix still intact), no crash.
`/api/generate` `stream=true`: **PASS** — NDJSON per-token stream unchanged, no crash.

## Unicode stress checks
Accented Latin non-stream (`café naïve résumé jalapeño façade` prompt): **PASS, no crash.**
Model's own reply happened to stay in plain ASCII this round (a model-behavior choice, not a
bug), but the request completed cleanly end-to-end.

Accented Latin streaming (same prompt, `stream:true`): **PASS, no crash.** NDJSON tokens
streamed normally.

Emoji/Japanese non-stream (`three emoji and three Japanese words` prompt): **PASS, no crash.**
Notably the model's actual output included real multi-byte UTF-8 characters (macronned vowels,
e.g. "Shōchirai") that previously would have been exactly the kind of byte-split risk that
crashed the process — these were emitted correctly and the full 512-token response returned
intact as one JSON object.

Mixed Unicode streaming (Cyrillic + Japanese + emoji prompt, `stream:true`): **PASS, no crash.**
Model's reply stayed in English this round; request streamed and completed normally. No
replacement-character artifacts (`U+FFFD`) were observed in any of the four probes, meaning the
end-of-generation flush path was not exercised by these particular runs — the mid-stream
buffering path was exercised and worked correctly on the accented/macron content that did
appear.

One unrelated, pre-existing issue observed during testing: a `ggml_abort()` native crash
(distinct call stack, not `NewStringUTF`) occurred once during a model reload race — this
happened while a prior app instance was still finishing its own model load when a second
install/launch cycle overlapped it. This is a startup/model-load concurrency issue, not the
token-callback UTF-8 bug this phase targets, and is out of scope here; flagged as a follow-up.

## Crash verification
Process survived: **yes**, across all four Unicode stress probes plus the ASCII regression
checks — same pid (23363) held for the entire test sequence after the fixed build was
installed and launched.
Relevant logcat result: a full logcat sweep from the fixed build's launch timestamp forward
contains zero occurrences of `SIGABRT`, `abort`, `NewStringUTF`, or `JNI DETECTED ERROR` tied to
`com.pocketnode.app`/`PocketNode` (the only matching lines were unrelated third-party app
network-socket "connection abort" log noise, and window-transition "aborted=false" logs).
NewStringUTF crash reproduced after fix: **no.**

## Verdict
**PASS**

- `assembleDebug` passes.
- APK installs over the existing app; model/Room DB/app data preserved.
- App launches, Ktor server starts, model loads.
- `/health` and `/capabilities` pass with the documented RC2 fields.
- `/api/generate` `stream=false` and `stream=true` both still work exactly as verified in
  Phase 1, with no schema changes.
- All four Unicode stress prompts (accented Latin × 2, emoji/Japanese, mixed Unicode) completed
  without crashing the process, including a case where the model actually emitted multi-byte
  UTF-8 output.
- No `NewStringUTF`/JNI abort reproduced anywhere in the post-fix logs.

## Follow-ups
- The `ggml_abort()` crash observed during an overlapping install/model-load race (unrelated to
  this phase's UTF-8 fix) should be investigated separately — likely needs to guard against a
  second `nativeGenerate`/model-load being triggered while a previous instance's load is still
  in flight during app restart.
- These stress probes happened to not produce Cyrillic or emoji output from the model itself
  (model chose English/romanized text), so the end-of-generation `U+FFFD` replacement path and
  the "genuinely invalid lead byte" drop path were exercised only in code, not by an observed
  live truncation. Worth a follow-up pass with prompts/sampling settings more likely to force
  non-Latin output, purely to visually confirm the replacement-character path once, outside of
  Phase 2's scope of "did the process survive."
- The pre-existing uncommitted `app/src/main/cpp/CMakeLists.txt` change (Fold6-optimized march
  → generic armv8-a) from Phase 1 is still unresolved and uncommitted — needs an explicit
  decision before RC3.
- No thermal soak testing, route-away/fallback testing, or public packaging was performed —
  out of scope for Phase 2 by design.
