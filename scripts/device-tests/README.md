# Opt-in debug Android/Mac tests

These tests target only the isolated debug apps. Install and pair Android `net.wastu.binderclip.debug` with Mac `BinderClip Debug.app`. They use private test clipboards and need no app root/background clipboard grant. Keep the Mac unlocked for any macOS Bluetooth privacy prompt. ADB access and proximity are required for Bluetooth tests. Debug Bluetooth is off by default on both platforms; explicitly enable **Bluetooth Testing (Shared Radio)** on Mac and Bluetooth fallback on Android in a dedicated acceptance setup before Bluetooth mode. Simultaneous custom-service advertising beside production can overflow macOS advertising and prevent Android discovery.

From the project root on the Mac:

```sh
export ADB="$HOME/.local/share/mise/installs/android-sdk/latest/platform-tools/adb"
export ANDROID_SERIAL="100.96.0.10:5555"
"$ADB" connect "$ANDROID_SERIAL"
JAVA_HOME="$HOME/.local/share/mise/installs/java/21.0.2" \
PATH="$HOME/.local/share/mise/installs/java/21.0.2/bin:$PATH" \
./android/gradlew -p android testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
python3 scripts/verify-debug-artifacts.py --android android/app/build
./macos/debug-install.sh
python3 scripts/verify-debug-artifacts.py --mac "$HOME/Applications/BinderClip Debug.app"
"$ADB" install -r android/app/build/outputs/apk/debug/app-debug.apk
"$ADB" install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
swiftc scripts/device-tests/clipboard.swift -o /tmp/binderclip-device-clipboard
python3 scripts/device-tests/run.py --large-text
```

Grant notifications and Nearby Devices to **debug** independently, then scan its own QR or use its `binderclip-debug://pair` invitation. Never uninstall the production package to bypass a signing mismatch. Debug group keys must be fresh; never import production keys.

`run.py` wraps `coordinator.py`. Network mode verifies encrypted text and real PNG bytes in both directions, including previously received text sent again after an image. `--bluetooth --large-text` uses root ADB only to install temporary TCP-39422 rules for debug's exact current UID, verifies large encrypted text, and removes those rules in `finally`. It never toggles the shared radio. Keep the private boards idle during a test; the helper saves/restores only the Mac debug board. Logs/report JSON go under `/tmp/binderclip-device-*` and contain synthetic test results, not pairing keys or user clipboard data.

Additional modes:

- `--image-cache --cache-seconds=600`: hold a real 3.6 MB image in the private board for ten minutes, inspect cumulative read/hash counters, then change bytes behind the same URI and verify delivery. Default observation: 70 seconds. The fixture holds its activity awake and detects clipboard replacement.
- `--manual-share`: Android's debug share target, the distinct Mac debug Services entry, and copied URL versus actionable Browser Links notification behavior.
- `--mesh` / `--lan`: assert a specific selected route. `run.py` derives the current opposite endpoints from the private debug invitation and blocks only debug's UID/port. It does not change device-wide routes or Wi-Fi.

The default helper's Services name is hardcoded to debug. It never invokes production Services. Its `system-change-count` command reads only the general Mac clipboard change count for before/after verification.

For GATT-only fallback, quit **only debug**, launch `open --env BINDERCLIP_TEST_GATT_ONLY=1 "$HOME/Applications/BinderClip Debug.app"`, and run Bluetooth mode. Restart normally afterward to restore L2CAP. This hook exists only in DEBUG builds. Ad-hoc rebuilds may require fresh macOS Bluetooth approval; a manager in state 0/authorization 0 can be waiting for an unlocked desktop and permission approval.

Android isolation/provider checks:

```sh
"$ADB" shell am instrument -w \
  -e class net.wastu.binderclip.AppIsolationDeviceTest,net.wastu.binderclip.ImageClipboardDeviceTest \
  net.wastu.binderclip.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`LifecycleDeviceTest` is opt-in with `hardwareLifecycle=true` and verifies three stop/start cycles and reauthentication. `PowerDeviceTest` is opt-in with `hardwareScreenOff=true`, checks 65 seconds of connection survival without clipboard polling, and accepts `expectedTransport=BLUETOOTH`. Turning off the physical screen affects both apps' normal power behavior; use a dedicated acceptance session for that test.

`run.py` and the routine coordinator reject `--promotion`/`--doze`, because radio cycling and forced Doze affect production. Real system-clipboard, root access, bond repair, radio/Doze, and battery tests belong on dedicated acceptance devices/sessions. The root access fixture is for a root-required production acceptance build; isolated debug intentionally blocks root probing. Preserve all production data, keys, permissions, processes, and boot scripts. See [the isolation contract](../../docs/debug-isolation.md).
