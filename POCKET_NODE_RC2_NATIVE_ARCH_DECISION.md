# Pocket Node RC2 Native Architecture Decision

Date/time: 2026-07-02, 15:30–15:40 local
Branch: main
Base commit: 2438369 ("P28: close Pocket Node RC2 API and UTF-8 stability gates")
Working tree state before decision: `app/src/main/cpp/CMakeLists.txt` modified (uncommitted,
pre-dating Phase 1); `POCKET_NODE_RC2_CHECKPOINT.md` had one small uncommitted post-commit edit
(SHA fill-in, unrelated to this decision); `screen.png`, `window_dump.xml` modified; ~50
untracked P27 artifacts, `pocket-node-public-repo/`, `release-artifacts/`, RC1 DB snapshot
pair, and `.fuse_hidden*` files present. None of the latter were touched in this phase.

## Problem
`app/src/main/cpp/CMakeLists.txt` carried an uncommitted change (present before P28 Phase 1
started) that replaced the Fold6-optimized compiler flags with a generic ARM64 baseline:

```diff
-# Snapdragon 8 Gen 3 / ARMv8.4-A performance build (B.3 patch — restored from RC1 tag)
-# Enables dotprod (SDOT/UDOT) and i8mm (SMMLA/UMMLA) ISA extensions used by GGML GEMM kernels.
-# This APK targets arm64-v8a and is built for the Samsung Galaxy Z Fold 6 (SM-F956U).
-# If you need a generic arm64 build that runs on pre-ARMv8.4 devices, create a separate
-# build flavor with -march=armv8-a and set abiFilters accordingly.
-# Validated device: Snapdragon 8 Gen 3, Android 16 — dotprod and i8mm are guaranteed present.
-set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -DNDEBUG -march=armv8.4-a+dotprod+i8mm")
-set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -march=armv8.4-a+dotprod+i8mm")
+# Generic ARMv8-A compatibility build (for RK3576 Apolo tablet and generic ARM64 devices)
+# If you need to restrict build to Snapdragon 8 Gen 3 ARMv8.4-A, change back to -march=armv8.4-a+dotprod+i8mm.
+set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -DNDEBUG -march=armv8-a")
+set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -march=armv8-a")
```

This matters because `dotprod`/`i8mm` gate real GGML GEMM-kernel ISA acceleration (SDOT/UDOT,
SMMLA/UMMLA) used by the quantized matrix math this app's inference path runs on every token.
Every RC2 validation phase to date (Phase 1 build/API checks, Phase 2 UTF-8 stress testing, the
2.5 checkpoint smoke) targeted the Samsung Z Fold6 (SM-F956U, Snapdragon 8 Gen 3) exclusively —
no RK3576 or other second device was inspected, built for, or tested against anywhere in this
repo's P28 work. Leaving the generic-ARM64 change in place, uncommitted, would silently change
the actual production architecture RC2 is being validated against without that ever being a
deliberate, reviewed decision — the exact risk flagged and deferred in Phase 2.5.

Only one `CMakeLists.txt` exists in the project (`app/src/main/cpp/CMakeLists.txt`), and
`app/build.gradle.kts` defines no `abiFilters` split or product flavors — this is a single
shared native build target, not a multi-flavor setup, so this change affects the one and only
APK variant produced by `assembleDebug`/`assembleRelease`.

## Options considered
**A. Fold6-optimized RC2 baseline** — revert to `-march=armv8.4-a+dotprod+i8mm`. Matches every
device RC2 has actually been validated on; no portability work is discarded because none has
been built or tested yet, only a comment referencing a possible future target.

**B. Generic ARM64 baseline** — keep `-march=armv8-a` as a deliberate, documented choice. Would
require treating RC2 as intentionally redefined toward multi-device portability, which no other
evidence in the repo (build.gradle flavors, second-device build/test artifacts, RC2 mission
docs) supports.

**C. Build flavor split** — add a second CMake/Gradle flavor so Fold6 keeps the optimized flags
while a second flavor targets generic ARM64. Technically the "correct" long-term shape (and
exactly what the original, now-deleted comment recommended), but requires Gradle
product-flavor/`externalNativeBuild` wiring changes beyond a single-file flag edit — out of
scope for "smallest safe fix" in a baseline-decision phase, and would need its own validation
pass (build both flavors, confirm `abiFilters`/packaging correctness) that this phase's rules
(no long builds/benchmarks beyond a single `assembleDebug` + smoke) don't allow time for.

## Decision
Chosen option: **A — Fold6-optimized RC2 baseline**

Reason: RC2's actual validated target device, in every phase of this project so far, is the
Samsung Z Fold6. No second device has been built for or tested against. The generic-ARM64
change was an uncommitted, undocumented, in-progress edit with no corresponding build/test
evidence for its stated target (RK3576 Apolo tablet) anywhere in the repo. Per the standing
guidance for this phase, Option A is used because inspection did not disprove the assumption —
if anything it reinforced it: every prior P28 validation artifact (Phase 1, Phase 2, the 2.5
checkpoint) was produced and verified exclusively against the Fold6.

## Exact CMake change
Before this decision (working tree, uncommitted): `-march=armv8-a` (generic ARMv8-A, no
dotprod/i8mm), with a comment naming the RK3576 Apolo tablet as the rationale.

After this decision (restored via `git checkout HEAD -- app/src/main/cpp/CMakeLists.txt`,
matching the blob already committed at HEAD `2438369` and unchanged since before Phase 1):
`-march=armv8.4-a+dotprod+i8mm` (Snapdragon 8 Gen 3 / ARMv8.4-A, dotprod + i8mm ISA
extensions enabled for GGML GEMM kernels), with the original comment block restored, including
its own note that a generic-ARM64 build should be "a separate build flavor" rather than an
in-place flag swap — i.e. Option C's shape, left for a dedicated later phase.

## Build verification
assembleDebug: **PASS** — `BUILD SUCCESSFUL in 1m 23s` with the restored
`-march=armv8.4-a+dotprod+i8mm` flags; `configureCMakeDebug[arm64-v8a]` /
`buildCMakeDebug[arm64-v8a]` re-ran and compiled cleanly (one pre-existing, unrelated
`#pragma message` warning from llama.cpp's `ggml-opencl.cpp` about unimplemented quant-type
support — not related to this change).

## Optional Fold6 smoke
Device: Samsung SM-F956U (Galaxy Z Fold 6), serial RFCX60BRDWA
Install: `adb -s RFCX60BRDWA install -r app/build/outputs/apk/debug/app-debug.apk` →
`Success` (streamed install, upgrade over the existing app; model/app data preserved, no
uninstall performed)
Health: `{"status":"ok","node":"pocket-node","device":"android","model_loaded":true,"uptime_ms":18977}`
Capabilities: `eligible_for_inference: true`, `model_loaded: true`, `service_alive: true`,
`thermal_status: "none"` — a transient thermal hard-block was observed immediately after
install (residual heat from the repeated build/install/launch cycles across recent phases,
`peak_thermal_zone_c` briefly ~86°C), which cleared on its own within the normal polling
interval; no thermal test was run, this was passive observation while waiting for model load.

## Files changed
- `app/src/main/cpp/CMakeLists.txt` — reverted to the Fold6-optimized
  `-march=armv8.4-a+dotprod+i8mm` flags (restores the blob already present at HEAD `2438369`,
  i.e. this file is no longer dirty relative to that commit).
- `POCKET_NODE_RC2_NATIVE_ARCH_DECISION.md` — this document (new).

No other files were modified. `screen.png`, `window_dump.xml`, all P27 artifacts,
`pocket-node-public-repo/`, `release-artifacts/`, the RC1 DB snapshot pair, and `.fuse_hidden*`
files were left exactly as found.

## Commit
Commit created: yes
Commit SHA: 6db3667
Commit message: `P28: restore Fold6 native optimization baseline for RC2`

## Follow-up
Generic ARM64 / RK3576 Apolo tablet support should be handled as a later, explicit build-flavor
or portability phase (Option C above) — e.g. a Gradle product flavor plus a second
`externalNativeBuild`/CMake configuration that keeps `armv8.4-a+dotprod+i8mm` for the Fold6
flavor and adds `armv8-a` for a generic-ARM64 flavor, each with `abiFilters` and its own build
verification. Do not resurrect the in-place flag swap without that structure — it silently
changes the single shared APK's target architecture for every device, not just the intended new
one.
