# Battery behavior and verification

The implementation addresses the battery/source review at base commit `ca92005`. Battery savings are not yet measured. Hardware sleep counts, app CPU, and whole-device battery consumption measure different things; lower CPU alone does not establish quieter overnight sleep.

## Behavior

- Mac system sleep closes listeners and sessions, stops Bluetooth advertising and nonessential timers, and pauses clipboard monitoring. User wake with an awake display refreshes networking and restores eligible transports; maintenance wakes with the display asleep leave them suspended. Display-off by itself does not stop background sync. An explicit application stop cannot be reversed by a later wake notification.
- A best-effort encrypted Mac sleep notice pauses phone discovery/reconnect for 60 seconds. It is not guaranteed to arrive before OS suspension, so bounded retries remain necessary and the pause always expires. Screen-on/user force reconnect can recover promptly.
- The Mac sends heartbeats every two seconds with a five-second interactive deadline. Phone screen-off is advertised after authentication and on transitions on both transports, selecting a 15-second interval and 60-second deadline. The phone's BLE timer probes when complete authenticated frames have gone quiet. Fragment progress extends its receive deadline but does not suppress reverse probes during large transfers. Wall-clock changes do not affect liveness.
- Android BLE scans run for at most eight seconds per burst, with balanced mode while interactive and low-power mode when screen-off. Failed scans/connects back off from 15 seconds to five minutes with jitter. Explicit screen-on resets retry state. The 20-second attempt deadline includes GATT/channel/authentication and closes pending sockets even when a GATT handle already exists.
- Mac BLE advertising is needed when a paired phone lacks WebSocket connectivity or a pairing window is open. All-peers-connected WebSocket operation stops advertising. Existing live BLE sessions are retained through the phone's 15-second WebSocket stability grace period. Powered-off peripheral-manager recovery backs off from 30 seconds to five minutes; denied/unsupported states do not retry.
- Android WebSocket retry starts at 1–16 seconds, then caps at 60 seconds interactive or five minutes screen-off, with jitter. Heartbeat failures preserve retry history; 30 seconds of healthy service resets it. A working BLE fallback suppresses unattended WS racing, while allowing its once-per-session promotion probe and a probe when a usable network becomes available. A late BLE authentication cannot cancel a working WebSocket; the phone publishes Bluetooth shutdown after the stability grace period.
- Mac clipboard polling runs at 0.5 seconds, with tolerance, only when a peer is connected. Network changes are event-driven with a 30-second VPN safety sample and a shared address snapshot. Heartbeat timers exist only for live sessions.
- Android clipboard callbacks remain the fast path. The fallback poll runs every three seconds only while paired, interactive, and connected. At most one read/delivery job is outstanding. Metadata and callback-generation caching avoid repeated image reads; once/minute revalidation catches providers changing a stable URI. A callback invalidating a slow read discards its stale result. Text delivery uses the active WS or BLE path; hashes advance after delivery queues successfully. Images wait for their supported WS path.
- Diagnostics persist in two-second batches and flush on controlled shutdown/sleep. Clipboard data, browser URLs, key fragments, and invitations are removed from payload logging. No system-wide power/Doze/Bluetooth settings are changed by these optimizations; the existing root background-access/autostart policy remains.

## Counters

Logs include `Activity counters` aggregates after five minutes of actual work. There is no additional reporting timer and no clipboard content in counter labels. Mac records clipboard polls/decodes, address samples, heartbeat sends, and peripheral-manager creation. Android records scan starts and scan milliseconds, GATT attempts, heartbeat probes, fallback polls/reads, and bytes hashed for images. A partial window may remain unreported when the app stops. Android also exposes lifetime cumulative snapshots; long tests must use those snapshots because five-minute reporting resets each log window. Counters are observations of work, not an energy model.

## Checks

Run on macOS:

```sh
swift test --package-path macos/BinderClipMac
```

Run Android checks using Java 21:

```sh
JAVA_HOME="$HOME/.local/share/mise/installs/java/21.0.2" \
PATH="$HOME/.local/share/mise/installs/java/21.0.2/bin:$PATH" \
./android/gradlew -p android test assembleDebug --no-daemon
```

The loopback tests isolate pairing files and preferences and disable Bluetooth. They do not alter the live app's pairing key or actual clipboard. Tests and build CPU activity should be excluded from normal power measurements.

## Physical-device acceptance

Update both applications together before testing protocol 3. Keep the existing pairing secret or scan a fresh QR. Check:

1. Awake text, supported images, browser links, and manual send on Wi-Fi and mesh.
2. Wi-Fi unavailable: BLE text/links, then stable transition back to WS; images deliver when WS returns.
3. Start the phone service with the screen already off. No clipboard polling should run, and Mac BLE liveness should use the sleeping deadline after authentication.
4. Keep an unchanged large clipboard image for ten minutes. Reads/hashes should be near once/minute fallback revalidation, plus actual clipboard changes, rather than twice/second. Mutate content behind the same URI and check delivery.
5. Mac asleep, out of range, radio disabled, and permission denied: bounded scans/retries, no reset loops, and successful recovery. Repeat lid-close/open and rapid radio toggles to expose late callbacks.
6. Compare at least two similar unplugged sleep periods each with current BinderClip, BinderClip quit (Bluetooth unchanged), and the revised apps. Keep phone proximity, peripherals, Wi-Fi, starting charge, and duration comparable. Record `pmset -g log`, charge loss/hour, dark wakes/hour, and Bluetooth-attributed records where available.

The earlier audit found 1,220 BinderClip-attributed Bluetooth events and many associated dark wakes; attribution records often followed hardware wakes. The implementation therefore needs a controlled comparison before assigning causal responsibility or claiming extra battery hours. Physical device results from 2026-10-04 are recorded below. Overnight comparisons remain outstanding.


## Physical test results: atlas.mesh, 2026-10-04

Tested a POCO F4 (`munch`, model `22021211RG`) running rooted Android 16 / API 36, reached through `air.mesh` using wireless ADB at `100.96.0.10:5555`. SSH was unavailable; ADB was reachable. There was no previous BinderClip installation on this phone. The Mac used the separate `BinderClip Debug.app` bundle; the production application and pairing secret were preserved.

- Root execution inside the actual application process passed after granting BinderClip in KernelSU.
- Authenticated network connection established over mesh (`100.96.0.7:39421`); LAN connection was also observed during reconnection.
- Automatic text clipboard delivery passed in both directions.
- Real PNG clipboard delivery passed in both directions, comparing actual bytes/hashes.
- Starting the service with the screen already off passed: authentication completed, the session stayed connected for 65 seconds, and clipboard poll/read counters did not increase.
- BLE scanning found the Mac. The transport test then failed before service discovery/application authentication with GATT status 5. Android's system Bluetooth log reported `encryption_change:key_missing`, bond loss, and `HCI_ERR_AUTH_FAILURE` for the existing Air bond. This prevents claiming BLE transfer success. A bond repair requires owner authorization; do not silently remove it. Diagnostics now provide a bond-repair hint and include MTU result status instead of labelling a failed negotiation successful.
- The BLE experiment blocked only BinderClip's TCP port 39421 traffic for its UID, and removed both IPv4 and IPv6 test rules afterward. No device-wide network or Bluetooth radio toggle was needed.
- Two remaining Android Activity pairing-URI log statements were redacted before the phone installation.

Run the opt-in instrumentation and Mac clipboard coordinator using [the physical test instructions](../scripts/device-tests/README.md). `PowerDeviceTest` observes counters in the test process; its 65-second result does not establish full Doze survival or battery savings. The phone was plugged in at 80% during testing, so charge-loss comparisons would not be meaningful. BLE round trips, radio/permission failure recovery, long-image caching, browser-link UI behavior, and overnight Mac sleep/battery comparisons still need separate acceptance runs.

After the run, the Mac production app was relaunched and its pairing-secret file compared unchanged against the pre-test backup. The Android debug app remains installed and paired for future testing, but its service was force-stopped and the boot keep-alive scripts created by this test were removed. Its screen was returned to off. Both test firewall rules were removed. The production Mac app still uses the previous protocol, so restart the updated Mac debug app before resuming tests with this Android build.

## Physical retest results: Atlas/Air, 2026-10-05

With Atlas close to Air, encrypted text transfers passed over both L2CAP and forced GATT fallback, including text spanning hundreds of KiB. The stale system bond from the previous run was already absent; no bond deletion or pairing-key rotation was required. Changed ad-hoc Mac builds triggered Bluetooth privacy approval; resolve that dialog before treating a state-0 peripheral manager as a discovery/radio failure.

LAN and mesh endpoint round trips passed with real PNG byte comparisons. A real Wi-Fi cycle verified automatic WebSocket promotion while retaining Bluetooth, deferred image delivery, and Bluetooth release after the stability grace period. Copying previously received text after an image also passed. Nearby Devices permission revocation/restoration and an Android Bluetooth off/on cycle each ended in a fresh large encrypted Bluetooth round trip. Mac Services and Android's real share Activity passed; copied URLs remained clipboard content, while shared URLs produced an actionable Browser Links notification.

The retest found and repaired reverse heartbeat starvation during fragmented transfers, a network-availability promotion gap, stale Bluetooth status, a late Bluetooth handshake cancelling preferred WebSocket, stale clipboard echo state, and a service-shutdown/root-callback scheduler crash. Three controlled stop/start cycles passed after repair. Duplicate root setup was removed; connection cleanup and the initial TCP/HTTP authentication deadline were tightened. Ten socket samples over three minutes had no `CLOSE_WAIT` accumulation. The debug Services entry now has a distinct name/port to avoid launching the older production app.

A valid ten-minute awake cache observation used a real 3,651,648-byte PNG: **10 reads and 36,516,480 hash bytes in 600 seconds**. Mutating bytes behind its unchanged URI then delivered the changed PNG byte-for-byte. Android exposes cumulative counter snapshots because five-minute log windows reset; long tests must not subtract resettable window values. The fixture holds its activity awake, waits for restored clipboard traffic to settle, and detects replacement of the test clipboard. Earlier interrupted observations were not scored as passes.

Screen-off Bluetooth and a forced-Doze network test each kept authentication for 65 seconds without increasing clipboard poll/read counters. Automated verification passed 54 Swift tests, a Swift release build, 78 Android unit tests, debug/instrumentation APK builds, and Android lint with zero errors (64 warnings).

The phone was charging, ADB/instrumentation was active, and Mac keep-awake assertions were used for testing. These results establish delivery, recovery, and reduced work; they do not establish additional battery hours or overnight/lid-close behavior. Another phone at `100.96.0.46` still attempted protocol-2 connections and needs the matching update. Its retries prevent treating this run as an idle battery baseline. Update all participating devices before controlled unplugged comparisons.
