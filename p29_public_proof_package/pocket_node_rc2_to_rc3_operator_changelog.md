# Pocket Node — Operator Changelog: RC2 → RC3

Written for someone operating a Pocket Node device day-to-day, not for a
code review audience. What changed, why it matters, and what to expect.

| Area | RC2 behavior | RC3 behavior | Why it matters to an operator |
|------|-------------|--------------|-------------------------------|
| Model load/unload | Rare crash (`ggml_abort`) possible under rapid reinstall/relaunch | Serialized, crash-free under the same stress pattern | You can reinstall/upgrade without risking a hard crash mid-load |
| Notification | Static "running" notification | Live state text (Loading model… / Serving / Unavailable: reason / Stopping…) + a "Stop serving" action | You can tell *what the service is actually doing* and stop it without opening the app |
| Observability | Required ADB/logcat to see service health | On-device Diagnostics screen: state, uptime, active session, last inference, last error, thermal/eligibility | No developer tooling needed to check if the node is healthy |
| Model management | Import/delete only | Add per-model "Reverify" (re-check integrity) and "Export Inventory" (JSON via share sheet) | You can confirm a model file is still valid, or hand off an inventory snapshot without pulling files off the device |
| Upgrade safety | Not formally re-verified since RC2 | 5-cycle rapid reinstall stress test, zero duplicate loads, zero crashes | Installing an update over a running node is now a verified-safe operation |
| Cold start / manual stop | Behavior existed, not re-verified | Re-verified: stop via Settings toggle drains safely; `force-stop` + relaunch recovers correctly, confirmed live in Diagnostics | Killing and restarting the app (or the OS doing it for you) recovers cleanly |
| Physical reboot | Not tested | Not tested this cycle either (deliberately, to avoid disrupting a daily-use device) | If you rely on boot-time autostart, treat it as *likely correct but not yet directly reboot-tested* — the same code path is proven via the equivalent upgrade-restart trigger |

## Bottom line

If you were running RC2 comfortably, RC3 is a drop-in upgrade: same
models, same API, same thermal behavior — just safer under real-world
device churn (reinstalls, kills, upgrades) and easier to observe without
a laptop and a USB cable.
