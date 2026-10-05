import Foundation
import Network
import XCTest
@testable import BinderClip

/// Real loopback transport with isolated pairing state. Never opens Bluetooth or the clipboard.
final class SecureWebSocketTests: XCTestCase {
    private func json(_ message: URLSessionWebSocketTask.Message) throws -> [String: Any] {
        guard case .string(let text) = message,
              let object = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] else {
            throw SecureChannelError.invalidFrame
        }
        return object
    }
    private func text(_ object: [String: Any]) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self)
    }
    private func isolated() -> (WebSocketServer, RosterManager, URL, UserDefaults, String) {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite = "binderclip-wire-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let roster = RosterManager(stateDirectory: directory, defaults: defaults)
        return (WebSocketServer(rosterManager: roster, port: 0, bluetoothEnabled: false), roster, directory, defaults, suite)
    }

    func testEncryptedAuthClipboardAndSleepWakeOverRealWebSocket() async throws {
        let (server, roster, directory, defaults, suite) = isolated()
        defer { server.stop(); defaults.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: directory) }
        let ready = expectation(description: "listener ready")
        var port: UInt16?
        server.onListening = { port = $0; ready.fulfill() }
        server.start()
        await fulfillment(of: [ready], timeout: 5)
        let endpoint = try XCTUnwrap(port)
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let socket = session.webSocketTask(with: URL(string: "ws://127.0.0.1:\(endpoint)")!)
        socket.resume()
        let hello = try json(await socket.receive())
        XCTAssertEqual(hello["type"] as? String, "secure_hello")
        let nonce = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(hello["nonce"] as? String)))
        let clientNonce = SecureHandshake().nonce
        let channel = try SecureChannel(psk: roster.groupKey, serverNonce: nonce, clientNonce: clientNonce, transport: "ws", server: false)
        let proof = SecureChannel.proof(psk: roster.groupKey, serverNonce: nonce, clientNonce: clientNonce, transport: "ws")
        try await socket.send(.string(text(["type": "secure_reply", "nonce": clientNonce.base64EncodedString(), "proof": proof.base64EncodedString()])))
        func encrypted(_ object: [String: Any]) throws -> String {
            let packet = try channel.seal(JSONSerialization.data(withJSONObject: object), kind: 1)
            return try text(["type": "secure", "data": packet.base64EncodedString()])
        }
        try await socket.send(.string(encrypted(["type": "auth", "deviceId": "test-phone", "deviceName": "Test", "version": 3, "pairing": true])))
        let reply = try json(await socket.receive())
        let authPacket = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(reply["data"] as? String)))
        let auth = try JSONSerialization.jsonObject(with: channel.open(authPacket, kind: 1)) as? [String: Any]
        XCTAssertEqual(auth?["type"] as? String, "auth_ok")
        let received = expectation(description: "clipboard delivered")
        server.onClipboard = { value in XCTAssertEqual(value, "wire test"); received.fulfill() }
        try await socket.send(.string(encrypted(["type": "clipboard", "text": "wire test"])))
        await fulfillment(of: [received], timeout: 5)
        let awake = expectation(description: "listener returns after sleep")
        server.onListening = { _ in awake.fulfill() }
        server.setSystemSleeping(true)
        server.setSystemSleeping(false)
        await fulfillment(of: [awake], timeout: 5)
        socket.cancel(with: .goingAway, reason: nil)
        server.stop()
        let stopped = expectation(description: "wake cannot restart explicitly stopped server")
        stopped.isInverted = true
        server.onListening = { _ in stopped.fulfill() }
        server.setSystemSleeping(true)
        server.setSystemSleeping(false)
        await fulfillment(of: [stopped], timeout: 0.3)
    }

    func testClientThatNeverUpgradesHasBoundedAuthenticationDeadline() async throws {
        let (server, _, directory, defaults, suite) = isolated()
        defer { server.stop(); defaults.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: directory) }
        let ready = expectation(description: "listener ready")
        var port: UInt16?
        server.onListening = { port = $0; ready.fulfill() }
        let expired = expectation(description: "silent TCP client deadline")
        server.onLog = { if $0 == "Auth deadline exceeded" { expired.fulfill() } }
        server.start()
        await fulfillment(of: [ready], timeout: 5)
        let connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: try XCTUnwrap(port))!, using: .tcp)
        defer { connection.forceCancel() }
        connection.start(queue: DispatchQueue(label: "binderclip-silent-client-test"))
        // No HTTP upgrade or auth bytes: the deadline must start at accept, before .ready.
        await fulfillment(of: [expired], timeout: 8)
        XCTAssertTrue(server.peersSnapshot().isEmpty)
    }

    func testRejectsLegacyPlaintextAuth() async throws {
        let (server, _, directory, defaults, suite) = isolated()
        defer { server.stop(); defaults.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: directory) }
        let ready = expectation(description: "listener ready")
        var port: UInt16?
        server.onListening = { port = $0; ready.fulfill() }
        server.start()
        await fulfillment(of: [ready], timeout: 5)
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let socket = session.webSocketTask(with: URL(string: "ws://127.0.0.1:\(try XCTUnwrap(port))")!)
        socket.resume()
        _ = try await socket.receive()
        try await socket.send(.string("{\"type\":\"auth\",\"version\":2,\"deviceId\":\"test-phone\",\"deviceName\":\"Test\",\"pairing\":true}"))
        do { _ = try await socket.receive(); XCTFail("Plaintext connection must close") }
        catch { XCTAssertTrue(server.peersSnapshot().isEmpty) }
    }
}
