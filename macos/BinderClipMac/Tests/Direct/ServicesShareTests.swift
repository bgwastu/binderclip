import XCTest
@testable import BinderClip

final class ServicesShareTests: XCTestCase {
    func testWebURLAcceptsHttps() {
        XCTAssertEqual(
            ServicesShare.webURL(from: "  https://example.com/path "),
            URL(string: "https://example.com/path")
        )
    }

    func testWebURLRejectsPlainText() {
        XCTAssertNil(ServicesShare.webURL(from: "hello world"))
    }
}
