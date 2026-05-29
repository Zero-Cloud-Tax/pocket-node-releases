# Pocket Node — Release Checklist

## Pre-build environment

- [ ] Set `POCKETNODE_OPERATOR_URL` env var (or `pocketnode.operator.url` Gradle property) to the real operator model URL
- [ ] Set `POCKETNODE_PRO_HMAC_SECRET` to the real HMAC secret (not the dev fallback)
- [ ] Set `POCKETNODE_PURCHASE_URL` to the real purchase URL
- [ ] Set keystore credentials in `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword)
- [ ] Confirm no SmolLM2 test URL appears in release build:
  ```
  ./gradlew assembleRelease
  grep -r "SmolLM2" app/build/
  ```
  Should return nothing.
- [ ] Set real SHA-256 in `OPERATOR_SPEC.expectedSha256` if the operator model hash is known

## Build

- [ ] `./gradlew clean assembleRelease`
- [ ] Build succeeds with no R8 errors
- [ ] APK is signed (verify with `apksigner verify app/build/outputs/apk/release/app-release.apk`)

## Signing note

If `keystore.properties` is absent, `assembleRelease` produces an **unsigned APK**.
The app will install via `adb install` but cannot be distributed through Play Store unsigned.

## Install and smoke test

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

### First-run — no model installed

- [ ] App opens to "Welcome / Model Setup Required" screen
- [ ] "Download / Browse Models" navigates to Model Hub
- [ ] "Import GGUF from Device" opens file picker
- [ ] If `POCKETNODE_OPERATOR_URL` is set: "Download PocketNode Operator" button is visible
- [ ] If `POCKETNODE_OPERATOR_URL` is not set: OperatorDownloadCard shows "Source not configured", no active download button

### First-run — download flow

- [ ] Tap "Download PocketNode Operator" — progress bar updates inline on Welcome screen
- [ ] `.part` file appears in `/sdcard/Android/data/com.pocketnode.app/files/models/` during download
- [ ] Cancel download — `.part` file is deleted, no `.gguf` appears
- [ ] Restart download — progress resumes from zero
- [ ] Download completes — `.part` renames to `.gguf`
- [ ] Hashing runs — badge shows `Unknown Hash` (not `Verified` unless `expectedSha256` is set)
- [ ] Screen transitions to "Recommended for this device"

### First-run — model present at startup

- [ ] Clear app data, place a `.gguf` in the models directory manually
- [ ] App opens to "Recommended for this device" (skips Model Missing state)
- [ ] Tap "Apply Recommended Settings" — navigates to Chat with model loaded

### Chat smoke test

- [ ] Model loads without error
- [ ] Send a message — response streams token by token
- [ ] TPS counter visible in backend badge
- [ ] Copy button on assistant bubble copies to clipboard
- [ ] Stop generation button works mid-stream
- [ ] Empty chat state shows 4 suggestion chips

### Model Hub

- [ ] Storage header shows correct model count, used and free bytes, path
- [ ] Rescan FAB removes stale DB records; does not duplicate existing models
- [ ] No `.part` files appear as installed models
- [ ] Delete model — confirmation dialog; file and DB record removed; storage stats update
- [ ] Import GGUF from file picker — extension validation rejects non-GGUF
- [ ] Import duplicate — renamed to `model (1).gguf`, both appear separately

### Download cancel and retry

- [ ] Start operator download, cancel mid-way
- [ ] Confirm `.part` file is deleted after cancel
- [ ] Retry — download starts fresh

### Partial file cleanup

- [ ] Manually create a `.part` file older than 12 hours in the models dir
- [ ] Tap Rescan — `.part` file is deleted, does not appear as a model

### File guard (corrupt / tiny file)

- [ ] Create a file `tiny.gguf` with < 10 MB content
- [ ] Select it in Gallery — error "Model file appears corrupted or too small"
- [ ] Create a file `download.gguf.part`
- [ ] Attempt to load it — blocked with "Incomplete download detected"

### Background / foreground during generation

- [ ] Start generation, press Home — app moves to background
- [ ] Return to app — generation continues or has completed cleanly
- [ ] No crash on resume

### Fold / unfold during chat (Samsung Galaxy Z Fold 6)

- [ ] Send a message with the phone unfolded
- [ ] Fold the phone mid-generation — no crash
- [ ] Unfold — UI state is correct

## Post-release

- [ ] Tag release commit: `git tag v0.1.0-rc1`
- [ ] Upload signed APK to distribution channel
- [ ] Verify production operator URL serves the correct file
