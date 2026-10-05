import XCTest
@testable import BinderClip

final class SessionLivenessTests: XCTestCase {
    func testMeshRemoteBindsLocalMeshAddress() {
        let locals = ["192.168.60.249", "100.96.0.2"]
        XCTAssertEqual(SessionLiveness.boundLocalAddress(remote: "100.96.0.31", localAddresses: locals), "100.96.0.2")
    }

    func testLanRemoteBindsLanAddress() {
        let locals = ["192.168.60.249", "100.96.0.2"]
        XCTAssertEqual(SessionLiveness.boundLocalAddress(remote: "192.168.50.199", localAddresses: locals), "192.168.60.249")
    }

    func testEvictWhenBoundMeshAddressDisappears() {
        XCTAssertTrue(SessionLiveness.shouldEvict(boundLocal: "100.96.0.2", currentLocals: ["192.168.60.249"]))
        XCTAssertFalse(SessionLiveness.shouldEvict(boundLocal: "192.168.60.249", currentLocals: ["192.168.60.249"]))
        XCTAssertFalse(SessionLiveness.shouldEvict(boundLocal: "192.168.60.249", currentLocals: ["192.168.60.249", "100.96.0.2"]))
    }

    func testHeartbeatMissIsDead() {
        let bound = "100.96.0.2"
        let locals = ["192.168.60.249", "100.96.0.2"]
        let now = Date()
        XCTAssertTrue(SessionLiveness.isAlive(boundLocal: bound, currentLocals: locals, lastHeard: now, now: now))
        XCTAssertFalse(SessionLiveness.isAlive(boundLocal: bound, currentLocals: locals, lastHeard: now.addingTimeInterval(-6), now: now))
        XCTAssertFalse(SessionLiveness.isAlive(boundLocal: bound, currentLocals: ["192.168.60.249"], lastHeard: now, now: now))
        XCTAssertFalse(SessionLiveness.isAlive(boundLocal: bound, currentLocals: locals, lastHeard: nil, now: now))
    }

    func testSleepBudgetKeepsSessionThroughDozeGap() {
        let bound = "100.96.0.2"
        let locals = ["192.168.60.249", "100.96.0.2"]
        let now = Date()
        XCTAssertTrue(SessionLiveness.isAlive(
            boundLocal: bound,
            currentLocals: locals,
            lastHeard: now.addingTimeInterval(-30),
            now: now,
            budget: SessionLiveness.heartbeatSleepBudget
        ))
        XCTAssertFalse(SessionLiveness.isAlive(
            boundLocal: bound,
            currentLocals: locals,
            lastHeard: now.addingTimeInterval(-SessionLiveness.heartbeatSleepBudget - 1),
            now: now,
            budget: SessionLiveness.heartbeatSleepBudget
        ))
    }
}
