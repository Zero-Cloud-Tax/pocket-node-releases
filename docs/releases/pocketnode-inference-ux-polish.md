# Pocket Node Inference UX Polish — 2026-06-06

Merge commit: `4447651`  
Branch: `feat/inference-state-ux-polish`  
Branch commit: `6cd1a53`

## Summary

Inference state was previously invisible to the user after a model loaded — no indication of which model was running, its verification status, or whether a stop request was in progress.

This merge makes the running model visible at a glance and gives clear feedback during cancellation and failure.

## Verified fixes

- **InferenceStatusCard added** — appears below the chat header once a model is loaded; shows model name, load state, backend chip (GPU/CPU), verification badge, role badge (PRIMARY_MODEL / DRAFT_MODEL), resolved file path, and last inference timestamp. Hidden until `isModelReady` is true.
- **Stopping… state added** — animated card appears above the input bar while a stop request is in progress; input TextField and FAB are disabled until coroutine cleanup completes.
- **Backend chip refactored** — `BackendInfo.isAccelerated()` and `displayLabel()` helpers added; Vulkan+OpenCL combo handled correctly; blank backend returns "Unknown" instead of "CPU".
- **Diagnostics rows added** — model card in DiagnosticsScreen now shows Resolved file, Loaded, Verification, Role, and Last inference (timestamped after each non-stopped generation).
- **Failed-model messaging improved** — error strings in `ChatViewModel` and `ChatNodeEntryResolver` now name the concrete recovery action: "Rescan Model Hub", "re-import it", or "choose another verified model".

## Verification

- `:app:assembleDebug` — BUILD SUCCESSFUL (42 tasks)
- `adb install -r` — Success
- Smoke test (6/6 passed):
  1. Launch with no model → status card hidden ✓
  2. Load model → InferenceStatusCard appears with real model data ✓
  3. Start generation → tap Stop → "Stopping…" card appears, input disabled ✓
  4. Stop completes → UI returns to idle ✓
  5. Follow-up prompt → generation restarts and completes normally ✓
  6. Open Diagnostics → Last inference shows correct timestamp ✓

## Important commits

- `6cd1a53` — feat: inference state UX polish — status card, Stopping state, diagnostics
- `4447651` — Merge branch 'feat/inference-state-ux-polish'

## Recommended next work

1. Zero Cloud Tax launch prep — stable UI and screenshots now in `main`.
