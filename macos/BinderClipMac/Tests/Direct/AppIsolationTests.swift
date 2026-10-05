import XCTest
import AppKit
@testable import BinderClip

final class AppIsolationTests: XCTestCase {
    func testPairingInvitationsRejectOtherRealm() throws {
        for profile in [AppProfile.production, .debug] {
            let url = try XCTUnwrap(SyncProtocol.createPairingURL(deviceId: "mac", deviceName: "Mac", psk: "test", endpoints: [], profile: profile))
            XCTAssertNotNil(SyncProtocol.parsePairingURL(url.absoluteString, profile: profile))
            XCTAssertNil(SyncProtocol.parsePairingURL(url.absoluteString, profile: profile.isDebug ? .production : .debug))
        }
    }
    func testIdenticalKeysDoNotAllowCrossEnvironmentTraffic() throws {
        let key = Data(0..<32), nonce = Data(32..<64), clientNonce = Data(64..<96)
        for transport in ["ws", "ble"] {
            for profile in [AppProfile.production, .debug] {
                let sender = try SecureChannel(psk: key, serverNonce: nonce, clientNonce: clientNonce, transport: transport, server: false, profile: profile)
                let packet = try sender.seal(Data("isolated".utf8), kind: 1)
                let other = try SecureChannel(psk: key, serverNonce: nonce, clientNonce: clientNonce, transport: transport, server: true, profile: profile.isDebug ? .production : .debug)
                XCTAssertThrowsError(try other.open(packet, kind: 1))
                let matching = try SecureChannel(psk: key, serverNonce: nonce, clientNonce: clientNonce, transport: transport, server: true, profile: profile)
                XCTAssertEqual(try matching.open(packet, kind: 1), Data("isolated".utf8))
            }
            let server = SecureHandshake(profile: .debug)
            let wrong = SecureChannel.proof(psk: key, serverNonce: server.nonce, clientNonce: clientNonce, transport: transport, profile: .production)
            XCTAssertThrowsError(try server.accept(clientNonce: clientNonce, proof: wrong, psk: key, transport: transport))
            let correct = SecureChannel.proof(psk: key, serverNonce: server.nonce, clientNonce: clientNonce, transport: transport, profile: .debug)
            XCTAssertNoThrow(try server.accept(clientNonce: clientNonce, proof: correct, psk: key, transport: transport))
        }
    }
    func testDebugClipboardAndKeyStoreDoNotTouchProduction() throws {
        XCTAssertFalse(AppProfile.debug.bluetoothEnabledByDefault)
        XCTAssertTrue(AppProfile.production.bluetoothEnabledByDefault)
        let board = NSPasteboard.withUniqueName()
        defer { board.releaseGlobally() }
        XCTAssertNotEqual(AppProfile.debug.pasteboard.name, NSPasteboard.general.name)
        let generalCount = NSPasteboard.general.changeCount
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let productionDirectory = directory.appendingPathComponent("production")
        let production = PrivateStateStore(directory: productionDirectory)
        production.set(Data(repeating: 99, count: 32), account: "group-key")
        let productionKey = productionDirectory.appendingPathComponent("direct-secrets.json")
        let before = try Data(contentsOf: productionKey)
        let store = PrivateStateStore(directory: directory.appendingPathComponent("debug"))
        store.set(Data(repeating: 42, count: 32), account: "group-key")
        ClipboardBridge(pasteboard: board).applyRemote("debug isolated test")
        XCTAssertEqual(board.string(forType: .string), "debug isolated test")
        XCTAssertEqual(NSPasteboard.general.changeCount, generalCount)
        XCTAssertEqual(try Data(contentsOf: productionKey), before)
        XCTAssertNotEqual(AppProfile.debug.stateDirectory, AppProfile.production.stateDirectory)
        XCTAssertNotEqual(AppProfile.debug.defaultsSuiteName, AppProfile.production.defaultsSuiteName)
        board.clearContents()
    }
}
