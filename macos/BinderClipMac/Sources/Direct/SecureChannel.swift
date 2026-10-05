import CryptoKit
import Foundation
import Security

enum SecureChannelError: Error { case invalidHandshake, invalidFrame, replay, exhausted }

/// Version 3: PSK-authenticated fresh sessions. No plaintext application frames or downgrade.
/// Traffic keys are directional; an ordered 64-bit counter supplies unique GCM nonces.
final class SecureChannel {
    private let sendKey: SymmetricKey
    private let receiveKey: SymmetricKey
    private let context: Data
    private let lock = NSLock()
    private var sent: UInt64 = 0
    private var received: UInt64 = 0
    static let maximumPlaintext = SyncProtocol.maximumImageBytes

    init(psk: Data, serverNonce: Data, clientNonce: Data, transport: String, server: Bool) throws {
        guard psk.count == 32, serverNonce.count == 32, clientNonce.count == 32,
              transport == "ws" || transport == "ble" else { throw SecureChannelError.invalidHandshake }
        let contextData = Data("BinderClip/v3/\(transport)".utf8)
        context = contextData
        let salt = serverNonce + clientNonce
        func derive(_ direction: String) -> SymmetricKey {
            HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: psk), salt: salt,
                info: contextData + Data("/\(direction)".utf8), outputByteCount: 32)
        }
        sendKey = derive(server ? "s2c" : "c2s")
        receiveKey = derive(server ? "c2s" : "s2c")
    }

    static func proof(psk: Data, serverNonce: Data, clientNonce: Data, transport: String) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data("BinderClip/v3/\(transport)/proof".utf8) + serverNonce + clientNonce,
            using: SymmetricKey(data: psk)))
    }

    func seal(_ plaintext: Data, kind: UInt8) throws -> Data {
        lock.lock(); defer { lock.unlock() }
        guard plaintext.count <= Self.maximumPlaintext else { throw SecureChannelError.invalidFrame }
        guard sent < UInt64(Int64.max) else { throw SecureChannelError.exhausted }
        sent += 1
        var counter = sent.bigEndian
        let sequence = withUnsafeBytes(of: &counter) { Data($0) }
        let nonce = try AES.GCM.Nonce(data: Data(repeating: 0, count: 4) + sequence)
        let box = try AES.GCM.seal(plaintext, using: sendKey, nonce: nonce,
            authenticating: context + Data([kind]) + sequence)
        return sequence + box.ciphertext + box.tag
    }

    func open(_ packet: Data, kind: UInt8) throws -> Data {
        lock.lock(); defer { lock.unlock() }
        guard packet.count >= 24, packet.count <= Self.maximumPlaintext + 24 else { throw SecureChannelError.invalidFrame }
        let sequence = Data(packet.prefix(8))
        let counter = sequence.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        guard counter == received + 1, counter <= UInt64(Int64.max) else { throw SecureChannelError.replay }
        let nonce = try AES.GCM.Nonce(data: Data(repeating: 0, count: 4) + sequence)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: packet.dropFirst(8).dropLast(16), tag: packet.suffix(16))
        let plaintext = try AES.GCM.open(box, using: receiveKey, authenticating: context + Data([kind]) + sequence)
        received = counter
        return plaintext
    }
}

final class SecureHandshake {
    let nonce: Data
    private var consumed = false
    init() {
        var bytes = [UInt8](repeating: 0, count: 32)
        precondition(SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess)
        nonce = Data(bytes)
    }
    func accept(clientNonce: Data, proof: Data, psk: Data, transport: String) throws -> SecureChannel {
        guard !consumed, clientNonce.count == 32, proof.count == 32, psk.count == 32 else { throw SecureChannelError.invalidHandshake }
        let transcript = Data("BinderClip/v3/\(transport)/proof".utf8) + nonce + clientNonce
        guard HMAC<SHA256>.isValidAuthenticationCode(proof, authenticating: transcript, using: SymmetricKey(data: psk)) else {
            throw SecureChannelError.invalidHandshake
        }
        consumed = true
        return try SecureChannel(psk: psk, serverNonce: nonce, clientNonce: clientNonce, transport: transport, server: true)
    }
}
