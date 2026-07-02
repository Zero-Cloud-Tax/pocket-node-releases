# Pocket Node RC2 Edge Gate Repo Sync

Date/time: 2026-07-02, 15:46–15:52 local
Branch: main
Starting HEAD: 47c3d5f ("P28: record validated RC2 tag")
Live Neo gate path: `/home/neo/homelab/litellm/edge_gate/gate.py` (retrieved read-only via
`scp` over an existing SSH connection to host `neo`; no files were edited on Neo, no services
were restarted or redeployed)
Local gate before sync: `edge_gate/gate.py`, version `9.7.0`, policy `fold6_preflight_v1`
Live gate version: `9.8.0`, policy `fold6_preflight_thermal_v2`
Local gate version before sync: `9.7.0`

## Reason
Phase 3's route-away proof used the live, thermal-aware Neo edge gate (`fold6_preflight_thermal_v2`,
observed directly via its `/health` and `/metrics` endpoints and via real routing decisions:
`allow_fold6` while the Fold6 was eligible, `bypass_thermal` → `mac-studio-edge-fallback` while
ineligible). The local repo's copy of `edge_gate/gate.py` was v9.7.0 and had no thermal-aware
logic at all — this phase set out to reconcile that drift.

## Important finding: `edge_gate/` is deliberately git-ignored, not merely stale
Inspection of `.gitignore` found:
```
# Cluster infra staging (gate lives on Neo, not in this repo)
edge_gate/
```
`git log --all --oneline -- edge_gate/gate.py` returns no history at all — this file has
**never been tracked by git in this repository**. The local copy under `edge_gate/` is a
working reference/staging file that was deliberately excluded from version control by an
existing, documented repo policy: the edge gate's real source of truth is meant to live on Neo,
not in this app repo.

This means the premise "the checked-in file is stale and should be synced back as a targeted
commit" does not apply as originally framed — there is no checked-in file to update in git;
there is only an untracked local copy on disk. Committing it now would mean **overriding an
existing, intentional `.gitignore` policy** (either by force-adding the ignored path or by
editing `.gitignore` to un-ignore it), which is a repo-policy decision beyond "repo sync only,"
not a mechanical stale-file fix. Per the hard rule to only sync if the change is repo-only and
does not represent a broader policy change, this phase did **not** force the file into git.

The on-disk local copy at `edge_gate/gate.py` **was** overwritten with the live v9.8.0 content
(a plain filesystem copy, not a git operation) so that anyone reading the local reference copy
sees the accurate, currently-deployed behavior rather than the stale v9.7.0 logic. Since the
path is git-ignored, this change is invisible to `git status`/`git diff` and carries no risk of
being accidentally committed or pushed.

## Live behavior confirmed
Policy: `fold6_preflight_thermal_v2`
Fold6 capabilities source: `FOLD6_CAPABILITIES_URL` (env-overridable, default
`http://100.99.70.73:11434/capabilities`) — the Fold6's own `/capabilities` endpoint, polled
asynchronously before every Fold6-targeted chat completion is forwarded
Hard block threshold: `FOLD6_THERMAL_HARD_C = 65.0` (°C) — bypasses regardless of the app's own
`eligible_for_inference` flag if `peak_thermal_zone_c >= 65.0`, or if
`thermal_zone_gate_reason` contains the substring `"hard_block"`
Warn threshold: `FOLD6_THERMAL_WARN_C = 55.0` (°C) — present as a declared constant and exposed
via `/health`'s `thermal_policy` block, but not read anywhere in the actual gate decision logic
(`_thermal_gate`) — it is telemetry/documentation only in this version, matching the app's own
"WARN — telemetry only; inference not blocked" semantics
Eligible decision: `allow_fold6` — model left unchanged, forwarded to LiteLLM as-is
Ineligible decision: `bypass_thermal` (general ineligibility or thermal hard-block) or the more
specific `bypass_model_not_loaded` (when `/capabilities` is unreachable, or when
`reason_if_not_eligible == "model_not_loaded"`) — both rewrite the model to `BYPASS_MODEL`
Fallback: `mac-studio-edge-fallback`

This matches, field-for-field, what was directly observed against the live gate in Phase 3
(`x-edge-gate-route: allow_fold6` while eligible; `x-edge-gate-route: bypass_thermal` →
`x-edge-gate-final-model: mac-studio-edge-fallback` while forced ineligible via the Fold6's
existing `/debug/eligibility/force_block` endpoint).

## Diff classification
**SAFE_SYNC_THERMAL_GATE** for the *content* of the diff — no secrets, no unrelated
experimental changes; every change is either the documented v9.7→v9.8 thermal-awareness
upgrade or making two existing values (`LITELLM_BASE`, `LOG_FILE`) environment-overridable with
identical defaults. A full secret/token/password/API-key grep of the live file returned no
matches.

However, because `edge_gate/` is deliberately git-ignored (not merely stale), the *commit*
action for `edge_gate/gate.py` itself is **BLOCKED_UNCLEAR at the repo-policy level** — safe
content, but committing it requires a separate, deliberate decision to change the `.gitignore`
policy, which is out of scope for a "repo sync only" hygiene phase. Only the local on-disk
reference copy was updated (untracked, invisible to git); `edge_gate/gate.py` was not staged or
committed.

## Files changed
- `edge_gate/gate.py`: **not committed** (git-ignored, out of git's purview; local on-disk copy
  only, updated to mirror the live v9.8.0 file so the local reference copy is accurate)
- `POCKET_NODE_RC2_EDGE_GATE_SYNC.md`: new, committed (this document)

## Runtime impact
None. No files were edited on Neo. No services were restarted, redeployed, or reconfigured.
`edge_gate/gate.py` on disk locally was overwritten with a read-only `scp` copy of the live
file for reference-copy accuracy only — this has no effect on git history, the local Pocket
Node app, or the live Neo deployment.

## Verification
Local inspection: live gate content confirmed field-for-field against Phase 3's observed
routing behavior (`allow_fold6`/`bypass_thermal`/`bypass_model_not_loaded`,
`mac-studio-edge-fallback`, `fold6_preflight_thermal_v2`, 65.0°C hard-block, 55.0°C warn,
`FOLD6_CAPABILITIES_URL` pointing at the Fold6's real Tailscale address)
Secrets found: no — explicit grep for `api_key|apikey|secret|password|passwd|token=|authorization|LITELLM_MASTER|sk-`
against the live file returned zero matches
Push main: yes (this doc-only commit)
Commit: doc-only — see final response for SHA

## Follow-up decision needed (not made in this phase)
Whether to formally un-ignore `edge_gate/` (or add a clearly-labeled `edge_gate/gate.py.reference`
snapshot path) is a repo-governance decision for the repo owner, not something to resolve
silently inside a hygiene phase. If a version-controlled reference copy of the live gate is
wanted going forward, that should be its own deliberate commit that touches `.gitignore`
explicitly, with a commit message that says so.
