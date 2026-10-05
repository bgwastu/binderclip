# Secure transport, protocol 3

Protocol 3 is required on both Mac and Android. Existing 32-byte pairing keys remain valid, but older applications cannot connect. There is no cleartext fallback. The underlying connection is still a WebSocket over TCP or BLE L2CAP/GATT; application authentication, clipboard data, images, links, and control frames are protected above that transport.

## Session setup

1. The Mac sends `secure_hello` with `version: 3` and a cryptographically random 32-byte server nonce. A fresh challenge is generated for each connection.
2. Android generates a random 32-byte client nonce and sends `secure_reply` containing the nonce and a 32-byte HMAC-SHA256 proof. The proof authenticates the UTF-8 string `BinderClip/v3/<transport>/proof`, followed by server nonce and client nonce, under the pairing key. `<transport>` is `ws` or `ble`.
3. Both endpoints derive separate client-to-server and server-to-client 32-byte keys using HKDF-SHA256. Input key material is the PSK; salt is server nonce concatenated with client nonce; info is `BinderClip/v3/<transport>/c2s` or `/s2c`.
4. Android sends encrypted `auth` containing identity, protocol version, and pairing intent. The PSK itself is never transmitted. The Mac validates the version and existing roster/pairing policy before returning encrypted `auth_ok`. Successful decryption of the response confirms the Mac possesses the pairing key.

The Mac verifies the proof in constant time. Each challenge is accepted once, and unauthenticated sessions expire after five seconds. Android also bounds the whole connect/setup/auth attempt. A rejected/legacy connection prompts updating both apps together. Pairing still distributes the key out of band through the existing QR invitation.

## Encrypted packets

Each direction starts its sequence at one and accepts only the next counter on the ordered reliable transport. Packet bytes are:

```
[8-byte unsigned big-endian sequence][AES-GCM ciphertext][16-byte tag]
```

The 12-byte GCM nonce is four zero bytes followed by the sequence. Uniqueness comes from fresh session keys, distinct directional keys, and a strictly increasing counter. Sending stops before the counter exceeds the signed 64-bit maximum. A failed tag does not advance the receiver; the transport closes on invalid encrypted application input. Replays, skipped sequences, reversed direction, and packets from another session are rejected.

Authenticated additional data is the UTF-8 context `BinderClip/v3/<transport>`, one kind byte, and the eight-byte sequence. Kind 1 is WebSocket JSON, 2 is WebSocket image binary, and 3 is BLE CBOR. Binding the kind and transport prevents changing the interpretation of a valid encrypted packet.

WebSocket JSON and BLE CBOR use an outer `secure` envelope with Base64 packet data. WebSocket image binary carries packet bytes directly to avoid Base64 expansion. Limits include the existing image/frame bounds and the 24-byte encrypted-packet overhead. Unauthenticated CBOR envelopes reject oversized maps/arrays, duplicate keys, and trailing bytes before allocation or application processing.

All socket sends preserve encryption-counter order. A GATT challenge received before subscription acknowledgement is held until that acknowledgement before any authentication write. Bluetooth writes respect backpressure: L2CAP retains unwritten bytes, GATT writes are acknowledged and serialized, and outgoing queues are bounded.

## Trust and limitations

This authenticates possession of the shared pairing-group secret. It does not establish a separate cryptographic identity for each phone: another trusted member with that key has the same cryptographic authority. Removing a previously trusted member does not revoke its knowledge of the group key; rotate the pairing key when revocation of that knowledge is needed.

This PSK design does not provide forward secrecy. Compromise of the PSK can expose recorded sessions whose public nonces were captured. Device IDs, names, and clipboard content are encrypted after setup, but transport endpoints, advertised service names, traffic timing, and packet sizes remain observable. A live transparent relay can forward a session without decrypting it. These are explicit protocol properties, not promises of full traffic anonymity or per-device isolation.

CryptoKit and Java's standard cryptographic providers implement the primitives. Tests share an independent Python `cryptography` HKDF/AES-GCM vector and cover tampering, replay, direction, transport/kind binding, wrong keys, and fresh sessions. Real loopback tests exercise the Mac's actual WebSocket handshake, application delivery, plaintext rejection, and sleep/wake lifecycle. Physical Android/BLE testing and independent cryptographic review remain valuable before a public release.
