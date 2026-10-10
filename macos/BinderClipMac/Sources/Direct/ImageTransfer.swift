import AppKit
import CryptoKit
import Foundation
import UniformTypeIdentifiers

public struct ImagePayload: Sendable {
    public static let maximumBytes = 30 * 1_024 * 1_024
    public static let allowedMIMETypes: Set<String> = ["image/png", "image/jpeg", "image/webp", "image/heic"]

    public let id: UUID
    public let mimeType: String
    public let data: Data
    public let sha256: String

    public init(id: UUID = UUID(), mimeType: String, data: Data) throws {
        guard Self.allowedMIMETypes.contains(mimeType), data.count > 0, data.count <= Self.maximumBytes else {
            throw ImageTransferError.invalidImage
        }
        self.id = id
        self.mimeType = mimeType
        self.data = data
        sha256 = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

public enum ImageTransferError: Error { case invalidImage, hashMismatch, protocolViolation }

public enum ImageClipboard {
    public static func read(from pasteboard: NSPasteboard) -> ImagePayload? {
        let candidates: [(NSPasteboard.PasteboardType, String)] = [
            (.png, "image/png"),
            (NSPasteboard.PasteboardType("public.jpeg"), "image/jpeg"),
            (NSPasteboard.PasteboardType("org.webmproject.webp"), "image/webp"),
            (NSPasteboard.PasteboardType("public.heic"), "image/heic"),
        ]
        for (type, mime) in candidates where pasteboard.types?.contains(type) == true {
            if let data = pasteboard.data(forType: type), let image = try? ImagePayload(mimeType: mime, data: data) { return image }
        }
        guard let tiff = pasteboard.data(forType: .tiff), let image = NSImage(data: tiff),
              let converted = image.pngData(), let payload = try? ImagePayload(mimeType: "image/png", data: converted) else { return nil }
        return payload
    }

    @discardableResult public static func write(_ payload: ImagePayload, to pasteboard: NSPasteboard, expectedChangeCount: Int? = nil) -> Int? {
        let type: NSPasteboard.PasteboardType = switch payload.mimeType {
        case "image/png": .png
        case "image/jpeg": NSPasteboard.PasteboardType("public.jpeg")
        case "image/webp": NSPasteboard.PasteboardType("org.webmproject.webp")
        case "image/heic": NSPasteboard.PasteboardType("public.heic")
        default: .png
        }
        // Prepare expensive conversions before taking ownership of the clipboard. The generation
        // returned by declareTypes belongs to this write, even if another app copies immediately.
        let tiff = NSImage(data: payload.data)?.tiffRepresentation
        if let expectedChangeCount, pasteboard.changeCount != expectedChangeCount { return nil }
        let generation = pasteboard.declareTypes(tiff == nil ? [type] : [type, .tiff], owner: nil)
        guard pasteboard.changeCount == generation else { return generation }
        pasteboard.setData(payload.data, forType: type)
        if let tiff, pasteboard.changeCount == generation {
            pasteboard.setData(tiff, forType: .tiff)
        }
        return generation
    }
}

private extension NSImage {
    func pngData() -> Data? {
        guard let tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiffRepresentation) else { return nil }
        return bitmap.representation(using: .png, properties: [:])
    }
}
