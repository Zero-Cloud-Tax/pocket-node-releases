# Pocket Node RC2 Checkpoint

Date/time: 2026-07-02, 15:18–15:25 local
Branch: main
Base HEAD: a940aab ("Harden Pocket Node thermal gating and Fold6 Vulkan build")

Dirty state before checkpoint:
- `app/src/main/cpp/CMakeLists.txt` — modified (pre-existing before Phase 1; native march
  changed from Fold6-optimized `armv8.4-a+dotprod+i8mm` to generic `armv8-a`)
- `app/src/main/cpp/pocketnode_jni.cpp` — modified (Phase 2 UTF-8 fix)
- `app/src/main/java/com/pocketnode/app/inference/ApiServer.kt` — modified (Phase 1 stream=false
  fix)
- `screen.png`, `window_dump.xml` — modified (stale UI-dump artifacts, unrelated to RC2)
- `POCKET_NODE_RC2_PHASE1_VALIDATION.md`, `POCKET_NODE_RC2_PHASE2_UTF8_VALIDATION.md` — untracked
  (Phase 1/2 evidence docs)
- ~50 other untracked files/dirs at repo root: P27 closure artifacts, audit docs, python
  scripts, `pocket-node-public-repo/`, `release-artifacts/`, a snapshot `.db`/`.db-wal` pair,
  two `.fuse_hidden*` stray files, `P20_HEALTH_AUDIT.md`, `RC1_EXECUTION_PLAN.md`,
  `RELEASE_AUDIT_SUMMARY.md`, `frontier_backlog.md`, `p20-impl/`. None of these were touched,
  deleted, or moved in this phase or in Phase 1/2.

## RC2 required fixes
- `ApiServer.kt`: Phase 1 fix — `GenerateRequest`/`ChatRequest` now carry a `stream: Boolean?`
  field; `/api/generate` and `/api/chat` branch on `stream == false` to a new
  `nonStreamResponse()` that returns one consolidated `{"response": "...", "done": true}` JSON
  object, instead of always returning per-token NDJSON regardless of the requested mode.
  Verified: `assembleDebug` PASS, `/health`/`/capabilities`/`stream=false`/`stream=true` all PASS.
- `pocketnode_jni.cpp`: Phase 2 fix — the native token callback (`emit_token` inside
  `nativeGenerate`) no longer passes raw per-token bytes straight to `NewStringUTF()`. Bytes are
  buffered in a per-generation `pending_utf8` string, only the longest valid complete UTF-8
  prefix is emitted per token, and any bytes left over at generation end are flushed (as-is if
  valid, or as U+FFFD if genuinely truncated/invalid). Fixes a `SIGABRT`/
  `JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8` crash that occurred
  whenever a multi-byte UTF-8 character (Cyrillic, CJK, accented Latin, emoji) was split across
  a BPE token boundary. Verified: `assembleDebug` PASS, four Unicode stress probes (accented
  Latin non-stream/stream, emoji+Japanese non-stream, mixed-Unicode stream) all completed with
  no crash and no `NewStringUTF`/JNI abort in logcat.

## Evidence docs
- `POCKET_NODE_RC2_PHASE1_VALIDATION.md`: Phase 1 build/install/API-compatibility validation —
  verdict CONDITIONAL (all gates passed except a since-fixed `stream=false` bug and the native
  UTF-8 crash later fixed in Phase 2).
- `POCKET_NODE_RC2_PHASE2_UTF8_VALIDATION.md`: Phase 2 native UTF-8 hardening validation —
  verdict PASS.

## Dirty file classification

| File | Classification | Notes |
|---|---|---|
| `app/src/main/java/com/pocketnode/app/inference/ApiServer.kt` | RC2_REQUIRED | Phase 1 `stream=false` fix |
| `app/src/main/cpp/pocketnode_jni.cpp` | RC2_REQUIRED | Phase 2 UTF-8 buffering fix |
| `POCKET_NODE_RC2_PHASE1_VALIDATION.md` | RC2_EVIDENCE | Phase 1 validation report |
| `POCKET_NODE_RC2_PHASE2_UTF8_VALIDATION.md` | RC2_EVIDENCE | Phase 2 validation report |
| `POCKET_NODE_RC2_CHECKPOINT.md` | RC2_EVIDENCE | this document |
| `app/src/main/cpp/CMakeLists.txt` | PRE_EXISTING_NATIVE_BUILD_DECISION | March-flag change predates Phase 1; not required for the build to pass (see below); left as a separate, explicit decision |
| `screen.png` | PRE_EXISTING_P27_ARTIFACT | Stale UI-dump image, unrelated to RC2 code |
| `window_dump.xml` | PRE_EXISTING_P27_ARTIFACT | Stale UI-dump XML, unrelated to RC2 code |
| `.fuse_hidden0000001c00000001`, `.fuse_hidden0000001c00000002` | UNKNOWN_DO_NOT_TOUCH | Editor/filesystem temp lock files; origin unclear, not RC2-related, not deleted |
| `P20_HEALTH_AUDIT.md` | PRE_EXISTING_P27_ARTIFACT | Prior-phase doc |
| `RC1_EXECUTION_PLAN.md` | PRE_EXISTING_P27_ARTIFACT | Prior-phase doc |
| `RELEASE_AUDIT_SUMMARY.md` | PRE_EXISTING_P27_ARTIFACT | Prior-phase doc |
| `frontier_backlog.md` | PRE_EXISTING_P27_ARTIFACT | Prior-phase doc |
| `build_b3.log`, `dump.xml`, `gguf_meta_output.txt`, `groupB_chat_template.txt`, `pn_groupA_header.b64`, `pn_groupB_header.b64` | PRE_EXISTING_P27_ARTIFACT | Scratch/debug output files |
| `get_chat_template.py`, `get_ct.py`, `inspect_header.py`, `parse_gguf.py`, `run_bench_b0.py`, `write_finals.py`, `p27_*.py` | PRE_EXISTING_P27_ARTIFACT | P27 scratch scripts |
| `p20-impl/`, `p27_*_artifacts/`, `p27_public_proof_package/`, `p27_public_screenshot_review/`, `p27_terminal_evidence/` | PRE_EXISTING_P27_ARTIFACT | P27 closure/evidence directories |
| `pocket-node-public-repo/` | PRE_EXISTING_P27_ARTIFACT | Separate public-repo staging directory |
| `release-artifacts/` | PRE_EXISTING_P27_ARTIFACT | Prior release output directory |
| `pocketnode_snapshot_rc1_preinstall.db`, `pocketnode_snapshot_rc1_preinstall.db-wal` | PRE_EXISTING_P27_ARTIFACT | RC1 pre-install DB snapshot — a validation artifact, not touched or deleted per hard rules |

No P27 artifacts, model files, Room DB files, keystores, or secrets were staged, moved, or
deleted in this phase.

## CMakeLists.txt decision

**Exact diff summary:**
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

1. **What changed:** the native C/C++ compile flags for `libpocketnode.so` dropped
   `+dotprod+i8mm` and downgraded the target ISA baseline from `armv8.4-a` to plain `armv8-a`.
   `dotprod`/`i8mm` enable the SDOT/UDOT and SMMLA/UMMLA instructions that GGML's quantized GEMM
   kernels use for faster INT8 matrix math — this is a real performance-relevant flag, not
   cosmetic.
2. **Present before Phase 1?** Yes — confirmed identical diff already existed in the working
   tree at the start of Phase 1 (`git status --short` showed this file modified before any RC2
   work began); Phase 1 and Phase 2 did not touch this file.
3. **Required for `assembleDebug` to pass?** No. `assembleDebug` was re-verified in this phase
   with the file in its current (generic `armv8-a`) state and passed (`BUILD SUCCESSFUL`,
   native `configureCMakeDebug`/`buildCMakeDebug` tasks ran without error). It was also
   confirmed passing in Phase 1 and Phase 2 with the same flag state. The original
   `armv8.4-a+dotprod+i8mm` flags are not needed to compile — only to run faster on ISA
   extensions the Fold6's Snapdragon 8 Gen 3 happens to support.
4. **Does it improve portability?** Yes — plain `armv8-a` is the baseline ARM64 instruction set
   guaranteed on every arm64-v8a Android device; `armv8.4-a+dotprod+i8mm` requires a newer core
   (the comment being replaced explicitly calls out that pre-ARMv8.4 devices would need a
   separate build flavor). The new comment names a concrete second target device
   ("RK3576 Apolo tablet"), suggesting this is an intentional multi-device portability decision
   in progress, not an accidental regression.
5. **Does it reduce Fold6-specific optimization?** Yes, measurably. Dropping `dotprod`/`i8mm`
   removes GEMM-kernel ISA acceleration this app's own prior commit (the "B.3 patch — restored
   from RC1 tag" comment being deleted) explicitly restored for the Fold6's Snapdragon 8 Gen 3.
   No before/after token-per-second benchmark was run in this phase (long benchmarking is
   out of scope here), so the magnitude of the regression is not quantified, only its direction.
6. **Safer way to make it explicit?** Yes. The cleanest approach is a CMake/Gradle
   `abiFilters`/flavor split (exactly what the *original* comment already recommended: "create
   a separate build flavor with -march=armv8-a and set abiFilters accordingly") rather than
   overwriting the single shared flag in place. That would let the Fold6 build keep
   `armv8.4-a+dotprod+i8mm` while a second flavor targets generic/RK3576 devices with
   `armv8-a`, with both paths reviewable and testable independently. This phase does not
   implement that split — it is a build-system change beyond "smallest safe fix" scope for a
   checkpoint phase, and belongs in a deliberate follow-up.
7. **Include in RC2 checkpoint, revert, or leave uncommitted for separate decision?**
   **Leave uncommitted, for a separate decision.** It is not required for this checkpoint's
   scope (API + UTF-8 reliability fixes) and mixes a real product/performance tradeoff
   (Fold6-only optimized build vs. multi-device generic build) into what should be a narrow,
   reviewable RC2 stability commit.

**Recommendation:** For the RC2 checkpoint itself, prefer stable generic `armv8-a` as the
*default* posture only once it is made an explicit, deliberate choice — but do not fold it into
*this* commit silently. Concretely:
- Do not commit `CMakeLists.txt` as part of this checkpoint.
- Before RC3, make the choice explicit via one of: (a) commit the generic-`armv8-a` change on
  its own with a commit message that states the tradeoff and names the second target device, or
  (b) implement the abiFilters/flavor split so the Fold6 build keeps
  `armv8.4-a+dotprod+i8mm` and a second flavor covers generic ARM64, or (c) revert to
  `armv8.4-a+dotprod+i8mm` if Fold6-only support is still RC2's actual target and the
  RK3576 comment was exploratory/premature.
- Given this mission is specifically "Pocket Node ... Fold6 Vulkan build" (per the base HEAD's
  own commit message), option (b) is the safer long-term default, but that is a product
  decision for the repo owner, not something to resolve inside a checkpoint commit.

Include in RC2 checkpoint: **no**
Reason: pre-existing, unrelated to the API/UTF-8 stability fixes this checkpoint closes, not
required for the build to pass, and represents a real performance/portability tradeoff that
deserves its own explicit, reviewable commit rather than being bundled invisibly into an
RC2 reliability checkpoint.

## Build verification
assembleDebug: **PASS** — `BUILD SUCCESSFUL in 59s`, native `configureCMakeDebug[arm64-v8a]` /
`buildCMakeDebug[arm64-v8a]` tasks ran with the tree in its current state (i.e. with the
generic-`armv8-a` `CMakeLists.txt` left uncommitted/unchanged, plus the Phase 1/2 fixes).

## Optional device smoke
Device: Samsung SM-F956U (Galaxy Z Fold 6), serial RFCX60BRDWA
Install: not re-installed in this phase — the Phase 2 debug build (pid 23363) was still running
and healthy, so a redundant reinstall was skipped per "do not run long benchmarks/unneeded
churn"; confirmed live instead via `pidof` (23363, same pid as end of Phase 2) and a fresh
`/health` + `/capabilities` call.
Health: `{"status":"ok","node":"pocket-node","device":"android","model_loaded":true,"uptime_ms":149091}`
Capabilities: `eligible_for_inference: true`, `model_loaded: true`, `service_alive: true`,
`thermal_status: "none"`, `last_inference_at` populated from a prior Phase 2 request — all
RC2-required fields present, no regression.

## Checkpoint action
Commit created: yes
Commit SHA if yes: 2438369
Files included:
- `app/src/main/java/com/pocketnode/app/inference/ApiServer.kt`
- `app/src/main/cpp/pocketnode_jni.cpp`
- `POCKET_NODE_RC2_PHASE1_VALIDATION.md`
- `POCKET_NODE_RC2_PHASE2_UTF8_VALIDATION.md`
- `POCKET_NODE_RC2_CHECKPOINT.md`

Files deliberately excluded:
- `app/src/main/cpp/CMakeLists.txt` (pre-existing native build/architecture decision — see
  above; needs its own explicit commit/decision, not bundled here)
- `screen.png`, `window_dump.xml` (stale, unrelated UI-dump artifacts)
- All P27 artifacts, scratch scripts, audit docs, `pocket-node-public-repo/`,
  `release-artifacts/`, the RC1 pre-install DB snapshot pair, and the two `.fuse_hidden*` files
  (pre-existing, unrelated to RC2, not touched or deleted)

## Next action
Proceed to P28 Phase 3 thermal/routing proof only after this checkpoint is clean. Before that,
resolve the `CMakeLists.txt` architecture-flag decision explicitly (see recommendation above) —
Phase 3 thermal work should be judged against the actual, deliberately-chosen production
architecture flag, not against an ambiguous silently-uncommitted change.
