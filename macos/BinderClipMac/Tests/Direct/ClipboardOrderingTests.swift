import XCTest
@testable import BinderClip

final class ClipboardOrderingTests: XCTestCase {
    private func stamp(_ time: Int64, origin: String = "phone-a", event: String = "00000000-0000-0000-0000-000000000001", kind: String = "text", hash: String = SyncProtocol.sha256Hex("a")) -> ClipboardStamp {
        ClipboardStamp(time: time, counter: 0, origin: origin, event: event, kind: kind, hash: hash)
    }
    func testConflictsConvergeRegardlessOfArrivalOrder() {
        let a = stamp(100)
        let b = stamp(100, origin: "phone-b", event: "00000000-0000-0000-0000-000000000002", hash: SyncProtocol.sha256Hex("b"))
        let one = ClipboardOrdering(localID: "mac"), two = ClipboardOrdering(localID: "mac")
        XCTAssertTrue(one.receive(a)); XCTAssertTrue(one.receive(b))
        XCTAssertTrue(two.receive(b)); XCTAssertFalse(two.receive(a))
        XCTAssertEqual(one.head, b); XCTAssertEqual(one.head, two.head)
    }
    func testRecopyAndTextAfterImageRemainPublishableDespiteClockRollback() {
        var wall: Int64 = 100
        let ordering = ClipboardOrdering(localID: "mac", wall: { wall })
        let hash = SyncProtocol.sha256Hex("a")
        let a = ordering.observe(kind: "text", hash: hash)!
        XCTAssertNil(ordering.observe(kind: "text", hash: hash))
        let recopied = ordering.observe(kind: "text", hash: hash, force: true)!
        XCTAssertGreaterThan(recopied, a)
        wall = 1
        let image = ordering.observe(kind: "image", hash: hash)!
        let text = ordering.observe(kind: "text", hash: hash)!
        XCTAssertGreaterThan(text, image)
        XCTAssertGreaterThan(image, recopied)
        XCTAssertFalse(ordering.receive(a))
    }
    func testCheckpointRejectsOldReplayAndLateImageDoesNotOverwriteNewText() {
        let ordering = ClipboardOrdering(localID: "mac")
        let image = stamp(100, kind: "image")
        let text = stamp(200, event: "00000000-0000-0000-0000-000000000002", hash: SyncProtocol.sha256Hex("b"))
        XCTAssertTrue(ordering.receive(image)); XCTAssertTrue(ordering.receive(text))
        XCTAssertFalse(ordering.receive(image))
        XCTAssertFalse(ordering.receive(stamp(300, kind: "image")))
        let restored = ClipboardOrdering(localID: "mac", checkpoint: ordering.checkpoint)
        XCTAssertFalse(restored.receive(image))
        XCTAssertEqual(restored.head, text)
    }
    func testNewerCopyWithSameBytesAdvancesMetadataWithoutApplyingAgain() {
        let ordering = ClipboardOrdering(localID: "mac")
        let a = stamp(100)
        let b = stamp(200, event: "00000000-0000-0000-0000-000000000002")
        XCTAssertTrue(ordering.receive(a))
        XCTAssertFalse(ordering.receive(b))
        XCTAssertEqual(ordering.head, b)
    }
    func testUnknownStartupContentDoesNotDefeatAnOfflineCopy() {
        let ordering = ClipboardOrdering(localID: "mac", wall: { 9_000 })
        XCTAssertNil(ordering.observe(kind: "empty", hash: SyncProtocol.sha256Hex(Data()), bootstrap: true))
        XCTAssertEqual(ordering.observe(kind: "text", hash: SyncProtocol.sha256Hex("old"), bootstrap: true)?.time, 0)
        XCTAssertTrue(ordering.receive(stamp(100)))
        let restored = ClipboardOrdering(localID: "mac", checkpoint: ordering.checkpoint, wall: { 90_000 })
        XCTAssertEqual(restored.observe(kind: "text", hash: SyncProtocol.sha256Hex("unknown"), bootstrap: true)?.time, 100)
        XCTAssertTrue(restored.receive(stamp(200, event: "00000000-0000-0000-0000-000000000002")))
    }
    func testVersionWireAndBluetoothFieldsMatchAndMalformedMetadataFailsClosed() throws {
        let version = stamp(100)
        XCTAssertEqual(try ClipboardStamp.parse(version.fields), version)
        var json = version.fields; json["type"] = "clipboard_state"; json["clipAvailable"] = 1
        let fields = try XCTUnwrap(WebSocketServer.btFields(from: json))
        let decoded = try BtCbor.decode(BtCbor.encode(fields))
        XCTAssertEqual(decoded.first { $0.0 == "clipTime" }?.1, .uint(100))
        XCTAssertEqual(decoded.first { $0.0 == "clipEvent" }?.1, .text(version.event))
        XCTAssertEqual(decoded.first { $0.0 == "clipAvailable" }?.1, .uint(1))
        for (key, value) in [("clipTime", true as Any), ("clipCounter", 1.5), ("clipOrigin", "bad origin"), ("clipEvent", "../file"), ("clipKind", "file"), ("clipHash", "wrong")] {
            var invalid = version.fields; invalid[key] = value
            XCTAssertThrowsError(try ClipboardStamp.parse(invalid))
        }
        XCTAssertThrowsError(try ClipboardStamp.parse(["clipTime": 100]))
    }
}
