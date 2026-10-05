import Foundation
import os

enum DiagnosticLevel: String, Codable {
    case info = "Info"
    case warning = "Warning"
    case error = "Error"
}

struct DiagnosticEvent: Codable {
    let date: Date
    let level: DiagnosticLevel
    let message: String
}

/// Redacted persistent log: it never contains clipboard data, filenames, keys, or invitations.
final class DiagnosticLog {
    static let shared = DiagnosticLog()
    static let changed = Notification.Name("net.wastu.binderclip.diagnosticsChanged")

    private let lock = NSLock()
    private let logger = Logger(subsystem: AppProfile.current.bundleID, category: "Diagnostics")
    private var events: [DiagnosticEvent] = []
    private let storageKey = "diagnostic-events-v1"
    private let maximumEvents = 2_000
    private var pendingPersist: DispatchWorkItem?
    private let persistenceQueue = DispatchQueue(label: "net.wastu.binderclip.diagnostics.persist", qos: .utility)
    private let retention: TimeInterval = 7 * 24 * 60 * 60

    private init() {
        events = (try? JSONDecoder().decode([DiagnosticEvent].self, from: AppProfile.current.defaults.data(forKey: storageKey) ?? Data()))?
            .filter { $0.date >= Date().addingTimeInterval(-retention) }.suffix(maximumEvents).map { $0 } ?? []
    }

    func info(_ message: String) { append(.info, message) }
    func warning(_ message: String) { append(.warning, message) }
    func error(_ message: String) { append(.error, message) }

    func snapshot() -> [DiagnosticEvent] {
        lock.lock(); defer { lock.unlock() }
        return events
    }

    func clear() {
        lock.lock(); events.removeAll(); pendingPersist?.cancel(); pendingPersist = nil; persistLocked(); lock.unlock()
        NotificationCenter.default.post(name: Self.changed, object: self)
    }

    private func append(_ level: DiagnosticLevel, _ message: String) {
        lock.lock()
        if let previous = events.last, previous.level == level, previous.message == message,
           Date().timeIntervalSince(previous.date) < 10 {
            lock.unlock()
            return
        }
        events.append(DiagnosticEvent(date: Date(), level: level, message: message))
        events = events.filter { $0.date >= Date().addingTimeInterval(-retention) }
        if events.count > maximumEvents { events.removeFirst(events.count - maximumEvents) }
        if pendingPersist == nil {
            let work = DispatchWorkItem { [weak self] in self?.flush() }
            pendingPersist = work
            persistenceQueue.asyncAfter(deadline: .now() + 2, execute: work)
        }
        lock.unlock()
        switch level {
        case .info: logger.info("\(message, privacy: .public)")
        case .warning: logger.warning("\(message, privacy: .public)")
        case .error: logger.error("\(message, privacy: .public)")
        }
        NotificationCenter.default.post(name: Self.changed, object: self)
    }
    func flush() {
        lock.lock(); defer { lock.unlock() }
        pendingPersist?.cancel(); pendingPersist = nil
        persistLocked()
    }
    private func persistLocked() { AppProfile.current.defaults.set(try? JSONEncoder().encode(events), forKey: storageKey) }
}
