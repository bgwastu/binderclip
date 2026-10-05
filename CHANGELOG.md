# Changelog

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
