import Foundation

/// Live presence is derived from authenticated, usable sessions — never from disk.
enum PeerPresence {
    static func isConnected(peerID: String, authenticatedPeerIDs: [String]) -> Bool {
        authenticatedPeerIDs.contains(peerID)
    }

    static func shouldReplace(existingPeerID: String?, incomingPeerID: String) -> Bool {
        existingPeerID == incomingPeerID
    }

    /// Drop the previous session for this phone. Unauthenticated sockets are left alone: they have
    /// no peerID, so they may be another phone mid-handshake. The phone cancels its own LAN+mesh
    /// twins once one of them receives `secure_hello`, and the auth deadline reaps any stragglers.
    static func shouldCancelExtra(isAuthenticated: Bool, existingPeerID: String?, incomingPeerID: String) -> Bool {
        isAuthenticated && shouldReplace(existingPeerID: existingPeerID, incomingPeerID: incomingPeerID)
    }

    /// Ignore auth that arrives after this socket was already cancelled or accepted.
    /// A leftover twin's queued auth must not cancel the winner.
    static func shouldProcessAuth(isStillActive: Bool, alreadyAuthenticated: Bool) -> Bool {
        isStillActive && !alreadyAuthenticated
    }

    /// An unpaired phone may return only by scanning the QR (`pairing` on auth).
    static func shouldAcceptReturningPeer(wasUnpaired: Bool, isPairingScan: Bool) -> Bool {
        !wasUnpaired || isPairingScan
    }
}
