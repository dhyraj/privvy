---
name: android-ui-test
description: Build, launch, interact with, and verify the Privvy Android app UI on an emulator or device. Use whenever the user asks to test UI behavior, validate a screen, write/replay Maestro flows, take screenshots, inspect the view hierarchy, or run end-to-end checks on Android.
---

# Android UI Testing

Privvy app facts (verified in this repo):

| Item | Value |
| --- | --- |
| applicationId | `com.privvy.app` |
| Launcher activity | `com.privvy.app/.ui.disguise.DisguiseActivity` |
| Built APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Build + install | `./gradlew installDebug` |
| Build only | `./gradlew assembleDebug` |
| AVD available | `Pixel_8a_API_33_Android_13_Tiramisu` |
| Android SDK | `$HOME/Library/Android/sdk` |
| Maestro | `$HOME/.maestro/bin/maestro` (v2.12.1, MCP-capable) |

> **FLAG_SECURE caveat:** Privvy sets `FLAG_SECURE` on all windows (anti-forensics,
> spec §5.3). `adb screencap` / Maestro screenshots of the Privvy window will be
> **black**. That is expected, not a crash. Use the view hierarchy
> (`uiautomator dump` or Maestro hierarchy inspection) for assertions. For visual
> checks, temporarily disable FLAG_SECURE in a `BuildFlags.DEBUG` build.

## 1. Verify a device/emulator is connected

```sh
"$HOME/Library/Android/sdk/platform-tools/adb" devices
"$HOME/Library/Android/sdk/platform-tools/adb" shell getprop sys.boot_completed
```

Or use the Maestro MCP tool `list_devices`. If nothing is connected, start the AVD:

```sh
"$HOME/Library/Android/sdk/emulator/emulator" -avd Pixel_8a_API_33_Android_13_Tiramisu \
  -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
```

Wait for `sys.boot_completed = 1`.

## 2. Build and install the debug APK

```sh
./gradlew installDebug
```

`installDebug` builds and installs in one step. Re-run after every code change.
If install fails with a signature mismatch: `adb uninstall com.privvy.app` then retry.

## 3. Launch the app by its real package name

```sh
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
$ADB shell am force-stop com.privvy.app          # clean state (also tests FR-08 lock)
$ADB shell am start -n com.privvy.app/.ui.disguise.DisguiseActivity
$ADB shell pidof com.privvy.app                  # must return a pid; empty = crashed
```

Or via Maestro MCP: launch app `com.privvy.app`, or `maestro test` a flow that
starts with `appId: com.privvy.app` and `- launchApp` (use `clearState: true` for a
fresh install state).

## 4. Inspect the UI before and after interactions

Before tapping anything, capture the current state, then interact, then capture again:

```sh
# View hierarchy (works with FLAG_SECURE)
$ADB shell uiautomator dump /sdcard/ui.xml && $ADB shell cat /sdcard/ui.xml

# Screenshot (black for FLAG_SECURE windows — see caveat)
$ADB exec-out screencap -p > /tmp/privvy-before.png

# Interact: coordinates from the hierarchy bounds, not guesswork
$ADB shell input tap <x> <y>
$ADB shell input text "123"
$ADB shell input keyevent KEYCODE_BACK

# Verify after
$ADB shell uiautomator dump /sdcard/ui-after.xml && $ADB shell cat /sdcard/ui-after.xml
$ADB shell "logcat -d | grep -E 'Privvy|AndroidRuntime' | tail -50"
$ADB shell pidof com.privvy.app
```

With Maestro MCP, use the equivalent tools: inspect view hierarchy → tap →
inspect hierarchy again → take screenshot; assert text in the hierarchy.

Open screenshots with the `read` tool to view them (it renders images).

## 5. Save passing flows as reusable Maestro YAML

Put flows under `.maestro/flows/`, one scenario per file. Example
(`.maestro/flows/disguise-calculator.yaml`):

```yaml
appId: com.privvy.app
name: Disguise calculator works
---
- launchApp:
    clearState: true
- assertVisible: "0"
- tapOn: "7"
- assertVisible: "7"
- tapOn: "9"
- assertVisible: "79"
- tapOn: "C"
- assertVisible: "0"
```

Run a flow:

```sh
export PATH="$HOME/.maestro/bin:$PATH"
maestro test .maestro/flows/disguise-calculator.yaml
```

Keep flows deterministic: `clearState: true`, avoid fixed sleeps, assert on
stable text/test tags, and never assert on Privvy's hidden internals from UI
tests (use logcat for native state such as `PrivvyIO` messages).

## 6. Report results

For every run record: command, pass/fail, failing step + visible text,
screenshot/hierarchy paths, and `pidof` result. Append a row to the
**Verification Log** in `progress.md` when the run validates a milestone.

## Instrumented Compose tests (complementary)

Unit-style UI tests live in `app/src/androidTest/` and run on-device:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Prefer these for committed regressions; use Maestro for end-to-end flows and
MCP-driven exploratory testing.
