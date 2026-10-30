# BinderClip Agent Guide

Mac menu bar app and Android app that sync the clipboard. The Mac hosts the WebSocket server (port `39421`); phones connect to it over mesh VPN, LAN, or a Bluetooth fallback.

## Commands

- macOS tests: `swift test --package-path macos/BinderClipMac`
- macOS debug install: `./macos/debug-install.sh`
- macOS release package: `./macos/package-release.sh`
- Android tests: `./android/gradlew -p android testDebugUnitTest`, with `JAVA_HOME=$HOME/.local/share/mise/installs/java/21.0.2` (Java 26 fails)

## Rules

- Separate commits by concern: protocol/features, networking, storage, tooling.
- Do not revert unrelated worktree changes.
- Never log clipboard content, URLs, PSKs, key fragments, or pairing invitations.
- Localize every string for en, id, es, zh, ja, de, fr, pt, ru. macOS uses `L10n.tr`; Android uses `strings.xml`.
- Debug builds are isolated from production: package `net.wastu.binderclip.debug`, port `39422`, separate keys. During debug testing, never stop or reinstall production, copy its keys, operate its boot scripts, or change global radio/Doze settings.
- Tags: `macos-v*`, `android-v*`, or `v*` for both. Diff detection builds only the affected platform.

## Non-obvious constraints

- Protocol 3 encrypts every frame with no plaintext downgrade, so both apps must update together.
- Pairing lists mesh `100.x` endpoints first, because VPNs often capture the LAN route. Never advertise loopback.
- A new Bonjour sighting of a known Mac must not reset WebSocket backoff, or it starves the Bluetooth fallback.
- Bluetooth carries text and links, not images. A late Bluetooth handshake must keep a live WebSocket connection.
- The Mac suspends everything on system sleep and stays suspended through dark wakes.
- Clipboard classification fails closed: file URLs win over other types, and unsupported files are ignored, never sent as a filename.
- Phone → Mac capture uses root if available, otherwise a read-only Shizuku UserService. Mac → phone, sharing, and the outbox need neither.
- Android `BinderClipService` is `specialUse`, not `dataSync`, to avoid the Android 15 6-hour timeout.
- Roster tests must use a temp directory so they never rotate the live pairing key.
