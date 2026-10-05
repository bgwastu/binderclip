# BinderClip

The most reliable clipboard sync app for Android and Mac. (Requires Root)

BinderClip syncs clipboard (text or images) over your LAN or mesh VPN (like Tailscale). It also uses Bluetooth BLE for last resort.

AI disclosure: Human validated.

## Features

- Automatically clipboard syncs text and images, up to 30 MiB
- Share current browser tab URL between Android and Mac
- Encrypted, replay-resistant clipboard transport on LAN, mesh VPN, and Bluetooth (protocol 3)
- Sleep-aware background work and bounded connection retries
- Auto update when new version is available. For android, use [Obtainium](https://obtainium.imranr.dev/).

## Install
Download the latest release from the [Releases](https://github.com/bgwastu/BinderClip/releases) page.
> Note: Since the app is not notarized yet, you need to see [disable gatekeeper](https://www.google.com/search?q=How+to+open+an+unverified+app+blocked+by+macOS+Gatekeeper) on macOS.


## Acknowledgments
Thanks to [ClipRelay](https://github.com/geekflyer/cliprelay) for the initial implementation and inspiration.
## Protocol 3 update

Update Mac and Android together. Older builds cannot connect to protocol 3; existing pairing keys can be reused. Application data is encrypted even though the underlying WebSocket uses `ws://`. See [the protocol design](docs/secure-protocol.md) and [battery validation](docs/battery-validation.md) for behavior, limits, and measurements still needed.

## Production and debug

Debug installs beside production on both platforms and pairs only with debug. It has independent keys, storage, discovery, and authentication. Production identifiers and its protocol-3 wire format are retained. Debug uses a private test clipboard, with text/image controls on Android and **Private Debug Clipboard…** in the Mac menu; automatic debug transfers never read or write the system clipboard. Debug Android needs no root and neither debug app starts at boot/login or checks production updates.

Use `./macos/debug-install.sh` and the Android `assembleDebug` APK for development. The Mac installer replaces only debug. See [isolation and migration](docs/debug-isolation.md) and [debug device tests](scripts/device-tests/README.md). Both apps share hardware resources; use dedicated acceptance devices for system-clipboard, radio, Doze, and battery tests.
