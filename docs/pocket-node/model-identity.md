# Pocket Node Model Identity Policy

This documents how Pocket Node establishes model trust, and clarifies a naming
distinction that has been flagged in past audits as a potential mismatch bug.
It is not a bug — it is expected behavior of the verification design.

## The four names a model can have

| Name | Source | Purpose |
|---|---|---|
| Filename / hash-registry key | On-disk filename, e.g. `PocketNode_SmolLM3_Q4_0_Fresh`, `PocketNode_Operator_Q4_0` | **Verification identity.** This is the key used to look up the expected SHA-256 in `HashUtils.KNOWN_HASHES`. |
| SHA-256 | Computed from file bytes at import time (`HashUtils.sha256`, `ModelArtifactManager.kt`) | **Trust anchor.** The only thing that determines whether a model is `VERIFIED`. |
| GGUF internal `general.name` (and `general.architecture` / `general.basename`) | Parsed from the GGUF header metadata (`GgufInspector.kt`) | **Descriptive metadata only.** Set by whoever produced/converted the GGUF upstream (e.g. llama.cpp conversion scripts). Not used as a verification key. |
| App display name | `LocalModel.name`, UI copy (`ModelsScreen.kt`, etc.) | **User-facing label only.** May echo the filename or a friendlier variant; carries no trust semantics. |

## Verification logic (source of truth)

`ModelArtifactManager.verificationStatusFor()` ([app/src/main/java/com/pocketnode/app/data/ModelArtifactManager.kt](../../app/src/main/java/com/pocketnode/app/data/ModelArtifactManager.kt)):

```kotlin
private fun verificationStatusFor(displayName: String, sha256: String, expectedSha256: String?): String {
    val knownHash = expectedSha256 ?: HashUtils.KNOWN_HASHES[displayName]
    return when {
        knownHash == null -> VerificationStatus.UNKNOWN_HASH
        knownHash.equals(sha256, ignoreCase = true) -> VerificationStatus.VERIFIED
        else -> VerificationStatus.FAILED
    }
}
```

This looks up the expected hash by **filename/display key**, then compares it
against the **computed SHA-256** of the actual file bytes. GGUF `general.name`
never enters this comparison — `GgufInspector` output (`generalName`,
`generalArchitecture`, `generalBasename`) is used elsewhere for prompt-template
resolution and draft-family detection, not for trust decisions.

## Room verification state

`LocalModel.verificationStatus` ([app/src/main/java/com/pocketnode/app/data/model/LocalModel.kt](../../app/src/main/java/com/pocketnode/app/data/model/LocalModel.kt))
persists one of the `VerificationStatus` constants
([app/src/main/java/com/pocketnode/app/data/VerificationStatus.kt](../../app/src/main/java/com/pocketnode/app/data/VerificationStatus.kt)):
`NOT_CHECKED`, `HASHING`, `VERIFIED`, `UNKNOWN_HASH`, `FAILED`. Only `VERIFIED`
drives the "Verified" badge shown in the model list / diagnostics UI.

## Why filename vs. `general.name` divergence is expected, not a bug

The hash registry key `PocketNode_SmolLM3_Q4_0_Fresh` is a filename chosen at
packaging time. The same GGUF's internal `general.name` metadata field may
read something else entirely (e.g. a base-model name set by the original
conversion tooling upstream, unrelated to the Pocket Node distribution name).
**This divergence does not affect verification** — a model with a mismatched
`general.name` still shows `VERIFIED` as long as its filename maps to a known
registry entry and its computed SHA-256 matches.

A future audit encountering "filename says X, GGUF metadata says Y" for a
`VERIFIED` model should treat this as expected packaging behavior, not a
verification defect. If the concern is a *fraudulent* or tampered artifact,
the correct signal to check is `verificationStatus == FAILED` or
`UNKNOWN_HASH`, not the `general.name` field.

## Summary

- **Filename/hash-registry key** = verification identity (lookup key).
- **SHA-256** = trust anchor (the actual proof).
- **GGUF `general.name`** = descriptive metadata only, not a trust signal.
- **App display name** = user-facing copy only.
- **Room `verificationStatus`** = the single source of truth for the UI's trusted/untrusted state.
- A filename/display-name vs. `general.name` mismatch is not, by itself, a verification failure.
