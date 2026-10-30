# Changelog

## 1.17.0

Mac and Android synchronized release:

- **Android Shizuku Support:** Capture clipboard on unrooted Android devices using Shizuku user service alongside existing root capture.
- **Synchronized Visual Identity:** Redesigned macOS and Android app icons using the Apple HIG layered squircle specification and Android adaptive icon with a unified brand blue palette.
- **macOS Menu Bar Animation:** Layered menu bar icon with an animated circular progress ring, smooth spring transitions, zero idle CPU overhead, and sleep/dark wake protection.
- **macOS UI Overhaul:** Flattened menu hierarchy eliminating nested submenus, modernized pairing window with direct copyable invitation links and step guide, native Console-style log viewer with live search and level filtering, and interactive click-to-reveal Toast HUD.
- **Android Material 3 & Edge-to-Edge Overhaul:** Full Android 15 Edge-to-Edge support, compact status header and side-by-side action buttons, native M3 ModalBottomSheet for device management, redesigned Received Files screen with TopAppBar and back navigation, and smooth Share Receiver flow.
- **File Transfer UX Overhaul:** Live background notification progress bar, send completion notifications and sound, and immediate share sheet feedback.
- **Android In-App Auto-Updater:** Checks for updates from GitHub releases or generic feeds, shows update notes, and guides installation via system package installer.
## Android 1.16.1

- Show file transfer progress in an overlay with a single-line filename, file-type icon, and consistent spacing.
- Add compact **Hide** and **Cancel transfer** buttons. Hide keeps delivery running without reopening the panel; Cancel stops the selected file and removes it from the outgoing queue.
- Replace the blank share-import screen with a dimmed dialog and remove misleading queued messages after sending.
- Roll back failed file selections atomically, including provider stream-close failures, so an import error cannot leave files queued for delivery.
- Preserve delivery confirmation when cancellation races the final chunk, and ignore late acknowledgements after cancellation.

Validation: 117 Android unit tests, the emulator transfer UI check, debug builds, and Android lint passed.

Version comes from the Android-only release tag `android-v1.16.1`.

## 1.15.0

Mac and Android must update together: protocol 3 encrypts and authenticates all application traffic over LAN, mesh, and Bluetooth, rejects replayed frames, and does not connect to older protocol versions. Existing production pairing keys remain usable.

- Reduce idle work with sleep-aware clipboard polling, cached image reads, demand-driven discovery and heartbeats, bounded Bluetooth scans, and connection retry backoff.
- Harden Bluetooth setup, stale callbacks, GATT framing, and WebSocket authentication deadlines.
- Prevent clipboard echo loops when sending earlier text after an image.
- Install debug beside production with separate app identities, keys, preferences, ports, discovery, authentication realms, and private clipboards.
- Keep debug root, boot/login startup, production updates, and automatic Bluetooth radio activation disabled. Debug Bluetooth testing is explicitly opt-in because both apps share physical radio resources.
- Add security, power-policy, isolation, artifact, and opt-in device checks.

Validation before release: 57 Mac tests and 81 Android unit tests passed; Android lint had zero errors and 65 warnings. Debug device checks covered private text/images, LAN/mesh delivery, cache behavior, sharing, lifecycle, and preservation of production state. Simultaneous isolated Bluetooth discovery beside production did not pass; dedicated L2CAP/GATT acceptance and quantitative battery measurements remain outstanding.

Versions come from release tags; `v1.15.0` packages both platforms as 1.15.0.
