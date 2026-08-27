import Foundation

/// Minimal CBOR (RFC 8949) subset for the Bluetooth link — byte-for-byte parity with the Android
/// implementation (BtCbor.kt): flat maps of text keys to text/unsigned-int/string-array values,
/// deterministic field order, strict decoding. Golden vectors on both platforms pin this codec.
public enum BtCborError: Error {
    case malformed(String)

    public var localizedDescription: String {
        switch self {
        case .malformed(let message): return message
        }
    }
}

public enum BtValue: Equatable {
    case text(String)
    case uint(UInt64)
    case array([String])

    var valueAsText: String? {
        if case .text(let text) = self { return text }
        return nil
    }
}

public enum BtCbor {
    private static let majorUnsigned = 0
    private static let majorText = 3
    private static let majorArray = 4
    private static let majorMap = 5

    /// Values: .text, .uint, or .array of texts. Max 23 fields.
    public static func encode(_ fields: [(String, BtValue)]) throws -> [UInt8] {
        guard fields.count <= 23 else { throw BtCborError.malformed("map too large: \(fields.count)") }
        var out: [UInt8] = []
        out.reserveCapacity(64 + fields.count * 16)
        writeHead(&out, major: majorMap, length: UInt64(fields.count))
        for (key, value) in fields {
            writeText(&out, key)
            switch value {
            case .text(let text):
                writeText(&out, text)
            case .uint(let number):
                writeHead(&out, major: majorUnsigned, length: number)
            case .array(let items):
                guard items.count <= 23 else { throw BtCborError.malformed("array too large: \(items.count)") }
                writeHead(&out, major: majorArray, length: UInt64(items.count))
                for item in items {
                    writeText(&out, item)
                }
            }
        }
        return out
    }

    public static func decode(_ bytes: [UInt8]) throws -> [(String, BtValue)] {
        var parser = Parser(bytes: bytes)
        let mapHead = try parser.readHead()
        guard mapHead.major == majorMap else {
            throw BtCborError.malformed("expected map, got major \(mapHead.major)")
        }
        var fields: [(String, BtValue)] = []
        fields.reserveCapacity(Int(mapHead.arg))
        for _ in 0..<mapHead.arg {
            let keyHead = try parser.readHead()
            guard keyHead.major == majorText else { throw BtCborError.malformed("map key must be text") }
            let key = try parser.readText(keyHead.arg)
            let valueHead = try parser.readHead()
            let value: BtValue
            switch valueHead.major {
            case majorUnsigned:
                value = .uint(valueHead.arg)
            case majorText:
                value = .text(try parser.readText(valueHead.arg))
            case majorArray:
                guard valueHead.arg <= Int.max else { throw BtCborError.malformed("array too large") }
                var items: [String] = []
                items.reserveCapacity(Int(valueHead.arg))
                for _ in 0..<valueHead.arg {
                    let itemHead = try parser.readHead()
                    guard itemHead.major == majorText else { throw BtCborError.malformed("array element must be text") }
                    items.append(try parser.readText(itemHead.arg))
                }
                value = .array(items)
            default:
                throw BtCborError.malformed("unsupported value major \(valueHead.major)")
            }
            fields.append((key, value))
        }
        return fields
    }

    private static func writeHead(_ out: inout [UInt8], major: Int, length: UInt64) {
        let prefix = UInt8(major << 5)
        switch length {
        case 0...23:
            out.append(prefix | UInt8(length))
        case 24...0xFF:
            out.append(prefix | 24)
            appendWide(&out, length, bytes: 1)
        case 0x100...0xFFFF:
            out.append(prefix | 25)
            appendWide(&out, length, bytes: 2)
        case 0x10000...0xFFFF_FFFF:
            out.append(prefix | 26)
            appendWide(&out, length, bytes: 4)
        default:
            out.append(prefix | 27)
            appendWide(&out, length, bytes: 8)
        }
    }

    private static func appendWide(_ out: inout [UInt8], _ value: UInt64, bytes: Int) {
        for shift in stride(from: (bytes - 1) * 8, through: 0, by: -8) {
            out.append(UInt8((value >> UInt64(shift)) & 0xFF))
        }
    }

    private static func writeText(_ out: inout [UInt8], _ value: String) {
        let utf8 = Array(value.utf8)
        writeHead(&out, major: majorText, length: UInt64(utf8.count))
        out.append(contentsOf: utf8)
    }

    private struct Head {
        var major: Int
        var arg: UInt64
    }

    private struct Parser {
        let bytes: [UInt8]
        var position = 0

        mutating func readHead() throws -> Head {
            let initial = try readByte()
            let major = Int(initial >> 5)
            let info = Int(initial & 0x1F)
            let arg: UInt64
            switch info {
            case 0...23:
                arg = UInt64(info)
            case 24:
                arg = UInt64(try readByte())
            case 25:
                arg = try readWide(bytes: 2)
            case 26:
                arg = try readWide(bytes: 4)
            case 27:
                arg = try readWide(bytes: 8)
            default:
                throw BtCborError.malformed("reserved/indefinite additional info \(info)")
            }
            return Head(major: major, arg: arg)
        }

        mutating func readText(_ length: UInt64) throws -> String {
            guard length <= bytes.count - position else { throw BtCborError.malformed("truncated text") }
            defer { position += Int(length) }
            guard let text = String(bytes: bytes[position..<position + Int(length)], encoding: .utf8) else {
                throw BtCborError.malformed("invalid UTF-8")
            }
            return text
        }

        mutating func readByte() throws -> UInt8 {
            guard position < bytes.count else { throw BtCborError.malformed("truncated input") }
            defer { position += 1 }
            return bytes[position]
        }

        mutating func readWide(bytes count: Int) throws -> UInt64 {
            var value: UInt64 = 0
            for _ in 0..<count {
                value = (value << 8) | UInt64(try readByte())
            }
            return value
        }
    }
}

/// Length-prefix stream framing ([u32be length][payload]) shared by both RFCOMM peers.
public enum BtFrameIo {
    public static let maximumFrameBytes = 1_500_000

    public static func frame(payload: [UInt8]) throws -> [UInt8] {
        guard !payload.isEmpty else { throw BtCborError.malformed("empty payload") }
        guard payload.count <= maximumFrameBytes else { throw BtCborError.malformed("payload too large") }
        var out: [UInt8] = []
        out.reserveCapacity(payload.count + 4)
        for shift in stride(from: 24, through: 0, by: -8) {
            out.append(UInt8((payload.count >> shift) & 0xFF))
        }
        out.append(contentsOf: payload)
        return out
    }

    /// Incremental decoder for chunked RFCOMM reads; emits complete payloads as they arrive.
    public struct Reader {
        private var buffer: [UInt8] = []

        public init() {}

        public mutating func feed(_ chunk: [UInt8]) throws -> [[UInt8]] {
            buffer.append(contentsOf: chunk)
            var frames: [[UInt8]] = []
            var offset = 0
            while true {
                let remaining = buffer.count - offset
                guard remaining >= 4 else { break }
                var length = 0
                for index in 0..<4 {
                    length = (length << 8) | Int(buffer[offset + index])
                }
                guard length > 0, length <= maximumFrameBytes else {
                    reset()
                    throw BtCborError.malformed("invalid frame length \(length)")
                }
                guard remaining >= 4 + length else { break }
                frames.append(Array(buffer[(offset + 4)..<(offset + 4 + length)]))
                offset += 4 + length
            }
            if offset > 0 {
                buffer.removeFirst(offset)
            }
            return frames
        }

        public mutating func reset() {
            buffer.removeAll(keepingCapacity: true)
        }
    }
}
