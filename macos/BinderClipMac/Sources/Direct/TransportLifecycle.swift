import Foundation

/// Mutated only on the transport queue; sleep cannot undo an explicit application stop.
struct TransportLifecycle {
    var running = false
    var sleeping = false
    var active: Bool { running && !sleeping }
}

/// A display-off event alone does not suspend service; a dark wake cannot undo system sleep.
enum SystemWakePolicy {
    static func shouldResume(waiting: Bool, displayAwake: Bool) -> Bool { waiting && displayAwake }
}
