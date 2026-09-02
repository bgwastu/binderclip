import XCTest
@testable import BinderClip

final class LocalizationTests: XCTestCase {
    func testLocalizationKeys() {
        let text = L10n.tr("listening")
        XCTAssertFalse(text.isEmpty)

        let connectedOne = L10n.tr("connected_count_one")
        XCTAssertFalse(connectedOne.isEmpty)

        let connectedMany = L10n.tr("connected_count_many", 2)
        XCTAssertTrue(connectedMany.contains("2"))

        let waitDevice = L10n.tr("waiting_for_device_named", "Pixel")
        XCTAssertTrue(waitDevice.contains("Pixel"))
    }
}
