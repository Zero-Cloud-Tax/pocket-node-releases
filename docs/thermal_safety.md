# Pocket Node — Thermal Safety Documentation

---

## The Problem: Android PowerManager API Gap

Android exposes a thermal status API via `PowerManager.getCurrentThermalStatus()`.
The documented return values are: `none`, `light`, `moderate`, `severe`, `critical`,
`emergency`, `shutdown`.

During Pocket Node validation, `thermal_status` returned `"none"` while raw thermal
sensor readings showed the device at **76.8°C** during a sustained inference load.

This is not a bug in Pocket Node. It is a limitation of the Android thermal API:
the PowerManager abstraction is too coarse and too slow to reflect real hardware
sensor state in real time at the granularity needed for inference gating.

**Do not use `thermal_status` from the `/capabilities` endpoint as a routing
signal. It will report `"none"` when the device is actually hot.**

---

## The Fix: Raw Thermal Zone Polling

The gate reads raw thermal zone temperatures directly from the Linux kernel's
thermal subsystem via the Fold6's `/capabilities` endpoint:

```
/sys/class/thermal/thermal_zone*/temp
```

On the Galaxy Z Fold6, there are **81 readable thermal zones**. The capabilities
endpoint polls all readable zones, returns the peak value, and reports the zone
type that hit the peak.

The `/capabilities` response includes:

```json
{
  "thermal_status": "none",            <- PowerManager API (do not use)
  "peak_thermal_zone_c": 37.0,        <- raw sysfs poll (use this)
  "peak_thermal_zone_type": "pmr735d_tz",
  "peak_cpu_zone_c": 36.6,
  "peak_gpu_zone_c": 34.8,
  "thermal_zone_readable_count": 81,
  "thermal_zone_error_count": 0,
  "eligible_for_inference": true
}
```

The gate uses `peak_thermal_zone_c` as the authoritative temperature signal.
If `peak_thermal_zone_c >= 65.0`, the gate sets `eligible_for_inference = false`
and returns `bypass_thermal`.

---

## Validated Thermal Event

During the P27 60-minute soak:

1. `peak_thermal_zone_c` rose to **76.8°C**.
2. `thermal_status` simultaneously reported `"none"` (PowerManager API gap confirmed).
3. The gate detected the spike via `peak_thermal_zone_c` and issued `bypass_thermal`.
4. All inference requests during the block were routed to Mac Studio (Tier 2).
5. The phone was never sent an inference request during the thermal event.
6. After cooldown, the gate re-polled and automatically restored `allow_fold6`.
7. No manual intervention was required.

---

## Thermal Threshold

| Parameter | Value | Notes |
|-----------|-------|-------|
| Bypass trigger | ≥ 65°C | `peak_thermal_zone_c` from sysfs |
| Recovery | automatic | Gate re-polls on next request |
| Recovery margin | no hysteresis implemented | Gate re-enables as soon as temp drops below threshold |
| Measured spike | 76.8°C | Single event during 60-min soak |

A hysteresis band (e.g., require cooldown to < 55°C before re-enabling) would
reduce oscillation around the threshold. This is deferred to a future iteration.

---

## Stability Caveat

Raw sysfs thermal zone polling is **not a public stable Android API**. The zone
names, count, and availability depend on the kernel version and device. The
approach that works on a Galaxy Z Fold6 with Ollama RC2 may require adaptation
on other Android devices.

---

## What This Means for Builders

If you replicate this architecture on a different Android device:

1. Do not rely on `PowerManager.getCurrentThermalStatus()` for real-time gating.
2. Poll `/sys/class/thermal/thermal_zone*/temp` directly.
3. Count readable zones — the number will differ by device.
4. Tune the threshold for your device's thermal characteristics.
5. Test with a real sustained load, not a synthetic spike.
