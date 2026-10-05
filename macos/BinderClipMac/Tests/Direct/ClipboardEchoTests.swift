import XCTest
@testable import BinderClip

final class ClipboardEchoTests: XCTestCase {
    func testTextCanBeCopiedAgainAfterAnImage() {
        var echo = ClipboardEchoFilter()
        echo.received("text:a")
        XCTAssertTrue(echo.shouldPublish("image:b"))
        XCTAssertTrue(echo.shouldPublish("text:a"))
    }
    func testPriorTextCanBeCopiedAfterDifferentLocalText() {
        var echo = ClipboardEchoFilter()
        echo.received("text:a")
        XCTAssertTrue(echo.shouldPublish("text:b"))
        XCTAssertTrue(echo.shouldPublish("text:a"))
    }
    func testEchoIsSuppressedOnceAndRetainsItsType() {
        var echo = ClipboardEchoFilter()
        echo.received("image:a")
        XCTAssertFalse(echo.shouldPublish("image:a"))
        XCTAssertTrue(echo.shouldPublish("image:a"))
        echo.received("image:a")
        XCTAssertTrue(echo.shouldPublish("text:a"))
    }
}
