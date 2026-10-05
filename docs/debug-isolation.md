# Production and debug isolation

Production and debug install together and form independent sync groups. Debug is an application variant, not a runtime switch in production. Existing production identifiers, storage, pairing keys, and the protocol-3 wire context remain compatible.

| Resource | Production | Debug |
|---|---|---|
| Android application ID | `net.wastu.binderclip` | `net.wastu.binderclip.debug` |
| Android instrumentation | Production acceptance tooling | `net.wastu.binderclip.debug.test` |
| Mac bundle ID | `net.wastu.binderclip` | `net.wastu.binderclip.debug.mb` |
| Mac preferences | Standard production domain | `net.wastu.binderclip.debug.mb.isolated` |
| Mac Application Support | `net.wastu.binderclip` | `net.wastu.binderclip.debug.mb` |
| TCP port | 39421 | 39422 |
| Bonjour | `_binderclip._tcp` | `_binderclip-dbg._tcp` |
| Pairing URI | `binderclip://pair` | `binderclip-debug://pair` |
| Cryptographic context | `BinderClip/v3` | `BinderClip/debug/v3` |
| Clipboard | System clipboard | Private debug clipboard |
| Root boot script | `binderclip.sh` | Disabled; reserved name `binderclip-debug.sh` |
| Mac Services | Send to Phones via BinderClip | Send to Phones via BinderClip Debug |

The shorter `binderclip-dbg` Bonjour name fits DNS-SD's service-name length limit. BLE uses four separate custom UUIDs in each environment; CCCD remains the standard Bluetooth descriptor and L2CAP PSMs remain allocated by the OS.

## Pairing and transport boundaries

`AppProfile` is selected by the Android build type and the packaged Mac bundle configuration. Android's debug application-ID suffix gives it independent storage, Keystore access, notifications, and runtime permissions. FileProvider authorities use the application ID. Internal service commands use explicit classes; root-script component names include the full source class because the Kotlin namespace remains `net.wastu.binderclip`.

Debug resolves only its own Bonjour type and, when Bluetooth testing is explicitly enabled, scans only its own service UUID. Debug discovery requires a matching paired identity; it has no production discovery or port fallback. Pairing parsers reject the other environment's URI scheme before changing saved state. A copied key still cannot authenticate or decrypt across environments: handshake HMACs, traffic-key derivation, and frame AAD bind the selected crypto context. Production retains its existing protocol-3 context.

## Private clipboard

Android `AppClipboard` never acquires `ClipboardManager` in debug. The process-local debug board carries text and app-owned image URIs, supports change listeners, and uses the same classification/caching/delivery paths. It resets with process death. The debug UI provides text entry, a generated test PNG, and a preview of private received content.

Mac debug uses a named pasteboard, `net.wastu.binderclip.debug.mb.clipboard`. Its menu opens **Private Debug Clipboard…** to send text or a selected image and inspect received content. Debug incoming browser links are displayed privately instead of automatically launching a browser. Debug Services use their own port/name. The physical-test helper operates only on the named debug board; its sole system-clipboard operation reads a change count for isolation verification.

Automatic debug transfers cannot bridge through the system clipboard into the production group. Explicit user copy/paste actions in ordinary OS controls still use the shared system clipboard. Real system-clipboard acceptance testing belongs on dedicated devices or an explicitly isolated acceptance session.

## Installation, startup, and updates

`macos/debug-install.sh` replaces only the debug bundle and stops only debug instances. It preserves `/Applications/BinderClip.app` and leaves production running. Debug Sparkle initialization, manual update actions, the production-feed fallback, and login registration are disabled.

Android debug requires no root. Root availability probing and privilege/autostart mutators are blocked for debug. Its manifest removes background clipboard, boot, and battery-exemption permissions and disables `BootReceiver`. Debug has no boot keep-alive script; unpairing cannot remove production's script. Debug permissions are granted independently when needed for notifications and Bluetooth.

Bluetooth is **off by default in both debug apps**. On Mac, opt in through **Bluetooth Testing (Shared Radio)**; Android has its own Bluetooth fallback toggle. Debug never automatically enables the system Bluetooth radio. Leave testing off for routine coexistence. Apple's peripheral advertising is [best effort](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:)): multiple custom 128-bit UUIDs may exceed the advertising budget, and overflow UUIDs are discoverable only by iOS scanners. Separate UUIDs prevent cross-environment service use but cannot guarantee simultaneous Android discovery on one Mac radio. Test L2CAP/GATT on dedicated acceptance hardware; do not stop production or reset its bonds to make a routine debug test pass.

## Migration from the former shared debug setup

Create fresh debug pairing state; never copy production keys or app data. Mac debug uses a new preferences suite so legacy shared-group peers/diagnostics do not become isolated-group peers. Its dedicated secret file is generated independently. The former debug preferences and production directory are preserved.

Earlier Android test builds used the production application ID. They remain a separate, legacy installation after the new debug APK is installed. Do not uninstall, overwrite, or migrate that package automatically. Its signing/version compatibility and its matching production Mac release need a separate deployment decision. The current isolation change does not replace production artifacts.

## Verification

Run `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` on Android, and `swift test` plus a release build on Mac. Unit tests cover cross-environment pairing/proofs/encryption with identical keys, production protocol vectors, boot policy/components, separate storage, and a private Mac board without changing the general board.

CI additionally runs `scripts/verify-debug-artifacts.py` against the actual Android metadata/merged manifest and Mac debug bundle. It checks identities, provider authority, URI scheme, removed permissions, disabled boot receiver, Services, Bonjour, and update configuration.

Use [the debug physical-test instructions](../scripts/device-tests/README.md). `AppIsolationDeviceTest` verifies private text/PNG handling and root no-ops against the real Android system clipboard/permission state. Coexistence acceptance compares production's executable, identity/roster, pairing secret, Android package/data/UID, root scripts, and Mac PID before and after debug installation/testing/uninstall.

Both apps still share CPU, memory, networking, the Bluetooth radio, display state, and battery. Isolation guarantees target app state, pairing, protocol, and automatic clipboard effects. Routine tests do not reset production privacy permissions or change global radio/Doze settings. Bond repair, radio cycling, full Doze, real clipboard, and battery measurements require dedicated acceptance devices/sessions. macOS permission approval needs an unlocked desktop; repair only the debug bundle's privacy state.
