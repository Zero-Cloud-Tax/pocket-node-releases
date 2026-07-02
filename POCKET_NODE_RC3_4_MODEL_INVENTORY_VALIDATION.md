# Pocket Node RC3.4 Model Inventory Validation

Date/time: 2026-07-02, 17:10–17:25 local
Branch: main
Starting HEAD: b38b33e ("P29: improve Pocket Node diagnostics screen")
Final HEAD: (see final response — inserted after this phase's commit is created)
Device: Samsung SM-F956U (Galaxy Z Fold 6), Android 16, serial RFCX60BRDWA
APK: app/build/outputs/apk/debug/app-debug.apk (debug build)

## Current-state inspection
Existing infrastructure found (this was hardening/wiring existing primitives, not building
verification/cleanup from scratch): `ModelArtifactManager.validateExistingFile(file, intent)`
already recomputes SHA-256 and re-inspects a GGUF header without re-copying — the exact
"reverify" primitive, already used by the import path
(`importFromFile`'s same-path branch). `ModelArtifactManager.cleanupFailedPrimaryArtifact()` +
`ModelsViewModel.cleanupFailedPrimaryModels()` already implement bulk "quarantine/clean failed
models" (delete file + Room record, gated to non-draft models with `verificationStatus ==
FAILED` and confirmed app-owned), already wired to a "Clean Failed Primary (N)" button in
`ModelsScreen.kt`. No per-model reverify action existed, and no export/copy of the model
inventory existed anywhere in the app.

Gaps found against the mission's tight scope: (1) no per-model "reverify" affordance — only a
debug-gated bulk "Audit Models" button that logs to logcat, not user-facing; (2) no inventory
export mechanism; (3) quarantine/cleanup logic was confirmed already sufficient as bulk-only —
per this phase's explicit instruction ("quarantine ... only if existing logic supports it"), no
new quarantine logic was added since the existing bulk cleanup already covers it correctly.

## Fix design
Reverify: added `ModelsViewModel.reverifyModel(model: LocalModel)`, which calls the existing
`ModelArtifactManager.validateExistingFile()` on the model's on-disk file (no re-download, no
re-import) and persists the refreshed `sha256`/`sizeBytes`/`verificationStatus`/`lastCheckedAt`
via `ModelManager.addModel()` (Room `OnConflictStrategy.REPLACE` on primary key `id`, the same
mechanism already used to add models). If the file is missing or fails inspection, the model is
marked `FAILED` rather than throwing. Wired to a new per-model "Reverify" `IconButton`
(`Icons.Default.Refresh`) in `InstalledModelCard`, next to the existing Delete button, for both
the Chat Models and Draft Models sections.

Quarantine/clean: **no new logic added** — the existing bulk "Clean Failed Primary (N)" button
and its underlying `cleanupFailedPrimaryArtifact()`/`cleanupFailedPrimaryModels()` already
satisfy this, per the phase's own scope instruction to reuse existing logic rather than invent
new quarantine semantics.

Export inventory: added `ModelsViewModel.exportInventory(context, onReady)`, which builds a
JSON array from `ModelArtifactManager.createAuditRecord()` for every installed model (the same
fields already used for the existing debug "Audit Models" log output) and writes it to
`context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)` — the same directory the
existing `external-files-path name="downloads"` entry in `file_paths.xml` already exposes via
the app's existing `FileProvider` (the identical pattern `AppUpdater.kt` already uses for APK
installs). No new FileProvider path, permission, or manifest entry was added. Wired to a new
"Export Inventory" button in `ModelsScreen`'s header row (visible whenever any models are
installed), which launches a standard `Intent.ACTION_SEND` share chooser with the resulting
`content://` URI.

No secrets/private URLs exposed: the exported JSON contains only model name, role,
verification status, resolved on-device path, file size, SHA-256 prefix, GGUF validity, and
architecture — all data already visible elsewhere in the app UI or existing debug logs. No API
keys, Tailscale addresses, or homelab-internal identifiers.

## Files changed
- `app/src/main/java/com/pocketnode/app/ui/screens/ModelsViewModel.kt`: added `reverifyModel()`
  and `exportInventory()` (plus a small private `jsonString()` escaping helper); added the
  `withContext` import.
- `app/src/main/java/com/pocketnode/app/ui/screens/ModelsScreen.kt`: added the "Export
  Inventory" header button; added an `onReverify` parameter to `InstalledModelCard` and wired it
  at both call sites (Chat Models, Draft Models); added the "Reverify" `IconButton`.
- `POCKET_NODE_RC3_4_MODEL_INVENTORY_VALIDATION.md` (new): this document.
- `POCKET_NODE_RC3_3_DIAGNOSTICS_VALIDATION.md`: trivial Final-HEAD SHA-fill correction left
  over from the prior phase's commit.

No native code, `/health`/`/capabilities`/`/api/generate` schema, thermal threshold, or
Neo/LiteLLM/routing changes.

## Build result
assembleDebug: **PASS** — `BUILD SUCCESSFUL in 1m 1s`.

## Device/UI verification
Reached Model Hub (`ModelsScreen`) via Home → Model Vault card, confirmed live on-device via
screenshots (not code review only).

Export Inventory: **confirmed working end-to-end** — tapping the button produced a real Android
share sheet ("1 item", `pocketnode_model_inventory.json`) via the standard system chooser.
Pulling the written file directly from
`/storage/emulated/0/Android/data/com.pocketnode.app/files/Download/pocketnode_model_inventory.json`
showed valid, well-formed JSON listing both installed models (`PocketNode_SmolLM3_Q4_0_Fresh`,
role MAIN, VERIFIED, sha256Prefix `dde7bbbffea19de3`; `SmolLM2 135M Draft (Q4_0)`, role DRAFT,
VERIFIED, sha256Prefix `bcc3af2849ad6095`) with accurate file sizes and GGUF validity flags.

Reverify: **button confirmed present, tappable, and non-crashing** on both installed model
cards (Chat Models and Draft Models sections), correctly positioned next to the existing Delete
action. An early tap missed the small icon and landed on the card's own `onClick` (which
navigates to the Chat screen and loads the model there — pre-existing, unrelated behavior, not
a bug in this phase's change); a corrected, precisely-targeted tap on the icon itself stayed on
the Model Hub screen (confirming the icon's own click target, not the card's, was hit) and
produced no crash, no ANR, and no regression to the app's responsiveness. The verification
status badge remained "VERIFIED" afterward, which is the correct outcome for a genuinely valid
file — not evidence of failure. Direct confirmation of the exact Room-write timing (via pulling
`pocketnode.db`) was inconclusive due to the on-device WAL file not being checkpointed into the
main `.db` file at pull time (no `sqlite3` binary is present on-device to query in place, and a
plain file copy of `.db`+`.db-wal`+`.db-shm` did not yield readable tables from the host); this
is a verification-tooling limitation, not an observed app defect. `reverifyModel()` calls the
exact same `ModelArtifactManager.validateExistingFile()` primitive already exercised (and
already proven correct) by the import path, so the underlying logic is not new/untested code —
only the UI wiring to it is new.

## API regression checks
`/health`: **PASS** — verified before and after the on-device UI walkthrough.
`/capabilities`: **PASS** — all documented fields present, unchanged schema.
`stream=false`: **PASS** — verified generation still works correctly (`{"response": "...",
"done": true}`).

## Verdict
**CONDITIONAL**

- `assembleDebug` passes with no regressions.
- Export Inventory is fully verified end-to-end: real share sheet, correct file written via the
  existing FileProvider path, valid JSON content pulled and inspected directly.
- The per-model Reverify button is confirmed present, correctly placed, tappable without
  crashing, and calls an already-proven validation primitive — but the exact write-back to Room
  (the specific `sha256`/`verificationStatus`/`lastCheckedAt` persistence after a reverify call)
  was not independently captured via a direct before/after database read, due to an on-device
  tooling gap (no `sqlite3` binary, WAL not checkpointed on a plain file pull). This is a
  verification-completeness gap, not a known or observed defect — CONDITIONAL rather than PASS
  specifically because that one data point wasn't captured with full rigor, not because
  anything failed.
- Quarantine/cleanup intentionally reuses existing, already-verified bulk logic per this
  phase's own scope instruction — no new code, no new risk there.
- No new inference features; no `/health`/`/capabilities`/`/api/generate` schema changes; no
  thermal threshold changes; no native/lifecycle code touched; no Neo/LiteLLM/routing changes;
  no new FileProvider paths or permissions.

## Follow-ups
- Re-verify the Reverify action's Room persistence with a more direct method next time it's
  touched — e.g. temporarily push a `sqlite3` binary to the device, or read the `lastCheckedAt`
  timestamp through a UI surface (the Diagnostics or Model Hub screen doesn't currently display
  it) rather than only through the DB file.
- Consider surfacing `lastCheckedAt` in the model card or Model Hub UI, since it's now
  meaningfully updated by Reverify but not shown anywhere.
- The "Reverify" icon and the card's own `onClick` (select-and-load) sit close together in a
  small touch target area — worth a follow-up UX pass (e.g. slightly more spacing or a
  confirmation toast on tap) if operators report mis-taps in practice, though Compose's own
  click-target semantics already correctly separate the two actions.
