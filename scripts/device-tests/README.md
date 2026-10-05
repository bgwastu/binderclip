# Opt-in physical Android/Mac tests

Requires the updated apps paired together, BinderClip root permission, Android background clipboard permission, and ADB access. Use a disposable clipboard: the tests replace it with fixed test text and real PNGs and attempt to restore it afterward. Live automatic sync and providers can affect restoration. These tests require a nearby Mac for Bluetooth.

From the project root on the Mac:

```sh
export ADB="$HOME/.local/share/mise/installs/android-sdk/latest/platform-tools/adb"
export ANDROID_SERIAL="100.96.0.10:5555" # atlas.mesh, or your USB/ADB serial
"$ADB" connect "$ANDROID_SERIAL"
JAVA_HOME="$HOME/.local/share/mise/installs/java/21.0.2" \
PATH="$HOME/.local/share/mise/installs/java/21.0.2/bin:$PATH" \
./android/gradlew -p android assembleDebug assembleDebugAndroidTest
```

Before installing, compare signing certificates with any existing app; never uninstall an existing app to bypass a signature mismatch. `atlas.mesh` had no installed BinderClip at the start of the 2026-10-04 test.

```sh
"$ADB" install -r android/app/build/outputs/apk/debug/app-debug.apk
"$ADB" install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
swiftc scripts/device-tests/clipboard.swift -o /tmp/binderclip-device-clipboard
python3 scripts/device-tests/coordinator.py
```

The coordinator runs `TransportDeviceTest` with `hardwareRoundTrip=true`. It checks authenticated connection, automatic background permission, actual text delivery in both directions, and byte-identical PNG delivery in both directions. It records `/tmp/binderclip-device-network.json` and `/tmp/binderclip-device-instrumentation.log`; it never prints clipboard contents or pairing keys.

For BLE, enable the app fallback, make only BinderClip's WS endpoints inaccessible, and run `coordinator.py --bluetooth`. Images are skipped because BLE carries text/links. Do not turn off the radio used to reach a remote phone. If using per-app firewall rules, determine its current UID and remove those rules in a `finally` block. The 2026-10-04 test blocked only TCP port 39421 for BinderClip's UID, leaving wireless ADB/mesh access intact. Do not remove a user's existing system Bluetooth bond without authorization.

Screen-off startup/liveness/poll test:

```sh
"$ADB" shell input keyevent 223
"$ADB" shell am instrument -w -e hardwareScreenOff true \
  -e class net.wastu.binderclip.PowerDeviceTest \
  net.wastu.binderclip.test/androidx.test.runner.AndroidJUnitRunner
```

This test starts the service while noninteractive, waits for authentication, and checks connection survival and unchanged `clipboard.poll`/`clipboard.read` counters for 65 seconds. It does not establish battery savings, full Doze survival, or overnight sleep behavior. `RootAccessDeviceTest` checks root execution inside the actual Android application process; run it on a rooted device with root already granted.


Additional coordinator modes:

- `--large-text`: text spanning hundreds of KiB in both directions; every run uses a fresh marker.
- `--mesh` / `--lan`: assert the selected endpoint transport after isolating the other path for BinderClip only.
- `--promotion`: start Bluetooth-only, verify image deferral, release the per-app rules and cycle Wi-Fi briefly, verify automatic promotion, both PNG directions, and published Bluetooth release. Set `BINDERCLIP_TEST_UID` to the current UID.
- `--image-cache --cache-seconds=600`: hold a real 3.6 MB PNG for ten minutes, inspect actual read/hash counters, then mutate content behind its unchanged URI and verify byte-identical delivery. The default observation is 70 seconds. The fixture holds its activity awake; leave paired devices’ clipboards untouched during the observation. It aborts if another clip replaces the test image.
- `--manual-share`: exercise Android's real share Activity, Mac Services using a unique pasteboard, and copied-URL versus Browser Links notification behavior. For the debug Mac app set `BINDERCLIP_TEST_SERVICE_NAME='Send to Phones via BinderClip Debug'`; production uses `Send to Phones via BinderClip`.

For a physical GATT-only test, quit the debug app and launch it with `open --env BINDERCLIP_TEST_GATT_ONLY=1 "$HOME/Applications/BinderClip Debug.app"`, then run Bluetooth mode. This hook exists only in DEBUG builds. Restart normally afterward to restore L2CAP.

Changed ad-hoc Mac builds may show a fresh Bluetooth approval dialog. A peripheral manager stuck at state 0 / authorization 0 can be waiting for that dialog; verify permission before attributing missing advertisements to radio, range, or sleep. Preserve app pairing keys. A stale system bond is a separate layer; repair it only within the owner's authorized scope.

`LifecycleDeviceTest` is opt-in with `hardwareLifecycle=true`: it stops/restarts the service three times, checks disconnected status at each stop, and requires fresh authentication after each restart. `PowerDeviceTest` accepts `expectedTransport=BLUETOOTH` for the same screen-off assertions over BLE. Forced Doze tests require a rooted test phone and a `finally` cleanup that calls `dumpsys deviceidle unforce`; short tests cannot establish overnight behavior.
