# Pocket Node RC3 — Short Technical Summary (Reddit / Discord ready)

**Title suggestion:** Pocket Node RC3 — hardened an Android edge-inference
service against real crash conditions (model-load race, lifecycle, cold
start)

---

Following up on the earlier Pocket Node build (Android edge-inference on a
Galaxy Z Fold 6): RC3 is a hardening pass, not a features pass. Short
version of what changed:

- Found and fixed a real crash: rapid reinstall/relaunch could hit a
  native `ggml_abort` from unserialized concurrent model load/unload
  calls. Reproduced it deliberately under stress, fixed it with a
  two-layer mutex (Kotlin coordinator + native lock), then re-ran the same
  stress loop crash-free.
- The foreground notification is now state-aware (Loading model… /
  Serving / Unavailable: reason / Stopping…) with a "Stop serving" action,
  instead of a static "running" notification.
- Added an in-app Diagnostics screen — service state, uptime, last
  inference, last error, thermal zone temps, eligibility — so you don't
  need ADB/logcat to check on the node.
- Added per-model "Reverify" (re-check file integrity without
  re-downloading) and "Export Inventory" (JSON snapshot via the standard
  share sheet).
- Re-verified upgrade survival (5x rapid reinstall, zero crashes, zero
  duplicate model loads), manual stop safety, and cold-start recovery —
  all directly on-device, not just in code review.

**Known gap:** a real physical reboot wasn't tested this cycle — the
developer's daily-use phone, didn't want to force a disruptive reboot for
a code path that's already proven via the equivalent upgrade-restart
trigger. Will close that out before a public release candidate.

No inference, routing, or thermal-threshold behavior changed in this
release — this was entirely about not crashing and being observable.

Full validation table and release notes in the repo if anyone wants the
detail.
