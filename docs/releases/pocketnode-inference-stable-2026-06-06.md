# Pocket Node Inference Stable — 2026-06-06

Tag: `pocketnode-inference-stable-2026-06-06`  
Main merge: `2d92881`  
Stabilization branch: `fix/pocketnode-inference-stabilization`

## Summary

This release stabilizes Pocket Node local inference on Android after a multi-phase model resolution, cancellation, and verification pass.

The app now handles runaway or degenerate generation safely, including Stop during prefill, immediate UI recovery, and clean follow-up prompts after cancellation.

## Verified fixes

- Native Stop button now cancels generation safely.
- Stop works during prefill before decode.
- UI returns to idle immediately after Stop.
- Post-stop recovery works: follow-up prompts generate normally.
- Empty assistant placeholder cleanup added after stopped/canceled generation.
- Prior generation job cleanup is awaited before a new send starts.
- SmolLM3 Q4_0 Fresh and draft model hashes registered.
- Existing UNKNOWN_HASH models auto-upgrade to VERIFIED on Rescan when their stored SHA-256 matches a newly added registry entry.
- Invalid OpenCL sampler stash was reviewed and dropped because the vendored `llama.h` still exposes the 4-arg sampler penalties API.

## Verification

- Gradle build on stabilization branch: `BUILD SUCCESSFUL`
- Gradle build on `main`: `BUILD SUCCESSFUL`
- APK install: `Success`
- App launch: confirmed live PID
- Resolver proof: `manualOverride=false decision=Llama 3 reason=pocketnode_smollm3_name_match`
- Stop proof: Kotlin stop request logged, native stop flag set, native observed stop after prefill before decode, UI returned to idle.
- Recovery proof: second prompt after Stop returned non-empty output; third prompt also returned non-empty output.

## Important commits

- `44d1ce7` — WIP checkpoint — multi-phase inference stabilization
- `32cfb4a` — fix: native stop button — prefill cancellation + immediate UI reset
- `dfa1f0f` — chore: gitignore dev-session screenshots and window dump XMLs
- `cf4880c` — chore: register SmolLM3 Q4_0 Fresh and draft model SHA-256 hashes
- `13f4d5a` — fix: recover cleanly after stopped generation
- `87a7121` — merge post-stop recovery into stabilization branch
- `2d92881` — merge stabilization branch into main

## Operational notes

The invalid `local-opencl-sampler-fixes` stash was intentionally dropped after verification. Its proposed 9-arg `llama_sampler_init_penalties(...)` migration does not match this checkout’s vendored `llama.h`, which still uses the 4-arg API.

Future sampler API work should start from an explicit vendored `llama.cpp` upgrade, not from the dropped stash.

## Recommended next work

1. P20 telemetry integration for Pocket Node health.
2. Pocket Node UI/UX polish around inference state and model verification.
3. Zero Cloud Tax launch content using the stabilized Pocket Node proof.
