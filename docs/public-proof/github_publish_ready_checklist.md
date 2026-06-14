# Pocket Node — GitHub Publish Ready Checklist

**Prepared:** 2026-06-14T20:21:03Z
**Verdict:** GO_PUBLIC_README_ONLY
**Reddit:** BLOCKED (pending DS-01, DS-03 device screenshots)

---

## Automated Checks (Completed by this session)

- [x] Repo directory structure assembled (`pocket-node-public-repo/`)
- [x] README.md written (renamed from `pocket_node_public_readme.md`)
- [x] LICENSE added (MIT, 2026)
- [x] `.gitignore` created (excludes all secrets, configs, artifacts)
- [x] Architecture diagram copied (`architecture/pocket_node_architecture_diagram.mmd`)
- [x] Validation table copied as `docs/validation_results.md`
- [x] `docs/routing_policy.md` extracted and expanded from README
- [x] `docs/thermal_safety.md` extracted and expanded from README
- [x] All 3 example configs in `examples/` — confirmed clean (all [PLACEHOLDER])
- [x] Terminal evidence TE-01 through TE-06 embedded in `screenshots/redacted_terminal_evidence_only/README.md`
- [x] TC-06 redaction applied: device owner name replaced with `"[owner]'s Z Fold6"` in TE-03
- [x] Full redaction scan PASSED (see `final_redaction_scan.txt`)
- [x] No Tailscale IPs (100.x.x.x) in any file
- [x] No API keys or bearer tokens in any file
- [x] No internal usernames or hostnames in any file
- [x] No keystore references in any file
- [x] No internal service directory references in any file

---

## Manual Steps Required Before `git push`

### Step 1 — Create the GitHub repository

1. Go to github.com → New repository
2. Name: `pocket-node` (or your preferred name)
3. Visibility: **Public**
4. **Do NOT** initialize with README, .gitignore, or license (we have all of these)
5. Copy the remote URL (e.g., `https://github.com/[username]/pocket-node.git`)

### Step 2 — Initialize local git repo

```sh
cd "C:\Users\Rhear\Pocket Node\pocket-node-public-repo"
git init
git add .
git status    # ← review list before committing
git commit -m "Initial public proof package: Pocket Node v0.1-proof

Thermally-aware Android inference node with homelab routing gate.
All claims validated on physical hardware (Galaxy Z Fold6, Ollama RC2,
Vulkan backend, SmolLM3 3.1B Q4_0, ~13+ TPS).

Includes: README, architecture diagram, validation table, redacted
example configs, terminal evidence. Gate source not yet included
(planned for P28 release)."
```

### Step 3 — Verify `git status` before pushing

Confirm the following are NOT staged:
- `gate.env` (real file, if present anywhere)
- `docker-compose.yml` (real, with IPs)
- `litellm/config.yaml` (real, with IPs)
- Any `.env` file without `.example` suffix
- Any `p27_*.py` file
- Any `*_artifacts/` directory
- `terminal_captures*.json`
- `decisions.jsonl`

If any of these appear, STOP and check your `.gitignore` before proceeding.

### Step 4 — Push

```sh
git remote add origin https://github.com/[username]/pocket-node.git
git branch -M main
git push -u origin main
```

### Step 5 — Post-push verification

1. Open the repo on GitHub in a browser
2. Confirm README renders correctly (check all section headers, tables, links)
3. Confirm Mermaid diagram renders (GitHub supports `.mmd` via the Mermaid renderer in README if linked, or renders in-browser if viewed as a `.mmd` file)
4. Click all internal links in README — confirm they resolve
5. Spot-check `examples/docker-compose.example.yml` on GitHub — confirm no real IPs visible
6. Add repo description: `"Thermally-aware Android LLM inference node with homelab routing gate"`
7. Add topics: `llm`, `android`, `ollama`, `homelab`, `local-ai`, `tailscale`, `fastapi`
8. Copy final repo URL for Reddit post

---

## What Is NOT Done Yet (Reddit Blockers)

- [ ] DS-01: Fold6 `ollama list` screenshot — capture on device, review, add to `screenshots/`
- [ ] DS-03: Continue.dev completion through gate — capture on laptop, review, add to `screenshots/`
- [ ] `pocket_node_reddit_post.md` `[GITHUB-REPO-URL]` placeholder → replace with live URL
- [ ] Reddit post ready for submission

Reddit go/no-go remains: **CONDITIONAL** — becomes `GO_PUBLIC_README_PLUS_REDDIT` when DS-01 and DS-03 are captured, reviewed, and pushed to the repo.

---

## Do Not Push These Files

The following files exist on your machine and must never appear in the public repo:

| File / Path | Reason |
|-------------|--------|
| `gate.env` (on gateway host) | Real Tailscale IP |
| `[internal-service]/.env` (on gateway host) | LiteLLM master key |
| `docker-compose.yml` (real, on gateway host) | IPs + DB credentials |
| `litellm/config.yaml` (real, on gateway host) | All mesh Tailscale IPs |
| `p27_*.py` (internal scripts) | SSH patterns, auth extraction |
| `p27_*_artifacts/` (internal) | Internal state, JSON blobs |
| `p27_terminal_evidence/terminal_captures_v2.json` | Pre-redaction captures (contains real name) |
