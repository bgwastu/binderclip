import XCTest
@testable import BinderClip

final class SecureChannelTests: XCTestCase {
    private let key = Data(0..<32)
    private let serverNonce = Data(32..<64)
    private let clientNonce = Data(64..<96)
    private func channel(server: Bool = false, transport: String = "ws") throws -> SecureChannel {
        try SecureChannel(psk: key, serverNonce: serverNonce, clientNonce: clientNonce, transport: transport, server: server)
    }
    private func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }
    func testIndependentVectorMatchesAndroid() throws {
        XCTAssertEqual(hex(SecureChannel.proof(psk: key, serverNonce: serverNonce, clientNonce: clientNonce, transport: "ws")),
            "b9abb92d08dd27dba1745c700a84a78b8efa3401ae67adc77861930c3e3c9c32")
        let packet = try channel().seal(Data("clipboard test".utf8), kind: 1)
        XCTAssertEqual(hex(packet), "0000000000000001c2e436814e014e917c288cdd7d717c5a4edacd534996a94da470fe5a988b")
        XCTAssertEqual(try channel(server: true).open(packet, kind: 1), Data("clipboard test".utf8))
    }
    func testTamperingDoesNotConsumeSequenceAndReplayIsRejected() throws {
        let packet = try channel().seal(Data([1]), kind: 1)
        var corrupt = packet; corrupt[corrupt.count - 1] ^= 1
        let receiver = try channel(server: true)
        XCTAssertThrowsError(try receiver.open(corrupt, kind: 1))
        XCTAssertEqual(try receiver.open(packet, kind: 1), Data([1]))
        XCTAssertThrowsError(try receiver.open(packet, kind: 1))
    }
    func testRejectsWrongDirectionTransportKindAndSession() throws {
        let packet = try channel().seal(Data([1]), kind: 1)
        XCTAssertThrowsError(try channel().open(packet, kind: 1))
        XCTAssertThrowsError(try channel(server: true, transport: "ble").open(packet, kind: 1))
        XCTAssertThrowsError(try channel(server: true).open(packet, kind: 2))
        let fresh = try SecureChannel(psk: key, serverNonce: SecureHandshake().nonce, clientNonce: clientNonce, transport: "ws", server: true)
        XCTAssertThrowsError(try fresh.open(packet, kind: 1))
    }
    func testHandshakeRequiresProofAndCannotBeReused() throws {
        let handshake = SecureHandshake()
        XCTAssertThrowsError(try handshake.accept(clientNonce: clientNonce, proof: Data(repeating: 0, count: 32), psk: key, transport: "ws"))
        let proof = SecureChannel.proof(psk: key, serverNonce: handshake.nonce, clientNonce: clientNonce, transport: "ws")
        _ = try handshake.accept(clientNonce: clientNonce, proof: proof, psk: key, transport: "ws")
        XCTAssertThrowsError(try handshake.accept(clientNonce: clientNonce, proof: proof, psk: key, transport: "ws"))
    }
    func testBleAndBinaryBidirectionalFrames() throws {
        let client = try channel(transport: "ble"), server = try channel(server: true, transport: "ble")
        let payload = try BtCbor.encode([("type", .text("clipboard")), ("text", .text("hello"))])
        XCTAssertEqual(try server.open(client.seal(Data(payload), kind: 3), kind: 3), Data(payload))
        XCTAssertEqual(try client.open(server.seal(Data([0, 1, 2]), kind: 3), kind: 3), Data([0, 1, 2]))
    }
}
