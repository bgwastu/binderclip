import XCTest
@testable import BinderClip

final class PairingLifecycleTests: XCTestCase {
    func testAdvertisedEndpointsRejectLoopbackAndCap() {
        let addresses = ["127.0.0.1", "169.254.1.1", "192.168.1.10", "100.64.0.1", "8.8.8.8", "10.0.0.5", "172.16.0.2"]
        let endpoints = SyncProtocol.advertisedEndpoints(from: addresses)
        XCTAssertFalse(endpoints.contains { $0.hasPrefix("127.0.0.1") })
        XCTAssertFalse(endpoints.contains { $0.hasPrefix("169.254.") })
        XCTAssertLessThanOrEqual(endpoints.count, SyncProtocol.maximumAdvertisedEndpoints)
        XCTAssertTrue(endpoints.contains("192.168.1.10:39421"))
        XCTAssertTrue(endpoints.contains("10.0.0.5:39421"))
        // Mesh (100.x) is ranked first so the tunnel path wins over a VPN-captured LAN subnet.
        let firstHost = endpoints.first?.split(separator: ":").first.map(String.init)
        XCTAssertEqual(firstHost, "100.64.0.1")
        XCTAssertFalse(endpoints.contains { $0.hasPrefix("8.8.8.8") })
    }

    func testParseEndpointHostPort() {
        let parsed = SyncProtocol.parseEndpoint("192.168.1.50:39421")
        XCTAssertEqual(parsed?.host, "192.168.1.50")
        XCTAssertEqual(parsed?.port, 39421)
        XCTAssertEqual(SyncProtocol.parseEndpoint("example.local")?.port, 39421)
        XCTAssertEqual(SyncProtocol.parseEndpoint("[2001:db8::1]:39421")?.host, "2001:db8::1")
    }

    func testUnpairThenSameDeviceCanBeAddedAgain() {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("binderclip-pairing-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let defaults = UserDefaults(suiteName: "binderclip-pairing-\(UUID().uuidString)")!
        let manager = RosterManager(stateDirectory: directory, defaults: defaults)
        manager.clearPairingState()
        let peer = Peer(id: "device-aaa", name: "Pixel 8", endpoint: DirectEndpoint(host: "192.168.1.100", port: 39421), connected: true, platform: "Android")
        XCTAssertTrue(manager.addOrUpdatePeer(peer))
        manager.removePeer(id: "device-aaa")
        XCTAssertNil(manager.peers["device-aaa"])
        XCTAssertFalse(manager.shouldAcceptPeer("device-aaa", isPairingScan: false))
        XCTAssertTrue(manager.shouldAcceptPeer("device-aaa", isPairingScan: true))
        XCTAssertTrue(manager.addOrUpdatePeer(peer))
        XCTAssertNotNil(manager.peers["device-aaa"])
    }
}
