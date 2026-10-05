import XCTest
@testable import BinderClip

final class SyncProtocolTests: XCTestCase {
    func testPairingUrlRoundTripUrlSafePsk() {
        let id = "mac-unique-id-123"
        let name = "Wastu's MacBook Pro"
        let rawKey = Data((0..<32).map { UInt8($0) })
        let psk = SyncProtocol.urlSafeBase64(rawKey)
        XCTAssertFalse(psk.contains("+"))
        XCTAssertFalse(psk.contains("/"))
        XCTAssertFalse(psk.contains("="))
        let endpoints = ["192.168.1.50:39421", "100.64.0.1:39421"]

        guard let url = SyncProtocol.createPairingURL(deviceId: id, deviceName: name, psk: psk, endpoints: endpoints) else {
            XCTFail("Failed to create pairing URL")
            return
        }

        guard let parsed = SyncProtocol.parsePairingURL(url.absoluteString) else {
            XCTFail("Failed to parse pairing URL")
            return
        }

        XCTAssertEqual(parsed.version, SyncProtocol.version)
        XCTAssertEqual(parsed.deviceId, id)
        XCTAssertEqual(parsed.deviceName, name)
        XCTAssertEqual(parsed.psk, psk)
        XCTAssertEqual(parsed.endpoints, endpoints)
        XCTAssertEqual(SyncProtocol.decodeBase64(parsed.psk), rawKey)
    }

    func testDecodeStandardAndUrlSafeBase64() {
        let data = Data([0xfb, 0xff, 0xef] + Array(repeating: UInt8(1), count: 29))
        let standard = data.base64EncodedString()
        let urlSafe = SyncProtocol.urlSafeBase64(data)
        XCTAssertEqual(SyncProtocol.decodeBase64(standard), data)
        XCTAssertEqual(SyncProtocol.decodeBase64(urlSafe), data)
    }

    func testImagePacketPackAndUnpack() throws {
        let imageData = try XCTUnwrap(Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL2OwAAAABJRU5ErkJggg=="))
        let payload = try ImagePayload(mimeType: "image/png", data: imageData)

        let packet = SyncProtocol.packImage(image: payload, originId: "sender-123")
        XCTAssertFalse(packet.isEmpty)

        let unpacked = SyncProtocol.unpackImage(packet)
        XCTAssertNotNil(unpacked)

        guard let (meta, extractedData) = unpacked else { return }
        XCTAssertEqual(meta.mimeType, "image/png")
        XCTAssertEqual(meta.originId, "sender-123")
        XCTAssertEqual(meta.hash, payload.sha256)
        XCTAssertEqual(meta.size, imageData.count)
        XCTAssertEqual(extractedData, imageData)
    }

    func testSha256Hex() {
        let text = "hello binderclip"
        let hash = SyncProtocol.sha256Hex(text)
        XCTAssertEqual(hash.count, 64)
        XCTAssertEqual(hash, SyncProtocol.sha256Hex(Data(text.utf8)))
    }
}
