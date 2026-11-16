import XCTest
@testable import BinderClip

final class BrowserTabReaderTests: XCTestCase {
    func testFrontmostBrowserIsAskedFirst() {
        let candidates = BrowserTabReader.candidates(
            running: ["com.google.Chrome", "com.apple.Safari"],
            frontmost: "com.apple.Safari"
        )
        XCTAssertEqual(candidates.map(\.name), ["Safari", "Google Chrome"])
    }

    func testNonBrowserFrontmostDoesNotChangeTheOrder() {
        let candidates = BrowserTabReader.candidates(
            running: ["com.apple.Safari", "com.google.Chrome"],
            frontmost: "com.apple.Terminal"
        )
        XCTAssertEqual(candidates.map(\.name), ["Google Chrome", "Safari"])
    }

    func testOnlyRunningAndSupportedBrowsersAreAsked() {
        let candidates = BrowserTabReader.candidates(
            running: ["com.google.Chrome", "com.example.NotABrowser"],
            frontmost: nil
        )
        XCTAssertEqual(candidates.map(\.bundleId), ["com.google.Chrome"])
    }

    func testReadsAtMostTwoBrowsers() {
        let running = Set(BrowserTabReader.browsers.map(\.bundleId))
        XCTAssertEqual(BrowserTabReader.candidates(running: running, frontmost: nil).count, 2)
        XCTAssertEqual(BrowserTabReader.candidates(running: running, frontmost: "com.vivaldi.Vivaldi").count, 2)
        XCTAssertEqual(BrowserTabReader.candidates(running: running, frontmost: "com.vivaldi.Vivaldi").first?.bundleId, "com.vivaldi.Vivaldi")
    }

    func testChromiumAndSafariUseDifferentScripts() {
        let chrome = BrowserTabReader.browsers.first { $0.bundleId == "com.google.Chrome" }!
        let safari = BrowserTabReader.browsers.first { $0.bundleId == "com.apple.Safari" }!
        XCTAssertTrue(BrowserTabReader.script(for: chrome).contains("active tab of front window"))
        XCTAssertTrue(BrowserTabReader.script(for: safari).contains("URL of front document"))
        XCTAssertTrue(BrowserTabReader.script(for: safari).contains("com.apple.Safari"))
    }

    func testOnlyWebPagesAreAccepted() throws {
        XCTAssertEqual(BrowserTabReader.url(fromAppleScriptResult: "  https://example.com/a?b=1\n")?.absoluteString,
                       "https://example.com/a?b=1")
        XCTAssertEqual(BrowserTabReader.url(fromAppleScriptResult: "http://192.168.1.5/")?.absoluteString,
                       "http://192.168.1.5/")
        XCTAssertNil(BrowserTabReader.url(fromAppleScriptResult: nil))
        XCTAssertNil(BrowserTabReader.url(fromAppleScriptResult: ""))
        XCTAssertNil(BrowserTabReader.url(fromAppleScriptResult: "   "))
        XCTAssertNil(BrowserTabReader.url(fromAppleScriptResult: "file:///etc/passwd"))
        XCTAssertNil(BrowserTabReader.url(fromAppleScriptResult: "javascript:alert(1)"))
        XCTAssertNil(BrowserTabReader.url(fromAppleScriptResult: "not a url"))
    }

    func testSupportedBundleIds() {
        XCTAssertTrue(BrowserTabReader.isSupported(bundleId: "com.google.Chrome"))
        XCTAssertFalse(BrowserTabReader.isSupported(bundleId: "com.apple.Terminal"))
    }
}
