import XCTest
@testable import BinderClip

final class BonjourTests: XCTestCase {
    func testTXTRecordsCarryIdentity() {
        let txt = WebSocketServer.bonjourTXTRecords(deviceID: "mac-studio-123", deviceName: "Studio Mac", version: 2)
        XCTAssertEqual(txt["id"], "mac-studio-123")
        XCTAssertEqual(txt["name"], "Studio Mac")
        XCTAssertEqual(txt["v"], "2")
    }

    func testMakeBonjourServiceUsesBinderclipType() {
        let service = WebSocketServer.makeBonjourService(deviceID: "mac-1", deviceName: "Mac")
        XCTAssertEqual(service.type, "_binderclip._tcp")
        XCTAssertEqual(service.name, "Mac")
    }
}
