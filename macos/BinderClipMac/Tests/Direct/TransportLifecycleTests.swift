import XCTest
@testable import BinderClip

final class TransportLifecycleTests: XCTestCase {
    func testWakeDoesNotRestartStoppedTransport() {
        var state = TransportLifecycle()
        state.running = true
        XCTAssertTrue(state.active)
        state.sleeping = true
        XCTAssertFalse(state.active)
        state.running = false
        state.sleeping = false
        XCTAssertFalse(state.active)
    }
    func testDarkWakeDoesNotRearmBackgroundWork() {
        XCTAssertFalse(SystemWakePolicy.shouldResume(waiting: true, displayAwake: false))
        XCTAssertTrue(SystemWakePolicy.shouldResume(waiting: true, displayAwake: true))
        XCTAssertFalse(SystemWakePolicy.shouldResume(waiting: false, displayAwake: true))
    }
    func testPhoneWakeReschedulesPingBeforeInteractiveDeadline() {
        let now = SessionLiveness.now()
        XCTAssertEqual(SessionLiveness.nextPingAfterPowerChange(sleeping: false, now: now), now)
        XCTAssertGreaterThan(SessionLiveness.nextPingAfterPowerChange(sleeping: true, now: now).timeIntervalSince(now), SessionLiveness.heartbeatBudget)
    }
    func testSleepingDeadlineAllowsMultipleIdleIntervals() {
        XCTAssertGreaterThan(SessionLiveness.idleHeartbeatInterval, SyncProtocol.heartbeatInterval)
        XCTAssertGreaterThanOrEqual(SessionLiveness.heartbeatSleepBudget, 3 * SessionLiveness.idleHeartbeatInterval)
    }
}
