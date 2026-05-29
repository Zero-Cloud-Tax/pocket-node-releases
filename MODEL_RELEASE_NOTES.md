# PocketNode Operator — Model Release Notes

## Canonical model file

| Field       | Value                                                              |
|-------------|---------------------------------------------------------------------|
| Filename    | `PocketNode_Operator_Q4_0.gguf`                                     |
| Size        | 1,805,819,328 bytes (1.68 GB)                                       |
| SHA-256     | `b1de55dff5815fc0dd898491295b064e7fea07368d603c82740288f8d3bb50ba` |
| Date hashed | 2026-05-29                                                          |
| Source      | Pulled from Galaxy Z Fold 6 (SM-F956U) via ADB                     |

This hash is registered in `HashUtils.KNOWN_HASHES["PocketNode_Operator_Q4_0"]`.
When a downloaded copy matches this hash, the UI shows **Verified**. All other files show **Unknown Hash**.

---

## Hosting the model (required for in-app download)

The production URL must be set via `POCKETNODE_OPERATOR_URL` before a release build.
Release builds with no URL configured show "Source not configured" — safe, but download is disabled.

### Option A — Hugging Face (recommended)

1. Create a model repo at huggingface.co (can be private)
2. Upload `release-assets/PocketNode_Operator_Q4_0.gguf`
3. Copy the direct resolve URL:
   ```
   https://huggingface.co/<USERNAME>/<REPO>/resolve/main/PocketNode_Operator_Q4_0.gguf
   ```
4. Set the URL and build:
   ```powershell
   $env:POCKETNODE_OPERATOR_URL = "https://huggingface.co/..."
   ./gradlew clean assembleRelease
   ```

### Option B — GitHub Release asset

1. Create a GitHub Release on any repo
2. Attach `PocketNode_Operator_Q4_0.gguf` as a release asset
3. Use the direct asset URL (ends in `/releases/download/.../*.gguf`)
4. Set `POCKETNODE_OPERATOR_URL` as above

### Option C — Private HTTPS server

Any direct HTTPS link to the raw file works.
Ensure the server sends `Content-Length` so progress tracking works correctly.

---

## Updating the known hash

If the model file is ever replaced or retrained, recompute the hash:

```powershell
Get-FileHash release-assets/PocketNode_Operator_Q4_0.gguf -Algorithm SHA256
```

Then update `HashUtils.KNOWN_HASHES` in:
`app/src/main/java/com/pocketnode/app/data/HashUtils.kt`

Do **not** use placeholder or guessed hashes. Unknown Hash is the correct badge for unverified files.

---

## OPERATOR_SPEC configuration

`app/src/main/java/com/pocketnode/app/data/OperatorDownloadSpec.kt`

The `expectedSha256` field in `OPERATOR_SPEC` is currently `null` — verification happens
post-download via `hashModelIfNeeded` comparing against `KNOWN_HASHES`. No change needed here
unless you want a download-time hash check before the file is registered.
