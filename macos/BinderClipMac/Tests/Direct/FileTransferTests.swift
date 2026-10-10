import CryptoKit
import Foundation
import XCTest
@testable import BinderClip

final class FileTransferTests: XCTestCase {
    private let id = "00000000-0000-0000-0000-000000000001"
    private func isolated() throws -> (URL, FileTransferStore) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return (root, FileTransferStore(root: root.appendingPathComponent("state"), receivedDirectory: root.appendingPathComponent("received")))
    }
    private func meta(_ data: Data, name: String = "report.txt") -> TransferMetadata {
        TransferMetadata(id: id, kind: "file", name: name, mimeType: "text/plain", size: Int64(data.count),
            sha256: SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined())
    }

    func testChunkMatchesCrossPlatformWireVectorAndRejectsMalformedFrames() throws {
        let packet = try FileTransferProtocol.packChunk(id: id, offset: 524288, data: Data([1, 2, 3]))
        let expected = "42435431" + "30303030303030302d303030302d303030302d303030302d303030303030303030303031" + "0000000000080000" + "010203"
        XCTAssertEqual(packet.map { String(format: "%02x", $0) }.joined(), expected)
        let (actualID, offset, body) = try FileTransferProtocol.unpackChunk(packet)
        XCTAssertEqual(actualID, id)
        XCTAssertEqual(offset, 524288)
        XCTAssertEqual(body, Data([1, 2, 3]))
        XCTAssertThrowsError(try FileTransferProtocol.unpackChunk(Data(packet.prefix(48))))
        var invalid = packet; invalid[40] = 0x80
        XCTAssertThrowsError(try FileTransferProtocol.unpackChunk(invalid))
        XCTAssertThrowsError(try FileTransferProtocol.packChunk(id: id, offset: 0, data: Data(count: FileTransferProtocol.chunkBytes + 1)))
    }

    func testMetadataRejectsTraversalInvalidTypesAndOversizedFiles() throws {
        let valid = meta(Data())
        let invalid: [(String, Any)] = [("name", "../report.txt"), ("name", "a\\b"), ("size", -1),
            ("size", FileTransferProtocol.maximumFileBytes + 1), ("size", "12"), ("size", true), ("size", 1.5),
            ("id", "../../other"), ("sha256", "wrong"), ("mimeType", "../file"), ("kind", "executable")]
        for (key, value) in invalid {
            var json = valid.json; json[key] = value
            XCTAssertThrowsError(try TransferMetadata.parse(json), "\(key)=\(value)")
        }
        XCTAssertEqual(FileTransferProtocol.safeName("../../photo.png"), "photo.png")
        XCTAssertEqual(FileTransferProtocol.safeName("C:\\private\\report.pdf"), "report.pdf")
        XCTAssertEqual(FileTransferProtocol.safeName(".."), "file")
        XCTAssertLessThanOrEqual(FileTransferProtocol.safeName(String(repeating: "😀", count: 100)).utf8.count, 180)
    }

    func testImageVersionSurvivesRestartAndCatchupDoesNotQueueItTwice() throws {
        let (root, store) = try isolated()
        defer { try? FileManager.default.removeItem(at: root) }
        let image = try ImagePayload(mimeType: "image/png", data: Data([1, 2, 3]))
        let stamp = ClipboardStamp(time: 100, counter: 0, origin: "phone", event: id, kind: "image", hash: image.sha256)
        let original = try store.stageImage(peerID: "phone", image: image, clipboard: stamp)
        let resumed = FileTransferStore(root: store.root, receivedDirectory: store.receivedDirectory)
        let repeated = try resumed.stageImage(peerID: "phone", image: image, clipboard: stamp)
        XCTAssertEqual(original.metadata, repeated.metadata)
        XCTAssertEqual(repeated.metadata.clipboard, stamp)
        var malformed = repeated.metadata.json
        for value in [true as Any, [String: Any](), ["clipTime": 100]] {
            malformed["clipboard"] = value
            XCTAssertThrowsError(try TransferMetadata.parse(malformed))
        }
        malformed = meta(Data([1, 2, 3])).json; malformed["clipboard"] = stamp.fields
        XCTAssertThrowsError(try TransferMetadata.parse(malformed))
        var wrongHash = stamp.fields; wrongHash["clipHash"] = SyncProtocol.sha256Hex("wrong")
        malformed = repeated.metadata.json; malformed["clipboard"] = wrongHash
        XCTAssertThrowsError(try TransferMetadata.parse(malformed))
    }

    func testRestartResumesDurableChunksAndIsBoundToPeerAndMetadata() throws {
        let (root, initial) = try isolated()
        defer { try? FileManager.default.removeItem(at: root) }
        let bytes = Data((0..<(FileTransferProtocol.chunkBytes * 2 + 37)).map { UInt8($0 % 251) })
        let metadata = meta(bytes)
        XCTAssertEqual(try initial.accept(peerID: "phone", meta: metadata).offset, 0)
        _ = try initial.append(peerID: "phone", meta: metadata, offset: 0, bytes: Data(bytes.prefix(FileTransferProtocol.chunkBytes)))
        let incoming = initial.root.appendingPathComponent("incoming")
        let partial = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: incoming, includingPropertiesForKeys: nil).first).appendingPathComponent("body")
        let output = try FileHandle(forWritingTo: partial)
        try output.seekToEnd(); try output.write(contentsOf: Data([1, 2, 3])); try output.close()
        let store = FileTransferStore(root: initial.root, receivedDirectory: initial.receivedDirectory)
        XCTAssertEqual(try store.accept(peerID: "phone", meta: metadata).offset, Int64(FileTransferProtocol.chunkBytes))
        var changed = metadata.json; changed["name"] = "different"
        XCTAssertThrowsError(try store.accept(peerID: "phone", meta: TransferMetadata.parse(changed)))
        XCTAssertEqual(try store.accept(peerID: "other-phone", meta: metadata).offset, 0)
        XCTAssertThrowsError(try store.append(peerID: "phone", meta: metadata, offset: 0, bytes: Data(bytes.prefix(FileTransferProtocol.chunkBytes))))
        let offset = try store.append(peerID: "phone", meta: metadata, offset: Int64(FileTransferProtocol.chunkBytes), bytes: bytes.subdata(in: FileTransferProtocol.chunkBytes..<(FileTransferProtocol.chunkBytes * 2)))
        _ = try store.append(peerID: "phone", meta: metadata, offset: offset, bytes: Data(bytes.dropFirst(Int(offset))))
        let saved = try store.complete(peerID: "phone", meta: metadata)
        XCTAssertEqual(try Data(contentsOf: saved.file), bytes)
        XCTAssertTrue(try store.accept(peerID: "phone", meta: metadata).completed)
        store.clearPairing()
        XCTAssertEqual(try Data(contentsOf: saved.file), bytes)
    }

    func testCorruptionIsRejectedAndSameNamesNeverOverwriteFiles() throws {
        let (root, store) = try isolated()
        defer { try? FileManager.default.removeItem(at: root) }
        let metadata = meta(Data([4, 5, 6]))
        _ = try store.accept(peerID: "phone", meta: metadata)
        _ = try store.append(peerID: "phone", meta: metadata, offset: 0, bytes: Data([4, 5, 7]))
        XCTAssertThrowsError(try store.complete(peerID: "phone", meta: metadata))
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.receivedDirectory.path))
        for peerID in ["phone", "another-phone"] {
            _ = try store.accept(peerID: peerID, meta: metadata)
            _ = try store.append(peerID: peerID, meta: metadata, offset: 0, bytes: Data([4, 5, 6]))
            _ = try store.complete(peerID: peerID, meta: metadata)
        }
        let files = try FileManager.default.contentsOfDirectory(at: store.receivedDirectory, includingPropertiesForKeys: nil)
        XCTAssertEqual(Set(files.map(\.lastPathComponent)), ["report.txt", "report (2).txt"])
        for file in files { XCTAssertEqual(try Data(contentsOf: file), Data([4, 5, 6])) }
    }

    func testEmptyFileAndDiskOutboxSurviveRestartAndClearOnUnpair() throws {
        let (root, store) = try isolated()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("empty")
        try Data().write(to: source)
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        let staged = try store.stage(peerID: "phone", name: "empty.txt", mimeType: "text/plain", kind: "file", input: input)
        let resumed = FileTransferStore(root: store.root, receivedDirectory: store.receivedDirectory)
        XCTAssertEqual(resumed.next(peerID: "phone")?.metadata, staged.metadata)
        XCTAssertNil(resumed.next(peerID: "different-phone"))
        let receipt = try resumed.accept(peerID: "phone", meta: staged.metadata)
        XCTAssertEqual(receipt.offset, 0)
        let received = try resumed.complete(peerID: "phone", meta: staged.metadata)
        XCTAssertEqual(try Data(contentsOf: received.file), Data())
        resumed.clearPairing(peerID: "phone")
        XCTAssertNil(resumed.next(peerID: "phone"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: received.file.path))
    }

    func testFinalCommitRecoversAfterLostReceiptWithoutCreatingAnotherFile() throws {
        let (root, store) = try isolated()
        defer { try? FileManager.default.removeItem(at: root) }
        let metadata = meta(Data([7, 8]))
        _ = try store.accept(peerID: "phone", meta: metadata)
        _ = try store.append(peerID: "phone", meta: metadata, offset: 0, bytes: Data([7, 8]))
        let file = try store.complete(peerID: "phone", meta: metadata)
        let incoming = store.root.appendingPathComponent("incoming")
        let directory = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: incoming, includingPropertiesForKeys: nil).first)
        var intent = metadata.json
        intent["committing"] = true
        intent["destination"] = file.file.lastPathComponent
        try JSONSerialization.data(withJSONObject: intent).write(to: directory.appendingPathComponent("metadata"), options: .atomic)
        let resumed = FileTransferStore(root: store.root, receivedDirectory: store.receivedDirectory)
        XCTAssertTrue(try resumed.accept(peerID: "phone", meta: metadata).completed)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(at: store.receivedDirectory, includingPropertiesForKeys: nil).count, 1)
    }

    private func startServer(root: URL, defaults: UserDefaults) async throws -> (WebSocketServer, RosterManager, UInt16) {
        let roster = RosterManager(stateDirectory: root, defaults: defaults)
        let server = WebSocketServer(rosterManager: roster, port: 0, bluetoothEnabled: false)
        let ready = expectation(description: "isolated listener ready")
        var port: UInt16?
        server.onListening = { port = $0; ready.fulfill() }
        server.start()
        await fulfillment(of: [ready], timeout: 5)
        return (server, roster, try XCTUnwrap(port))
    }

    func testFileResumesAcrossServerRestartOverEncryptedWebSocket() async throws {
        let (root, _) = try isolated()
        let suite = "binderclip-file-wire-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: root) }
        let (server, roster, port) = try await startServer(root: root, defaults: defaults)
        defer { server.stop() }
        let paused = expectation(description: "interrupted receive publishes durable progress")
        server.onTransferProgress = { progress in
            if progress.phase == .paused {
                XCTAssertEqual(progress.direction, .receiving)
                XCTAssertEqual(progress.completedBytes, Int64(FileTransferProtocol.chunkBytes))
                paused.fulfill()
            }
        }
        let first = try await FileWireClient.connect(port: port, key: roster.groupKey)
        defer { first.close() }
        let bytes = Data((0..<(FileTransferProtocol.chunkBytes * 2 + 37)).map { UInt8($0 % 211) })
        let metadata = meta(bytes)
        try await first.send(metadata.json)
        let firstAck = try await first.control("transfer_ack")
        XCTAssertEqual(firstAck["offset"] as? Int, 0)
        try await first.sendChunk(id: id, offset: 0, data: Data(bytes.prefix(FileTransferProtocol.chunkBytes)))
        let durableAck = try await first.control("transfer_ack")
        XCTAssertEqual(durableAck["offset"] as? Int, FileTransferProtocol.chunkBytes)
        first.close()
        server.stop()
        await fulfillment(of: [paused], timeout: 5)

        let (resumed, resumedRoster, resumedPort) = try await startServer(root: root, defaults: defaults)
        defer { resumed.stop() }
        let received = expectation(description: "file delivered once")
        let progressCompleted = expectation(description: "receive progress completes after verification")
        var progressEvents: [TransferProgress] = []
        resumed.onTransferProgress = { progress in
            progressEvents.append(progress)
            if progress.phase == .received { progressCompleted.fulfill() }
        }
        var receivedURL: URL?
        var count = 0
        resumed.onFile = { url in count += 1; receivedURL = url; received.fulfill() }
        let second = try await FileWireClient.connect(port: resumedPort, key: resumedRoster.groupKey)
        defer { second.close() }
        try await second.send(metadata.json)
        let resumedAck = try await second.control("transfer_ack")
        let offset = try XCTUnwrap(resumedAck["offset"] as? Int)
        XCTAssertEqual(offset, FileTransferProtocol.chunkBytes)
        try await second.sendChunk(id: id, offset: Int64(offset), data: bytes.subdata(in: offset..<(offset + FileTransferProtocol.chunkBytes)))
        let nextAck = try await second.control("transfer_ack")
        let lastOffset = try XCTUnwrap(nextAck["offset"] as? Int)
        try await second.sendChunk(id: id, offset: Int64(lastOffset), data: Data(bytes.dropFirst(lastOffset)))
        let done = try await second.control("transfer_done")
        XCTAssertEqual(done["id"] as? String, id)
        await fulfillment(of: [received, progressCompleted], timeout: 5)
        XCTAssertEqual(progressEvents.map(\.phase), [.receiving, .receiving, .verifying, .received])
        XCTAssertEqual(progressEvents.first?.completedBytes, Int64(FileTransferProtocol.chunkBytes))
        XCTAssertEqual(progressEvents.last?.completedBytes, metadata.size)
        XCTAssertEqual(progressEvents.last?.fraction, 1)
        XCTAssertTrue(progressEvents.allSatisfy { $0.metadata.name == metadata.name && $0.direction == .receiving })
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(receivedURL)), bytes)
        resumed.onTransferProgress = nil
        try await second.send(metadata.json)
        let repeatedDone = try await second.control("transfer_done")
        XCTAssertEqual(repeatedDone["id"] as? String, id)
        XCTAssertEqual(count, 1)
    }

    func testMacSendsLargeImageAndExplicitFileWithAcknowledgedEncryptedChunks() async throws {
        let (root, _) = try isolated()
        let suite = "binderclip-send-wire-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: root) }
        let (server, roster, port) = try await startServer(root: root, defaults: defaults)
        defer { server.stop() }
        let client = try await FileWireClient.connect(port: port, key: roster.groupKey)
        defer { client.close() }
        XCTAssertTrue(server.canSendFile(to: "wire-phone"))
        let data = Data((0..<(17 * 1024 * 1024 + 113)).map { UInt8($0 % 251) })
        let image = try ImagePayload(mimeType: "image/png", data: data)
        let imageSent = expectation(description: "image acknowledged")
        server.onTransferStatus = { if $0 == L10n.tr("transfer_sent", L10n.tr("transfer_kind_image")) { imageSent.fulfill() } }
        server.sendImage(image, targetDeviceId: "wire-phone")
        let imageOffer = try TransferMetadata.parse(try await client.control("transfer_offer"))
        XCTAssertEqual(imageOffer.kind, "image")
        XCTAssertEqual(imageOffer.size, Int64(data.count))
        let imageData = try await client.receiveTransfer(imageOffer)
        XCTAssertEqual(imageData, data)
        await fulfillment(of: [imageSent], timeout: 5)

        let source = root.appendingPathComponent("sample.pdf")
        let fileBytes = Data((0..<(FileTransferProtocol.chunkBytes * 2 + 5)).map { UInt8($0 % 239) })
        try fileBytes.write(to: source)
        let fileSent = expectation(description: "file acknowledged")
        let progressCompleted = expectation(description: "send progress completes only after final acknowledgement")
        var progressEvents: [TransferProgress] = []
        server.onTransferProgress = { progress in
            guard progress.metadata.kind == "file" else { return }
            progressEvents.append(progress)
            if progress.phase == .sent { progressCompleted.fulfill() }
        }
        server.onTransferStatus = { if $0 == L10n.tr("transfer_sent", L10n.tr("transfer_kind_file")) { fileSent.fulfill() } }
        server.sendFile(source, targetDeviceID: "wire-phone")
        let fileOffer = try TransferMetadata.parse(try await client.control("transfer_offer"))
        XCTAssertEqual(fileOffer.kind, "file")
        XCTAssertEqual(fileOffer.name, "sample.pdf")
        let receivedFile = try await client.receiveTransfer(fileOffer)
        XCTAssertEqual(receivedFile, fileBytes)
        await fulfillment(of: [fileSent, progressCompleted], timeout: 5)
        XCTAssertEqual(progressEvents.map(\.phase), [.preparing, .sending, .sending, .sending, .sent])
        XCTAssertEqual(progressEvents.filter { $0.phase == .sending }.map(\.completedBytes),
                       [0, Int64(FileTransferProtocol.chunkBytes), Int64(FileTransferProtocol.chunkBytes * 2)])
        XCTAssertTrue(progressEvents.filter { $0.isActive }.allSatisfy { $0.fraction < 1 })
        XCTAssertEqual(progressEvents.last?.fraction, 1)
        XCTAssertTrue(progressEvents.allSatisfy { $0.metadata.name == "sample.pdf" && $0.direction == .sending })
    }
}

private final class FileWireClient {
    private let session: URLSession
    private let socket: URLSessionWebSocketTask
    private let channel: SecureChannel
    private init(session: URLSession, socket: URLSessionWebSocketTask, channel: SecureChannel) {
        self.session = session; self.socket = socket; self.channel = channel
    }
    private static func text(_ object: [String: Any]) throws -> String { String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self) }
    static func connect(port: UInt16, key: Data) async throws -> FileWireClient {
        let session = URLSession(configuration: .ephemeral)
        let socket = session.webSocketTask(with: URL(string: "ws://127.0.0.1:\(port)")!)
        socket.resume()
        guard case .string(let hello) = try await socket.receive(),
              let json = try JSONSerialization.jsonObject(with: Data(hello.utf8)) as? [String: Any],
              let nonceString = json["nonce"] as? String, let nonce = Data(base64Encoded: nonceString) else { throw SecureChannelError.invalidHandshake }
        let clientNonce = SecureHandshake().nonce
        let channel = try SecureChannel(psk: key, serverNonce: nonce, clientNonce: clientNonce, transport: "ws", server: false)
        let proof = SecureChannel.proof(psk: key, serverNonce: nonce, clientNonce: clientNonce, transport: "ws")
        try await socket.send(.string(text(["type": "secure_reply", "nonce": clientNonce.base64EncodedString(), "proof": proof.base64EncodedString()])))
        let result = FileWireClient(session: session, socket: socket, channel: channel)
        try await result.send(["type": "auth", "deviceId": "wire-phone", "deviceName": "Test Phone", "version": 3, "pairing": true, "chunkedTransfers": 1])
        let auth = try await result.control("auth_ok")
        guard auth["chunkedTransfers"] as? Int == 1 else { throw SecureChannelError.invalidHandshake }
        return result
    }
    func send(_ object: [String: Any]) async throws {
        let packet = try channel.seal(JSONSerialization.data(withJSONObject: object), kind: 1)
        try await socket.send(.string(Self.text(["type": "secure", "data": packet.base64EncodedString()])))
    }
    func sendChunk(id: String, offset: Int64, data: Data) async throws {
        try await socket.send(.data(channel.seal(FileTransferProtocol.packChunk(id: id, offset: offset, data: data), kind: 2)))
    }
    private func receive() async throws -> (json: [String: Any]?, data: Data?) {
        switch try await socket.receive() {
        case .data(let packet): return (nil, try channel.open(packet, kind: 2))
        case .string(let text):
            guard let wrapper = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
                  let encoded = wrapper["data"] as? String, let packet = Data(base64Encoded: encoded),
                  let json = try JSONSerialization.jsonObject(with: channel.open(packet, kind: 1)) as? [String: Any] else { throw SecureChannelError.invalidFrame }
            return (json, nil)
        @unknown default: throw SecureChannelError.invalidFrame
        }
    }
    func control(_ type: String) async throws -> [String: Any] {
        for _ in 0..<10 {
            let message = try await receive()
            guard let json = message.json else { throw SecureChannelError.invalidFrame }
            if json["type"] as? String == type { return json }
            if json["type"] as? String == "ping" { try await send(["type": "pong", "t": json["t"] ?? 0]) }
            else if json["type"] as? String != "power" { throw SecureChannelError.invalidFrame }
        }
        throw SecureChannelError.invalidFrame
    }
    func receiveTransfer(_ meta: TransferMetadata) async throws -> Data {
        var result = Data()
        try await send(["type": "transfer_ack", "id": meta.id, "offset": 0])
        while result.count < meta.size {
            let message = try await receive()
            if let json = message.json, json["type"] as? String == "ping" {
                try await send(["type": "pong", "t": json["t"] ?? 0]); continue
            }
            let packet = try XCTUnwrap(message.data)
            XCTAssertLessThanOrEqual(packet.count, FileTransferProtocol.headerBytes + FileTransferProtocol.chunkBytes)
            let (id, offset, bytes) = try FileTransferProtocol.unpackChunk(packet)
            XCTAssertEqual(id, meta.id); XCTAssertEqual(offset, Int64(result.count))
            result.append(bytes)
            if result.count < meta.size { try await send(["type": "transfer_ack", "id": id, "offset": result.count]) }
            else { try await send(["type": "transfer_done", "id": id]) }
        }
        return result
    }
    func close() { socket.cancel(with: .goingAway, reason: nil); session.invalidateAndCancel() }
}
