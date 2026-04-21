# Session Context

Last updated: 2026-04-21

## Goal

Keep a persistent local summary of our work so the conversation context can be restored after chat history is cleared.

## Project

- Workspace: `d:\VS Code\web-viewer`
- App type: Android app
- Application ID: `com.webviewer.app`
- Launcher activity: `com.webviewer.app/.SetupActivity`

## What We Already Did

### Disk cleanup on Windows

- Removed `GitKraken`
- Removed `WebStorm`
- Removed JetBrains leftovers except `DataGrip`
- Kept `DataGrip`
- One protected Rider leftover in `C:\Program Files\JetBrains\Rider` required admin cleanup command

### Android USB debugging

- Found Google Android USB driver in:
  - `D:\serge\Documents\usb_driver_r13-windows\usb_driver`
- Confirmed main INF:
  - `android_winusb.inf`
- Driver package was installed successfully from an elevated shell
- `adb` was not in `PATH`
- Found working `adb.exe` here:
  - `C:\Program Files\Aiseesoft Studio\FoneLab for Android\adb\adb.exe`

### Physical device debugging

- Connected Android device was detected over ADB
- Built debug APK
- Installed app manually with `adb install -r`
- Started app with:
  - `adb shell am start -n com.webviewer.app/.SetupActivity`

### Emulator setup

- SDK path from `local.properties`:
  - `D:\Android\UserLocalAndroid\Sdk`
- Existing AVD:
  - `Pixel_API_34`
- Emulator originally failed because the system image folder was incomplete and missing `kernel-ranchu`
- Installed Android command-line tools into:
  - `D:\Android\UserLocalAndroid\Sdk\cmdline-tools\latest`
- Reinstalled system image:
  - `system-images;android-34;google_apis;x86_64`
- Started emulator successfully
- Emulator ADB target:
  - `emulator-5554`
- Existing app on emulator had incompatible signature, so it was uninstalled
- Installed fresh debug APK on emulator
- Started app successfully on emulator

## Useful Commands

### Build debug APK

```powershell
./gradlew.bat assembleDebug
```

### Install and launch on emulator

```powershell
& "C:\Program Files\Aiseesoft Studio\FoneLab for Android\adb\adb.exe" -s emulator-5554 install -r "D:\VS Code\web-viewer\app\build\outputs\apk\debug\app-debug.apk"
& "C:\Program Files\Aiseesoft Studio\FoneLab for Android\adb\adb.exe" -s emulator-5554 shell am start -n com.webviewer.app/.SetupActivity
```

### Logcat for the app

```powershell
& "C:\Program Files\Aiseesoft Studio\FoneLab for Android\adb\adb.exe" -s emulator-5554 logcat | Select-String "com.webviewer.app"
```

## Notes

- `gradlew installDebug` was unreliable in this environment because Android tooling tried to use restricted sandbox profile paths such as `C:\Users\CodexSandboxOffline\.android`
- Manual install through the working external `adb.exe` succeeded
- If chat history gets cleared again, this file should be the first thing to read
