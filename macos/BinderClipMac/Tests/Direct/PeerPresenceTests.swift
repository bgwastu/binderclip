import XCTest
@testable import BinderClip

final class PeerPresenceTests: XCTestCase {
    func testPresenceIgnoresClosedDuplicateSession() {
        let peerID = "phone-1"
        XCTAssertTrue(PeerPresence.isConnected(peerID: peerID, authenticatedPeerIDs: ["phone-1"]))
        XCTAssertTrue(PeerPresence.isConnected(peerID: peerID, authenticatedPeerIDs: ["phone-1", "phone-1"]))
        XCTAssertFalse(PeerPresence.isConnected(peerID: peerID, authenticatedPeerIDs: []))
        XCTAssertFalse(PeerPresence.isConnected(peerID: peerID, authenticatedPeerIDs: ["phone-2"]))
    }

    func testShouldReplaceSamePeerOnly() {
        XCTAssertTrue(PeerPresence.shouldReplace(existingPeerID: "phone-1", incomingPeerID: "phone-1"))
        XCTAssertFalse(PeerPresence.shouldReplace(existingPeerID: "phone-2", incomingPeerID: "phone-1"))
        XCTAssertFalse(PeerPresence.shouldReplace(existingPeerID: nil, incomingPeerID: "phone-1"))
    }

    func testShouldCancelOnlyThePreviousSessionOfTheSamePeer() {
        // Another phone's in-flight handshake must survive this phone authenticating.
        XCTAssertFalse(PeerPresence.shouldCancelExtra(isAuthenticated: false, existingPeerID: nil, incomingPeerID: "phone-1"))
        XCTAssertTrue(PeerPresence.shouldCancelExtra(isAuthenticated: true, existingPeerID: "phone-1", incomingPeerID: "phone-1"))
        XCTAssertFalse(PeerPresence.shouldCancelExtra(isAuthenticated: true, existingPeerID: "phone-2", incomingPeerID: "phone-1"))
    }

    func testShouldIgnoreAuthAfterCancelOrDuplicate() {
        XCTAssertTrue(PeerPresence.shouldProcessAuth(isStillActive: true, alreadyAuthenticated: false))
        XCTAssertFalse(PeerPresence.shouldProcessAuth(isStillActive: false, alreadyAuthenticated: false))
        XCTAssertFalse(PeerPresence.shouldProcessAuth(isStillActive: true, alreadyAuthenticated: true))
    }

    func testUnpairedPeerReturnsOnlyViaPairingScan() {
        XCTAssertTrue(PeerPresence.shouldAcceptReturningPeer(wasUnpaired: false, isPairingScan: false))
        XCTAssertFalse(PeerPresence.shouldAcceptReturningPeer(wasUnpaired: true, isPairingScan: false))
        XCTAssertTrue(PeerPresence.shouldAcceptReturningPeer(wasUnpaired: true, isPairingScan: true))
    }
}
