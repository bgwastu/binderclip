import Foundation
import XCTest
@testable import BinderClip

final class PairedFileTransferTests: XCTestCase {
    func testMixedAndroidShareResumesAndExplicitMacSharesPreserveClipboard() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard let serial = environment["ANDROID_SERIAL"], let host = environment["BINDERCLIP_TEST_MAC_HOST"] else {
            throw XCTSkip("Set ANDROID_SERIAL and BINDERCLIP_TEST_MAC_HOST for the paired debug test")
        }
        let device = DebugAndroid(serial: serial, adb: environment["ADB"] ?? "adb")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite = "binderclip-paired-test-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let roster = RosterManager(stateDirectory: root, defaults: defaults)
        let port = AppProfile.debug.port
        let server = WebSocketServer(rosterManager: roster, port: port, bluetoothEnabled: false, profile: .debug)
        defer {
            server.stop()
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: root)
            try? device.removeFixtures()
        }

        let listening = expectation(description: "debug WebSocket ready")
        listening.assertForOverFulfill = false
        server.onListening = { actual in
            XCTAssertEqual(actual, port)
            listening.fulfill()
        }
        var interrupted = false
        var paused = false
        var resumedAt: Int64?
        var received: [String: URL] = [:]
        server.onImage = { _ in XCTFail("A file share reached the clipboard image path") }
        server.onFile = { file in
            received[file.lastPathComponent] = file
            guard file.pathExtension == "png" else { return }
            do { server.shareImage(try ImagePayload(mimeType: "image/png", data: Data(contentsOf: file))) }
            catch { XCTFail("Could not share the fixture photo") }
        }
        server.onTransferProgress = { progress in
            guard progress.direction == .receiving, progress.metadata.name.hasSuffix(".zip") else { return }
            if interrupted && progress.phase == .paused { paused = true }
            guard progress.phase == .receiving else { return }
            if interrupted {
                if paused && resumedAt == nil { resumedAt = progress.completedBytes }
            } else if progress.completedBytes > 0 && progress.completedBytes < progress.metadata.size {
                interrupted = true
                server.stop()
                server.sendSharedText("first test share")
                server.sendSharedText("second test share")
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.75) { server.start() }
            }
        }
        server.start()
        await fulfillment(of: [listening], timeout: 10)
        let fixture = try JSONSerialization.data(withJSONObject: [
            "id": roster.localID, "host": host, "port": port, "key": roster.groupKey.base64EncodedString()
        ])
        try device.writePairing(fixture)
        let output = try await Task.detached { try device.runInstrumentation() }.value
        let timeout = output.split(separator: "\n").first { $0.hasPrefix("java.lang.AssertionError: Timed out:") }
        XCTAssertTrue(output.contains("OK (1 test)"), timeout.map(String.init) ?? "Android instrumentation failed")
        XCTAssertTrue(interrupted)
        XCTAssertGreaterThan(resumedAt ?? 0, 0)
        let hashes = try JSONDecoder().decode([String: String].self, from: device.readResult())
        XCTAssertEqual(received.count, 2)
        for (name, hash) in hashes {
            XCTAssertEqual(try FileTransferProtocol.hash(XCTUnwrap(received[name])), hash)
        }
    }
}

private struct DebugAndroid: Sendable {
    let serial: String
    let adb: String
    private let package = "net.wastu.binderclip.debug"

    func writePairing(_ data: Data) throws {
        _ = try run(["shell", "-T", "run-as", package, "sh", "-c", "'umask 077; mkdir -p files && cat > files/device-test-pair.json'"], input: data)
    }

    func runInstrumentation() throws -> String {
        let output = try run(["shell", "timeout", "150", "am", "instrument", "-w",
                              "-e", "class", "net.wastu.binderclip.FileTransferDeviceTest",
                              "-e", "hardwareFileTransfer", "true",
                              "\(package).test/androidx.test.runner.AndroidJUnitRunner"])
        return String(decoding: output, as: UTF8.self)
    }

    func readResult() throws -> Data {
        try run(["exec-out", "run-as", package, "cat", "files/device-test-files.json"])
    }

    func removeFixtures() throws {
        _ = try run(["shell", "run-as", package, "rm", "-f", "files/device-test-pair.json", "files/device-test-files.json"])
    }

    private func run(_ arguments: [String], input: Data? = nil) throws -> Data {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        process.arguments = [adb, "-s", serial] + arguments
        let output = Pipe()
        process.standardOutput = output
        process.standardError = FileHandle.nullDevice
        let pipe = Pipe()
        if input != nil { process.standardInput = pipe }
        try process.run()
        if let input {
            try pipe.fileHandleForWriting.write(contentsOf: input)
            try pipe.fileHandleForWriting.close()
        }
        let data = output.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            throw NSError(domain: "DebugAndroid", code: Int(process.terminationStatus))
        }
        return data
    }
}
