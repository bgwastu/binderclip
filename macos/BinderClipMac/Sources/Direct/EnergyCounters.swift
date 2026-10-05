import Foundation

/// Fixed-label counters, aggregated only when work occurs. No extra maintenance timer.
enum EnergyCounters {
    private static let lock = NSLock()
    private static var values: [String: UInt64] = [:]
    private static var lastReport = ProcessInfo.processInfo.systemUptime
    static func add(_ name: String, _ amount: UInt64 = 1) {
        lock.lock()
        values[name, default: 0] += amount
        let now = ProcessInfo.processInfo.systemUptime
        let report: String?
        if now - lastReport >= 300 {
            report = values.keys.sorted().map { "\($0)=\(values[$0]!)" }.joined(separator: " ")
            values.removeAll(); lastReport = now
        } else { report = nil }
        lock.unlock()
        if let report { DiagnosticLog.shared.info("Activity counters: \(report)") }
    }
}
