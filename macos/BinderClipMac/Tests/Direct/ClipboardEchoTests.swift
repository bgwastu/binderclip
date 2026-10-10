import AppKit
import XCTest
@testable import BinderClip

final class ClipboardEchoTests: XCTestCase {
    private func board() -> NSPasteboard { NSPasteboard(name: .init("binderclip-test-\(UUID().uuidString)")) }

    @MainActor func testRemoteWriteDoesNotEchoButAnIdenticalUserCopyDoes() {
        let board = board()
        defer { board.releaseGlobally() }
        let bridge = ClipboardBridge(pasteboard: board)
        var copies: [(String, Bool)] = []
        bridge.onLocalText = { copies.append(($0, $1)) }
        XCTAssertTrue(bridge.applyRemote("a"))
        bridge.captureChanges()
        XCTAssertTrue(copies.isEmpty)
        board.clearContents(); board.setString("a", forType: .string)
        bridge.captureChanges()
        XCTAssertEqual(copies.map(\.0), ["a"])
        XCTAssertFalse(copies[0].1)
        bridge.captureChanges()
        XCTAssertEqual(copies.count, 1)
    }

    @MainActor func testOfflineCopyIsCapturedAndStartupIsMarkedUnknown() {
        let board = board()
        defer { board.releaseGlobally() }
        board.clearContents(); board.setString("old", forType: .string)
        let bridge = ClipboardBridge(pasteboard: board)
        var copies: [(String, Bool)] = []
        bridge.onLocalText = { copies.append(($0, $1)) }
        bridge.captureChanges()
        XCTAssertEqual(copies.first?.0, "old")
        XCTAssertEqual(copies.first?.1, true)
        board.clearContents(); board.setString("new", forType: .string)
        bridge.captureChanges()
        XCTAssertEqual(copies.last?.0, "new")
        XCTAssertEqual(copies.last?.1, false)
    }

    @MainActor func testAnUnobservedLocalCopyCannotBeOverwrittenByAQueuedRemoteWrite() {
        let board = board()
        defer { board.releaseGlobally() }
        let bridge = ClipboardBridge(pasteboard: board)
        bridge.captureChanges()
        board.clearContents(); board.setString("local", forType: .string)
        var captured: String?
        bridge.onLocalText = { text, _ in captured = text }
        XCTAssertFalse(bridge.applyRemote("remote"))
        XCTAssertEqual(captured, "local")
        XCTAssertEqual(board.string(forType: .string), "local")
    }
}
