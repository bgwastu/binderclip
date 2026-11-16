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

final class LocalizationParityTests: XCTestCase {
    private let packageRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    private func table(_ locale: String) throws -> [String: String] {
        let url = packageRoot.appendingPathComponent("Resources/\(locale).lproj/Localizable.strings")
        let dict = NSDictionary(contentsOf: url) as? [String: String]
        return try XCTUnwrap(dict, "Could not parse \(locale)")
    }

    private func placeholders(_ value: String) -> [String] {
        let regex = try! NSRegularExpression(pattern: "%(\\d+\\$)?[@dlu]+")
        return regex.matches(in: value, range: NSRange(value.startIndex..., in: value))
            .map { String(value[Range($0.range, in: value)!]) }.sorted()
    }

    func testEveryLocaleHasTheSameKeysAndPlaceholders() throws {
        let english = try table("en")
        for locale in ["id", "es", "zh-Hans", "ja", "de", "fr", "pt-BR", "ru"] {
            let other = try table(locale)
            XCTAssertEqual(Set(other.keys), Set(english.keys), "\(locale) keys differ from en")
            for (key, value) in english {
                guard let translated = other[key] else { continue }
                XCTAssertEqual(placeholders(translated), placeholders(value), "\(locale) \(key) placeholders differ")
            }
        }
    }

    func testEveryKeyUsedInCodeExists() throws {
        let english = try table("en")
        let sources = packageRoot.appendingPathComponent("Sources")
        let regex = try NSRegularExpression(pattern: "L10n\\.tr\\(\"([a-z0-9_]+)\"")
        let files = FileManager.default.enumerator(at: sources, includingPropertiesForKeys: nil)?
            .compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" } ?? []
        XCTAssertFalse(files.isEmpty)
        for file in files {
            let code = try String(contentsOf: file, encoding: .utf8)
            for match in regex.matches(in: code, range: NSRange(code.startIndex..., in: code)) {
                let key = String(code[Range(match.range(at: 1), in: code)!])
                XCTAssertNotNil(english[key], "\(file.lastPathComponent) uses missing key \(key)")
            }
        }
    }
}
