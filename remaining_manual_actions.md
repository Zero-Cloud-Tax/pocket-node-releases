# Pocket Node — Remaining Manual Actions

**Prepared:** 2026-06-14T20:21:03Z
**Current verdict:** GO_PUBLIC_README_ONLY

This file tracks what cannot be automated and requires manual action.

---

## Blocking — Must Complete Before ANY GitHub Push

### MA-01: Create GitHub repository
**Who:** You (manual — requires GitHub account auth)
**Action:** Create new public repo at github.com. Do NOT initialize with any files.
**Time:** 2 minutes

### MA-02: Run `git init` and first commit locally
**Who:** You (or Claude can assist via terminal if you grant access)
**Commands:**
```sh
cd "C:\Users\Rhear\Pocket Node\pocket-node-public-repo"
git init
git add .
git commit -m "Initial public proof package: Pocket Node v0.1-proof"
git remote add origin https://github.com/[YOUR-USERNAME]/pocket-node.git
git branch -M main
git push -u origin main
```
**Time:** 5 minutes

### MA-03: Post-push spot check
**Who:** You
**Action:** Open repo on GitHub, verify README renders, links work, no secrets visible.
**Time:** 5 minutes

---

## Blocking — Must Complete Before Reddit Post

### MA-04: Capture DS-01 (Fold6 `ollama list`)
**Who:** You (requires Fold6 device with terminal access)
**Action:**
1. Open terminal on Fold6 (Termux or ADB shell)
2. Run: `ollama list`
3. Screenshot the output
4. Check: no Tailscale IP visible in prompt, no private hostname
5. If prompt shows `user@hostname:`, crop or redact before saving
6. Save as `screenshots/ds01_fold6_ollama_list.jpg`
**Time:** 5 minutes

### MA-05: Capture DS-03 (Continue.dev completion through gate)
**Who:** You (requires laptop with Continue.dev and gate running)
**Action:**
1. Open Continue.dev in VS Code (or your IDE)
2. Send a short completion request with model set to `pocket-node-fold6`
3. Screenshot the completion in the IDE
4. Check: no Tailscale IP visible in status bar or output
5. Save as `screenshots/ds03_continue_dev.jpg`
**Time:** 5 minutes

### MA-06: Add device screenshots to repo and push
**Who:** You
**Action:** Copy DS-01 and DS-03 into `pocket-node-public-repo/screenshots/`, then:
```sh
cd "C:\Users\Rhear\Pocket Node\pocket-node-public-repo"
git add screenshots/
git commit -m "Add device screenshots: Fold6 ollama list, Continue.dev"
git push
```

### MA-07: Update Reddit post placeholder
**Who:** You (or Claude)
**Action:** Open `p27_public_proof_package/pocket_node_reddit_post.md`, replace `[GITHUB-REPO-URL]` with the live GitHub URL.

### MA-08: Post to r/LocalLLaMA
**Who:** You (manual — requires Reddit account auth)
**Action:** Follow `p27_public_screenshot_review/reddit_publish_checklist.md`
**Time:** 10 minutes including first comment

---

## Non-Blocking — Optional Enhancements

### MA-09: Add repo metadata on GitHub
**Action:** Add description + topics after push
- Description: `Thermally-aware Android LLM inference node with homelab routing gate`
- Topics: `llm`, `android`, `ollama`, `homelab`, `local-ai`, `tailscale`, `fastapi`

### MA-10: Capture DS-02 (Fold6 inference in progress)
Optional screenshot: Fold6 screen while inference is running. Add visual proof that the device is actively computing.

### MA-11: Render Mermaid diagram to PNG
GitHub renders `.mmd` files natively, but a PNG in the README `architecture/` folder is more portable. Optional.

### MA-12: Begin P28 gate source release prep
**What:** Externalize all hardcoded constants in `gate.py` to environment variables, clean for public release. Not a blocker for this publish but is the next meaningful technical step.

---

## Summary

| # | Action | Blocks | Time |
|---|--------|--------|------|
| MA-01 | Create GitHub repo | All | 2 min |
| MA-02 | git init + first push | All | 5 min |
| MA-03 | Post-push spot check | All | 5 min |
| MA-04 | Capture DS-01 (Fold6) | Reddit | 5 min |
| MA-05 | Capture DS-03 (Continue.dev) | Reddit | 5 min |
| MA-06 | Add screenshots to repo | Reddit | 5 min |
| MA-07 | Update Reddit URL placeholder | Reddit | 2 min |
| MA-08 | Post to Reddit | — | 10 min |

**Minimum time to Reddit post after this file was written: ~39 minutes of manual work.**
