# Pocket Node RC3 — Pre-Publication Redaction Checklist

Complete before publishing any file in this package. Mark each item CLEAR
or NEEDS REDACTION. This checklist itself is internal — do not publish it.

---

## 1. Secrets and Credentials

| Item | Status | Notes |
|------|--------|-------|
| Pro licensing HMAC secret | [ ] CLEAR | Not referenced anywhere in RC3 docs; must never appear in a published file or `assembleRelease` log excerpt |
| LiteLLM master API key | [ ] CLEAR | Not applicable to RC3 content, but re-check if any screenshot includes a terminal with Neo/Continue config visible |
| Android keystore password/alias | [ ] CLEAR | Not referenced |
| `.env` file contents | [ ] CLEAR | Not referenced |

## 2. Device and Network Identifiers

| Item | Status | Notes |
|------|--------|-------|
| Device serial (`RFCX60BRDWA`) | [ ] NEEDS REDACTION | Present in internal validation docs (`POCKET_NODE_RC3_*_VALIDATION.md`). Replace with "Galaxy Z Fold 6, Android 16" in any published file — do not carry the serial into the public package. |
| Tailscale mesh IPs (100.x.x.x) | [ ] CLEAR | Not referenced in RC3 docs |
| Home directory / local file paths (`C:\Users\Rhear\...`) | [ ] NEEDS REVIEW | Do not include raw local paths in published examples; use generic paths if any code excerpt is shown |

## 3. Personal Information

| Item | Status | Notes |
|------|--------|-------|
| Real name / email in git history | [ ] NEEDS REVIEW | Check `git log` author info before pointing anyone at commit history publicly |
| Package name `com.pocketnode.app` | [ ] CLEAR | App identifier, not sensitive |

## 4. Performance / Capability Claims

| Claim | Status | Notes |
|-------|--------|-------|
| "Model-load race fixed, verified crash-free under stress" | [ ] APPROVED | Directly reproduced and re-tested; accurate as stated |
| "5-iteration upgrade stress, zero duplicate loads" | [ ] APPROVED | Directly measured |
| "Physical reboot not tested" | [ ] APPROVED — must keep this caveat | Do not drop this caveat in any published summary; it is the one open gap |
| Any inference speed (TPS) claims | [ ] MUST AVOID IN THIS PACKAGE | RC3 made no inference-path changes; do not introduce new performance claims not already validated in the RC1/RC2 proof package |
| "Production-ready" / "enterprise-grade" | [ ] MUST AVOID | Still a release candidate on a debug-signed build |

## 5. Final Sign-Off

Reviewed 2026-07-02 against a grep sweep for device serials, mesh IPs,
hostnames, secrets, tokens, local paths, and internal code-path names,
plus a claim-accuracy pass against the RC3 evidence and a forbidden-claim
sweep (production-ready, crash-proof, enterprise-grade, etc.).

- [x] `pocket_node_rc3_release_notes.md` — reviewed, no device serial, no secrets, claims match evidence
- [x] `pocket_node_rc3_validation_table.md` — reviewed; device serial not present (uses "Galaxy Z Fold 6, Android 16"); one internal code-path reference (`BootReceiver.onReceive()`) softened to plain language
- [x] `pocket_node_rc2_to_rc3_operator_changelog.md` — reviewed, no secrets, no identifiers
- [x] `pocket_node_rc3_reddit_discord_summary.md` — reviewed, tone appropriate, reboot caveat retained and visible

No device serial, mesh IP, hostname, secret, token, or local file path
found in any public-facing file. The only occurrence of the device serial
(`RFCX60BRDWA`) in this package is in this checklist itself (Section 2,
as a description of the redaction rule) — this checklist is internal-only
and is explicitly excluded from publication.

**Verdict: GO_PUBLIC_AFTER_REVIEW**

The four public-facing files (release notes, validation table, operator
changelog, Reddit/Discord summary) are clean and ready for the user's own
manual read-through before any external posting. This checklist and the
internal validation docs (`POCKET_NODE_RC3_*_VALIDATION.md`,
`POCKET_NODE_RC3_CLOSURE.md`) remain internal-only and must not be
published.

**Reviewer sign-off:** automated review pass, 2026-07-02

## 6. July 5 Re-Pass — Fresh Benchmark / RC3 Hygiene Additions

A follow-on hygiene series (test-suite fix, stale test expectations,
Operator branding normalization, model-identity documentation, fresh
Fold6 benchmark capture) added new content after the 2026-07-02 sign-off.
This section re-reviews that new content specifically.

| Item | Status | Notes |
|------|--------|-------|
| `pocket_node_rc3_validation_table.md` — new "Fresh Fold6 Validation" section | [x] CLEAR (after fix) | Originally added precise TPS/decode/prefill/TTFT numbers and a direct link to an unredacted internal artifact — both violated Section 4's "avoid inference-speed claims" rule and this section's IP-redaction expectations. Replaced with a short, redacted statement confirming verified model identity, backend, successful generation, and sub-WARN thermal behavior only. No precise performance numbers or artifact link remain in this public file. |
| Benchmark artifact link/citation status | [x] CLEAR | The internal artifact (`p29_target_rc3_fresh_benchmark_20260705_121201_ac676e3_artifacts/`) is no longer linked from any public-facing file. It remains an internal engineering artifact, referenced only from `POCKET_NODE_RC3_CLOSURE.md` (internal-only, per Section 5 above). |
| Private mesh IP redaction | [x] CLEAR (after fix) | `rc3_benchmark_summary.md` originally contained a private Tailscale IP; replaced with `[REDACTED_PRIVATE_MESH_IP]`. No other occurrence of that IP found in the artifact directory (raw response JSONs, logcat capture, or elsewhere). |
| No precise TPS/TTFT claims in public package | [x] CLEAR (after fix) | Confirmed via grep: no `TPS`/`TTFT`/decode/prefill figures remain in any of the four public-facing files. The RC1/RC2 package's existing rounded `"~13+ TPS"` claim is untouched and unaffected by this pass. |
| API-visible stats caveat | [x] CLEAR | Public validation table now states API-visible performance/thermal stats "remain deferred, consistent with earlier RC3 scope decisions" — no implementation claimed or planned. |

**Verdict after July 5 re-pass: GO_PUBLIC_AFTER_REVIEW (unchanged)** — the
four public-facing files are clean as of this re-pass, contingent on the
fixes above already being applied. The internal-only fresh-benchmark
artifact and `POCKET_NODE_RC3_CLOSURE.md` remain excluded from publication,
as before.

**Reviewer sign-off:** re-pass, 2026-07-05

## 7. Raw Artifact / Log File Scanning (added after a missed finding)

A prior pass scanned only markdown docs for specific secret-token patterns
and one known Tailscale IP. It missed that a **raw, unfiltered, system-wide
`adb logcat` capture** had been committed as part of a benchmark artifact
directory — this file was never intended for the public package, but a
plain doc-text scan does not catch it. That file was removed from the
push-bound branch history entirely (never reached the public remote) and
replaced with a small, Pocket Node-scoped log excerpt containing only
native generation-stats lines.

**Going forward, every pre-push redaction pass must additionally scan any
non-markdown artifact/log/binary file staged for a public branch — not
only `.md` docs — for:**

| Category | Pattern / rule | Fails the check if present |
|---|---|---|
| Private IPv4 ranges | `192.168.*`, `10.*`, `172.16.*`–`172.31.*` | Any occurrence in a file destined for a public branch |
| Tailscale / private mesh IPs | `100.64.0.0/10` range, or any IP tied to a known personal Tailscale node | Any occurrence |
| Device/serial identifiers | ADB device serials, IMEI-like strings | Any occurrence outside this internal checklist |
| Emails/usernames | Any real email address or account handle not intended for publication | Any occurrence |
| Tokens/API keys | `gho_`, `ghp_`, `github_pat_`, `xoxb-`, `xapp-`, `sk-`, `*_KEY=`, `*_PASSWORD=`, `Authorization:`/`Bearer` headers | Any occurrence |
| Private URLs | Internal hostnames, LAN-only URLs, non-public API endpoints | Any occurrence |
| Broad raw system logcat | Any file that is a full, unfiltered `adb logcat` capture (not scoped/filtered to the app under test) | **Automatic fail** — raw system-wide logcat must never be included in a public package or public branch history, regardless of whether a spot-check of it happens to be clean; unscoped logs can contain arbitrary incidental data (other apps, OS services, network state) that no single grep pass can be trusted to fully vet |

Any file tripping the last rule (broad raw logcat) must be either dropped
entirely or replaced with a manually-curated, app-scoped excerpt before
it can be considered for a public branch — it is not sufficient to redact
individual matched strings within it.

**Reviewer sign-off (raw artifact scan addendum):** 2026-07-05, following
discovery that `full_logcat.txt` (committed in a since-rewritten local
commit, never pushed) contained private LAN IP addresses and unrelated
system-service log noise. The offending commit was rewritten out of the
branch's history before any push occurred; it never reached
`origin/main`. Replaced with `pocket_node_log_excerpt.txt`, an app-scoped
excerpt containing only Pocket Node's own log lines.

---

*Internal document. Do not publish as part of the public proof package.*
