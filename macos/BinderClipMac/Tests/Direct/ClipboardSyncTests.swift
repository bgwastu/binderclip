import Foundation
import XCTest
@testable import BinderClip

final class ClipboardSyncTests: XCTestCase {
    private func start(root: URL, defaults: UserDefaults) async throws -> (WebSocketServer, RosterManager, UInt16) {
        let roster = RosterManager(stateDirectory: root, defaults: defaults)
        let server = WebSocketServer(rosterManager: roster, port: 0, bluetoothEnabled: false)
        let ready = expectation(description: "isolated clipboard listener")
        var port: UInt16?
        server.onListening = { port = $0; ready.fulfill() }
        server.start()
        await fulfillment(of: [ready], timeout: 5)
        return (server, roster, try XCTUnwrap(port))
    }
    private func stamp(_ text: String, time: Int64, origin: String = "phone-a", kind: String = "text") -> ClipboardStamp {
        ClipboardStamp(time: time, counter: 0, origin: origin, event: UUID().uuidString.lowercased(), kind: kind, hash: SyncProtocol.sha256Hex(text))
    }

    private func assertStamp(_ json: [String: Any], _ expected: ClipboardStamp, file: StaticString = #filePath, line: UInt = #line) throws {
        XCTAssertEqual(try ClipboardStamp.parse(json), expected, file: file, line: line)
    }
    private func assertString(_ json: [String: Any], _ key: String, _ expected: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(json[key] as? String, expected, file: file, line: line)
    }
    private func assertInt(_ json: [String: Any], _ key: String, _ expected: Int, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(json[key] as? Int, expected, file: file, line: line)
    }

    func testDelayedConflictsAndOfflineCopiesConvergeAcrossTwoPeersAndReconnects() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite = "binderclip-catchup-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: root) }
        let (server, roster, port) = try await start(root: root, defaults: defaults)
        defer { server.stop() }
        let a = try await ClipboardWireClient.connect(port: port, key: roster.groupKey, id: "phone-a")
        let b = try await ClipboardWireClient.connect(port: port, key: roster.groupKey, id: "phone-b")
        defer { a.close(); b.close() }
        _ = try await a.control("clipboard_state"); _ = try await b.control("clipboard_state")
        var received: [String] = []
        server.onClipboard = { received.append($0) }
        let earlier = stamp("earlier", time: 100)
        let later = stamp("later", time: 200, origin: "phone-b")
        try await b.copy("later", stamp: later)
        try assertStamp(try await b.control("clipboard_state"), later)
        assertString(try await a.control("clipboard"), "text", "later")
        try await a.copy("earlier", stamp: earlier)
        try assertStamp(try await a.control("clipboard_state"), later)
        let applied = expectation(description: "delayed copy cannot overwrite newer copy")
        DispatchQueue.main.async { XCTAssertEqual(received, ["later"]); applied.fulfill() }
        await fulfillment(of: [applied], timeout: 2)

        b.close()
        server.sendClipboard("Mac offline")
        let liveMac = try await a.control("clipboard")
        let macStamp = try XCTUnwrap(ClipboardStamp.parse(liveMac))
        let unchanged = try await ClipboardWireClient.connect(port: port, key: roster.groupKey, id: "phone-b")
        defer { unchanged.close() }
        try assertStamp(try await unchanged.control("clipboard_state"), macStamp)
        try await unchanged.state(later)
        assertString(try await unchanged.control("clipboard"), "text", "Mac offline")
        unchanged.close()

        let offline = stamp("Phone offline", time: macStamp.time + 1_000, origin: "phone-b")
        let newer = try await ClipboardWireClient.connect(port: port, key: roster.groupKey, id: "phone-b")
        defer { newer.close() }
        _ = try await newer.control("clipboard_state")
        try await newer.state(offline)
        assertString(try await newer.control("clipboard_request"), "requestEvent", offline.event)
        try await newer.copy("Phone offline", stamp: offline)
        try assertStamp(try await newer.control("clipboard_state"), offline)
        assertString(try await a.control("clipboard"), "text", "Phone offline")
    }

    func testEqualVersionRequestsMissingPayloadAfterHubRestart() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite = "binderclip-payload-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: root) }
        let (server, roster, port) = try await start(root: root, defaults: defaults)
        defer { server.stop() }
        server.sendClipboard("restore")
        let first = try await ClipboardWireClient.connect(port: port, key: roster.groupKey, id: "phone-a")
        defer { first.close() }
        let initial = try await first.control("clipboard_state")
        let version = try XCTUnwrap(ClipboardStamp.parse(initial))
        first.close(); server.stop()
        let (restored, restoredRoster, restoredPort) = try await start(root: root, defaults: defaults)
        defer { restored.stop() }
        let client = try await ClipboardWireClient.connect(port: restoredPort, key: restoredRoster.groupKey, id: "phone-a")
        defer { client.close() }
        let missing = try await client.control("clipboard_state")
        XCTAssertEqual(try ClipboardStamp.parse(missing), version)
        XCTAssertEqual(missing["clipAvailable"] as? Int, 0)
        try await client.state(version)
        assertString(try await client.control("clipboard_request"), "requestEvent", version.event)
        let received = expectation(description: "restore body with unchanged version")
        restored.onClipboard = { XCTAssertEqual($0, "restore"); received.fulfill() }
        try await client.copy("restore", stamp: version)
        let available = try await client.control("clipboard_state")
        XCTAssertEqual(try ClipboardStamp.parse(available), version)
        XCTAssertEqual(available["clipAvailable"] as? Int, 1)
        await fulfillment(of: [received], timeout: 5)
    }

    func testImageResumedAfterRestartCannotOverwriteNewerText() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite = "binderclip-image-order-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: root) }
        let (server, roster, port) = try await start(root: root, defaults: defaults)
        defer { server.stop() }
        let first = try await ClipboardWireClient.connect(port: port, key: roster.groupKey, id: "phone-a")
        defer { first.close() }
        _ = try await first.control("clipboard_state")
        let bytes = Data(repeating: 17, count: FileTransferProtocol.chunkBytes * 2 + 37)
        let image = ClipboardStamp(time: 100, counter: 0, origin: "phone-a", event: UUID().uuidString.lowercased(), kind: "image", hash: SyncProtocol.sha256Hex(bytes))
        let meta = TransferMetadata(id: UUID().uuidString.lowercased(), kind: "image", name: "image", mimeType: "image/png", size: Int64(bytes.count), sha256: image.hash, clipboard: image)
        try await first.send(meta.json)
        assertInt(try await first.control("transfer_ack"), "offset", 0)
        try await first.chunk(meta.id, offset: 0, data: Data(bytes.prefix(FileTransferProtocol.chunkBytes)))
        assertInt(try await first.control("transfer_ack"), "offset", FileTransferProtocol.chunkBytes)
        let text = stamp("newer text", time: 200)
        try await first.copy("newer text", stamp: text)
        try assertStamp(try await first.control("clipboard_state"), text)
        first.close(); server.stop()

        let (restored, restoredRoster, restoredPort) = try await start(root: root, defaults: defaults)
        defer { restored.stop() }
        let staleImage = expectation(description: "older completed image must not be applied")
        staleImage.isInverted = true
        restored.onImage = { _ in staleImage.fulfill() }
        let client = try await ClipboardWireClient.connect(port: restoredPort, key: restoredRoster.groupKey, id: "phone-a")
        defer { client.close() }
        try assertStamp(try await client.control("clipboard_state"), text)
        try await client.send(meta.json)
        assertInt(try await client.control("transfer_ack"), "offset", FileTransferProtocol.chunkBytes)
        let offset = FileTransferProtocol.chunkBytes
        try await client.chunk(meta.id, offset: Int64(offset), data: bytes.subdata(in: offset..<(offset * 2)))
        assertInt(try await client.control("transfer_ack"), "offset", offset * 2)
        try await client.chunk(meta.id, offset: Int64(offset * 2), data: Data(bytes.dropFirst(offset * 2)))
        _ = try await client.control("transfer_done")
        try assertStamp(try await client.control("clipboard_state"), text)
        await fulfillment(of: [staleImage], timeout: 0.3)
    }
}

private final class ClipboardWireClient {
    private let session: URLSession
    private let socket: URLSessionWebSocketTask
    private let channel: SecureChannel
    private var controls: [[String: Any]] = []
    private init(session: URLSession, socket: URLSessionWebSocketTask, channel: SecureChannel) {
        self.session = session; self.socket = socket; self.channel = channel
    }
    private static func text(_ object: [String: Any]) throws -> String { String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self) }
    static func connect(port: UInt16, key: Data, id: String) async throws -> ClipboardWireClient {
        let session = URLSession(configuration: .ephemeral)
        let socket = session.webSocketTask(with: URL(string: "ws://127.0.0.1:\(port)")!)
        socket.resume()
        guard case .string(let hello) = try await socket.receive(),
              let json = try JSONSerialization.jsonObject(with: Data(hello.utf8)) as? [String: Any],
              let encoded = json["nonce"] as? String, let nonce = Data(base64Encoded: encoded) else { throw SecureChannelError.invalidHandshake }
        let clientNonce = SecureHandshake().nonce
        let channel = try SecureChannel(psk: key, serverNonce: nonce, clientNonce: clientNonce, transport: "ws", server: false)
        let proof = SecureChannel.proof(psk: key, serverNonce: nonce, clientNonce: clientNonce, transport: "ws")
        try await socket.send(.string(text(["type": "secure_reply", "nonce": clientNonce.base64EncodedString(), "proof": proof.base64EncodedString()])))
        let result = ClipboardWireClient(session: session, socket: socket, channel: channel)
        try await result.send(["type": "auth", "deviceId": id, "deviceName": "Test Phone", "version": 3, "pairing": true, "clipboardSync": 1, "chunkedTransfers": 1, "clockProbe": 10])
        let auth = try await result.control("auth_ok")
        guard auth["clipboardSync"] as? Int == 1, auth["clockProbe"] as? Int == 10 else { throw SecureChannelError.invalidHandshake }
        return result
    }
    func send(_ object: [String: Any]) async throws {
        let packet = try channel.seal(JSONSerialization.data(withJSONObject: object), kind: 1)
        try await socket.send(.string(Self.text(["type": "secure", "data": packet.base64EncodedString()])))
    }
    func copy(_ text: String, stamp: ClipboardStamp) async throws {
        var json = stamp.fields; json["type"] = "clipboard"; json["text"] = text
        try await send(json)
    }
    func state(_ stamp: ClipboardStamp) async throws {
        var json = stamp.fields; json["type"] = "clipboard_state"; json["clipAvailable"] = 1
        try await send(json)
    }
    func chunk(_ id: String, offset: Int64, data: Data) async throws {
        try await socket.send(.data(channel.seal(FileTransferProtocol.packChunk(id: id, offset: offset, data: data), kind: 2)))
    }
    func control(_ type: String) async throws -> [String: Any] {
        if let index = controls.firstIndex(where: { $0["type"] as? String == type }) { return controls.remove(at: index) }
        let timeout = DispatchWorkItem { [weak socket] in socket?.cancel(with: .goingAway, reason: nil) }
        DispatchQueue.global().asyncAfter(deadline: .now() + 5, execute: timeout)
        defer { timeout.cancel() }
        for _ in 0..<32 {
            guard case .string(let text) = try await socket.receive(),
                  let wrapper = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
                  let encoded = wrapper["data"] as? String, let packet = Data(base64Encoded: encoded),
                  let json = try JSONSerialization.jsonObject(with: channel.open(packet, kind: 1)) as? [String: Any] else { throw SecureChannelError.invalidFrame }
            if json["type"] as? String == type { return json }
            if json["type"] as? String == "ping" { try await send(["type": "pong", "t": json["t"] ?? 0]) }
            else if json["type"] as? String != "power" { controls.append(json) }
        }
        throw SecureChannelError.invalidFrame
    }
    func close() { socket.cancel(with: .goingAway, reason: nil); session.invalidateAndCancel() }
}
