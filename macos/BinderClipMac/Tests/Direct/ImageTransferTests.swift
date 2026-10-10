import AppKit
import XCTest
@testable import BinderClip

final class ImageTransferTests: XCTestCase {
    func testAcceptsSupportedPNGAndHashesItsBytes() throws {
        let data = try XCTUnwrap(Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL2OwAAAABJRU5ErkJggg=="))
        let image = try ImagePayload(mimeType: "image/png", data: data)

        XCTAssertEqual(image.data, data)
        XCTAssertEqual(image.mimeType, "image/png")
        XCTAssertEqual(image.sha256.count, 64)
    }

    func testRejectsUnsupportedMediaTypes() {
        XCTAssertThrowsError(try ImagePayload(mimeType: "image/gif", data: Data([1])))
    }

    @MainActor func testImageWriteDoesNotClaimClipboardAfterAnotherCopy() throws {
        let board = NSPasteboard.withUniqueName()
        defer { board.releaseGlobally() }
        let before = board.changeCount
        board.clearContents(); board.setString("local copy", forType: .string)
        let image = try ImagePayload(mimeType: "image/png", data: Data([1, 2, 3]))
        XCTAssertNil(ImageClipboard.write(image, to: board, expectedChangeCount: before))
        XCTAssertEqual(board.string(forType: .string), "local copy")
    }
}
