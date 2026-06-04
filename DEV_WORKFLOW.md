# DEV_WORKFLOW.md

Commands for day-to-day Pocket Node development on Windows.

## Build debug APK

```powershell
.\gradlew.bat :app:assembleDebug
```

## Install debug APK

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Launch app with adb

```powershell
adb shell am start -n com.pocketnode.app/.MainActivity
```

## Run tests

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Optional device-connected instrumentation tests, if the project later adds them:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Use scrcpy for device mirroring

```powershell
scrcpy
```

If multiple devices are connected:

```powershell
adb devices
scrcpy -s <device-id>
```

## Useful notes

- Run commands from the repo root: `C:\Users\Rhear\Pocket Node`
- The app id is `com.pocketnode.app`
- The Android module is `app`
- Keep changes offline-first and avoid adding cloud dependencies

