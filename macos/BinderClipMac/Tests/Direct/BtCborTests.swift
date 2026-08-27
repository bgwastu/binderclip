import XCTest
@testable import BinderClip

final class BtCborTests: XCTestCase {
    private func hex(_ value: String) -> [UInt8] {
        var bytes: [UInt8] = []
        var iterator = value.makeIterator()
        while let high = iterator.next(), let low = iterator.next() {
            bytes.append(UInt8(String([high, low]), radix: 16)!)
        }
        return bytes
    }

    private func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    func testEncodesReferenceGoldenVectors() throws {
        let cases: [([(String, BtValue)], String)] = [
            ([
                ("type", .text("clipboard")),
                ("text", .text("")),
            ], "a2647479706569636c6970626f617264647465787460"),
            ([
                ("type", .text("unpair")),
            ], "a1647479706566756e70616972"),
            ([
                ("type", .text("pong")),
                ("t", .uint(42)),
            ], "a2647479706564706f6e676174182a"),
            ([
                ("type", .text("ping")),
                ("t", .uint(1_787_719_445_677)),
            ], "a264747970656470696e6761741b000001a03c61ecad"),
            ([
                ("type", .text("clipboard")),
                ("text", .text("héllo 世界 📋")),
            ], "a2647479706569636c6970626f61726464746578747268c3a96c6c6f20e4b896e7958c20f09f938b"),
        ]
        for (fields, expectedHex) in cases {
            XCTAssertEqual(expectedHex, hex(try BtCbor.encode(fields)))
        }
    }

    func testEncodesAuthFrameWithAllHeadSizes() throws {
        let encoded = try BtCbor.encode([
            ("type", .text("auth")),
            ("psk", .text("0N1jn4tZ6L9UqiEwPWDUaJsZVwyVpESBlTA8V5mWcik")),
            ("deviceId", .text("006f94c0-2324-493f-a9a2-17410daa6108")),
            ("deviceName", .text("Atlas")),
            ("version", .uint(2)),
        ])
        XCTAssertEqual(
            "a5647479706564617574686370736b782b304e316a6e34745a364c39557169457750574455614a735a56777956704553426c54413856356d5763696b686465766963654964782430303666393463302d323332342d343933662d613961322d3137343130646161363130386a6465766963654e616d656541746c61736776657273696f6e02",
            hex(encoded)
        )
    }

    func testEncodesAuthOkWithStringArray() throws {
        let encoded = try BtCbor.encode([
            ("type", .text("auth_ok")),
            ("deviceId", .text("A6642397-8317-4657-A41C-221C84ABBD3A")),
            ("deviceName", .text("Air")),
            ("version", .uint(2)),
            ("endpoints", .array(["192.168.50.168:39421", "100.96.0.7:39421"])),
        ])
        XCTAssertEqual(
            "a5647479706567617574685f6f6b686465766963654964782441363634323339372d383331372d343635372d413431432d3232314338344142424433416a6465766963654e616d65634169726776657273696f6e0269656e64706f696e747382743139322e3136382e35302e3136383a3339343231703130302e39362e302e373a3339343231",
            hex(encoded)
        )
    }

    func testDecodesGoldenVectors() throws {
        let decoded = try BtCbor.decode(hex(
            "a5647479706567617574685f6f6b686465766963654964782441363634323339372d383331372d343635372d413431432d3232314338344142424433416a6465766963654e616d65634169726776657273696f6e0269656e64706f696e747382743139322e3136382e35302e3136383a3339343231703130302e39362e302e373a3339343231"
        ))
        XCTAssertEqual(.text("auth_ok"), decoded.first?.1)
        XCTAssertEqual(.array(["192.168.50.168:39421", "100.96.0.7:39421"]), decoded.last?.1)

        let ping = try BtCbor.decode(hex("a264747970656470696e6761741b000001a03c61ecad"))
        XCTAssertEqual(.uint(1_787_719_445_677), ping.last?.1)
    }

    func testRoundTripsEveryHeadSizeForText() throws {
        for length in [0, 1, 23, 24, 255, 256, 65_535, 65_536, 70_000] {
            let text = String(repeating: "x", count: length)
            let decoded = try BtCbor.decode(BtCbor.encode([
                ("type", .text("clipboard")),
                ("text", .text(text)),
            ]))
            XCTAssertEqual(2, decoded.count)
            XCTAssertEqual(.text(text), decoded[1].1)
        }
    }

    func testRejectsUnsupportedAndTruncatedInput() {
        XCTAssertThrowsError(try BtCbor.decode(hex("c07474797065")))      // tag major
        XCTAssertThrowsError(try BtCbor.decode(hex("a201026142")))        // int key
        XCTAssertThrowsError(try BtCbor.decode(hex("a26474797065")))      // truncated
        XCTAssertThrowsError(try BtCbor.decode([]))
        XCTAssertThrowsError(try BtCbor.encode(Array(repeating: ("k", BtValue.text("v")), count: 24)))
    }

    func testFramingPrefixesLengthAndReassemblesChunks() throws {
        let payload = try BtCbor.encode([
            ("type", .text("power")),
            ("state", .text("sleep")),
        ])
        let framed = try BtFrameIo.frame(payload: payload)
        var expectedLength = 0
        for byte in framed[0..<4] { expectedLength = (expectedLength << 8) | Int(byte) }
        XCTAssertEqual(payload.count, expectedLength)

        var reader = BtFrameIo.Reader()
        var emitted: [[UInt8]] = []
        var index = 0
        while index < framed.count {
            let end = min(index + 3, framed.count)
            emitted = try reader.feed(Array(framed[index..<end]))
            index = end
        }
        XCTAssertEqual(1, emitted.count)
        XCTAssertEqual(payload, emitted[0])

        let second = try BtFrameIo.frame(payload: [0x01])
        let both = try reader.feed(second + second)
        XCTAssertEqual(2, both.count)
        XCTAssertEqual([UInt8(0x01)], both[1])
    }

    func testFramingRejectsOversizeFrames() {
        var reader = BtFrameIo.Reader()
        XCTAssertThrowsError(try reader.feed([0x00, 0x17, 0x00, 0x00])) // 1_500_001
        XCTAssertThrowsError(try BtFrameIo.frame(payload: Array(repeating: 0, count: BtFrameIo.maximumFrameBytes + 1)))
    }
}
