# AGENTS.md

Repo instructions for coding agents working on Pocket Node.

## Scope

- Android app source lives under `app/`.
- The repo also contains a small Node CLI in `cli/` and static landing-page assets in `landing/`, but the primary product is the Android app.
- Treat `:app` as the only Gradle module.

## Hard Rules

- Do not modify Kotlin source files unless the user explicitly requests source changes.
- Do not add cloud dependencies, analytics, crash reporting, or hosted backend integrations.
- Keep the app offline-first and local-first.
- Preserve the current `applicationId`: `com.pocketnode.app`.
- Do not overwrite or revert unrelated user changes in the worktree.

## Architecture Notes

- UI: Jetpack Compose and Material 3.
- State and async work: Kotlin coroutines and Flow.
- Storage: Room plus DataStore preferences.
- Local inference: JNI bridge in `app/src/main/cpp` and Kotlin inference code in `app/src/main/java/com/pocketnode/app/inference`.
- Native build: CMake/NDK, with arm64-v8a as the configured ABI filter.

## Working Practices

- Inspect the current repo state before editing.
- Prefer small, targeted changes.
- If a change affects build, install, or test behavior, update docs in the same patch.
- Use Gradle tasks from the repository root.
- Validate changes with the smallest relevant build or test command.
- Avoid destructive git operations.

## Verification

- For docs-only changes, verify by reviewing the generated diff.
- For app changes, prefer `:app:assembleDebug` and `:app:testDebugUnitTest` before handing work back.
- If native code changes are involved, also verify the CMake/native build path.

