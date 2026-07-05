# Pocket Node v0.1.0-rc3 — Release Notes

**Tag:** `v0.1.0-rc3-p29`
**Focus:** operator safety and observability, not new inference features.

Pocket Node RC3 moves the app from "working edge inference node" to
"operator-safe edge service." No model, routing, or thermal-threshold
behavior changed. Every change in this release makes the app safer to run
unattended, easier to observe, and harder to break under real-world device
conditions (rapid reinstalls, OS-level app kills, cold starts, upgrades).

## Highlights

- **Fixed a real model-load race.** A rapid install/relaunch cycle could
  trigger a native `ggml_abort` crash due to unserialized concurrent model
  load/unload calls. Reproduced directly, then fixed with a dual-layer
  mutex (Kotlin-side coordinator + native-side lock), verified crash-free
  under repeated stress.
- **Hardened the foreground service.** The persistent notification now
  reflects live state (Starting / Loading model… / Serving / Unavailable:
  reason / Stopping…) and includes a "Stop serving" action, wired to the
  existing safe drain-then-free shutdown sequence.
- **Added an on-device Diagnostics screen.** Service state, uptime, active
  session, last inference, last error, thermal zone temperatures, and
  eligibility — all visible in the app itself, no ADB or logcat required.
- **Added model inventory tools.** Per-model "Reverify" (recompute
  SHA-256/validity without re-downloading) and "Export Inventory" (JSON
  summary via the standard Android share sheet).
- **Re-verified survival across upgrades, manual stops, and cold starts.**
  Five rapid reinstall cycles, zero crashes, zero duplicate model loads,
  confirmed recovery every time.

## What did *not* change

- No inference behavior, model support, or generation quality changes.
- No `/health`, `/capabilities`, or `/api/generate` schema changes.
- No thermal threshold changes.
- No routing, gateway, or homelab-side changes.

## Known limitation

A full physical device reboot was not exercised in this release cycle (to
avoid disrupting the developer's daily-use device). The underlying start
path is shared code with the upgrade-restart path, which *was* verified
repeatedly with zero failures — but a cold-boot-from-power-off scenario
remains unverified until a convenient reboot window is scheduled.

## Upgrade notes

This is a debug-signed release candidate, not a signed production release.
No user-facing settings or data migrations are required — installing over
RC2 preserves existing models and settings.

Full validation detail: see `pocket_node_rc3_validation_table.md` in this
package.
