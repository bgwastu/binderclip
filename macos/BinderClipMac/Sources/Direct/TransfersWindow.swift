import AppKit
import SwiftUI

final class TransferHistory: ObservableObject {
    struct Entry: Codable, Identifiable, Equatable {
        let id: String
        let index: Int
        var name: String?
        var size: Int64?
        var status = "transfers_waiting"
        var fraction: Double?
        var destination: URL?
        var retry = false

        var saved: Bool { status == "transfers_saved" }
        var terminal: Bool {
            saved || ["transfer_failed", "transfer_declined", "transfer_invalid", "transfers_cancelled",
                      "transfers_unreadable", "transfers_too_large", "transfers_storage",
                      "transfers_queue_full", "transfers_expired"].contains(status)
        }
    }

    struct Batch: Codable, Identifiable {
        let id: String
        let peerID: String
        var peerName: String
        let direction: TransferProgress.Direction
        let count: Int
        var files = [Entry]()
        var updated = Date()
        var notified = false

        var saved: Int { files.filter(\.saved).count }
        var finished: Bool { files.count == count && files.allSatisfy(\.terminal) }
    }

    @Published private(set) var batches: [Batch]
    private let defaults: UserDefaults
    private static let key = "transfer-history"

    init(defaults: UserDefaults = AppProfile.current.defaults) {
        self.defaults = defaults
        let data = defaults.data(forKey: Self.key) ?? Data()
        self.batches = (try? JSONDecoder().decode([Batch].self, from: data)) ?? []
    }

    func clear(peerID: String? = nil) {
        batches.removeAll { peerID == nil || $0.peerID == peerID }
        persist()
    }

    func apply(_ snapshot: TransferBatchSnapshot, peerID: String, name: String) -> Batch? {
        let group = TransferBatch(id: snapshot.id, index: 0, count: snapshot.count)
        var batch = find(group, peerID: peerID, name: name, direction: .receiving)
        guard batch.count == snapshot.count else { return nil }
        let before = batch.files
        let statuses = [
            "UNREADABLE": "transfers_unreadable", "TOO_LARGE": "transfers_too_large",
            "STORAGE": "transfers_storage", "QUEUE_FULL": "transfers_queue_full", "EXPIRED": "transfers_expired",
            "CANCELLED": "transfers_cancelled", "REJECTED": "transfer_failed",
        ]
        for file in snapshot.items {
            if let known = batch.files.first(where: { $0.index == file.index }), known.id != file.id { continue }
            var item = batch.files.first(where: { $0.id == file.id }) ??
                Entry(id: file.id, index: file.index, name: file.name, size: file.size)
            if !item.saved && item.fraction == nil {
                item.status = statuses[file.state] ?? "transfers_waiting"
            }
            set(item, in: &batch)
        }
        guard batch.files != before else { return nil }
        return update(batch, durable: true)
    }

    func apply(_ progress: TransferProgress, name: String) -> Batch? {
        let file = progress.metadata
        guard file.kind == "file" else { return nil }
        let group = file.batch ?? TransferBatch(id: file.id, index: 0, count: 1)
        var batch = find(group, peerID: progress.peerID, name: name, direction: progress.direction)
        guard batch.count == group.count else { return nil }
        let previous = batch.files.first { $0.index == group.index }
        guard previous == nil || previous?.id == file.id else { return nil }
        var item = previous ?? Entry(id: file.id, index: group.index, name: file.name, size: file.size)
        switch progress.phase {
        case .sent, .received: item.status = "transfers_saved"
        case .declined: item.status = "transfer_declined"
        case .invalid: item.status = "transfer_invalid"
        case .failed: item.status = "transfer_failed"
        case .paused: item.status = "transfer_paused"
        default: item.status = progress.phase == .verifying ? "transfer_verifying" : "transfers_waiting"
        }
        item.fraction = progress.isActive && progress.phase != .preparing
            ? Double(Int(progress.fraction * 100)) / 100 : nil
        item.destination = progress.destination ?? item.destination
        item.retry = progress.phase == .declined && progress.direction == .sending && file.batch != nil
        if item == previous { return nil }
        set(item, in: &batch)
        return update(batch, durable: !progress.isActive)
    }

    private func find(_ group: TransferBatch, peerID: String, name: String, direction: TransferProgress.Direction) -> Batch {
        let id = peerID + "/" + direction.rawValue + "/" + group.id
        return batches.first { $0.id == id } ??
            Batch(id: id, peerID: peerID, peerName: name, direction: direction, count: group.count)
    }

    private func set(_ item: Entry, in batch: inout Batch) {
        if let index = batch.files.firstIndex(where: { $0.id == item.id }) { batch.files[index] = item }
        else { batch.files.append(item) }
    }

    private func update(_ newValue: Batch, durable: Bool) -> Batch? {
        var batch = newValue
        let notify = batch.finished && !batch.notified && batch.saved > 0
        if notify { batch.notified = true }
        batch.updated = Date()
        if let i = batches.firstIndex(where: { $0.id == batch.id }) { batches[i] = batch }
        else { batches.insert(batch, at: 0) }
        if batches.count > 32 { batches.remove(at: batches.lastIndex(where: \.finished) ?? (batches.endIndex - 1)) }
        if durable || notify { persist() }
        return notify ? batch : nil
    }

    private func persist() {
        var records = Array(batches.prefix(32))
        for b in records.indices {
            for f in records[b].files.indices where !records[b].files[f].terminal {
                records[b].files[f].fraction = nil
                records[b].files[f].status = "transfers_waiting"
            }
        }
        if let data = try? JSONEncoder().encode(records) { defaults.set(data, forKey: Self.key) }
    }
}

final class TransfersWindow: NSWindowController {
    init(history: TransferHistory, folder: URL, retry: @escaping (String, String) -> Void) {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 550, height: 400),
                              styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.title = L10n.tr("transfers_title")
        window.minSize = NSSize(width: 400, height: 260)
        window.isReleasedWhenClosed = false
        window.contentViewController = NSHostingController(rootView: TransfersView(history: history, folder: folder, retry: retry))
        window.center()
        super.init(window: window)
    }

    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }
}

private struct TransfersView: View {
    @ObservedObject var history: TransferHistory
    let folder: URL
    let retry: (String, String) -> Void

    var body: some View {
        VStack(spacing: 0) {
            if history.batches.isEmpty {
                Text(L10n.tr("transfers_empty")).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(history.batches) { batch in
                    Section {
                        ForEach(batch.files.sorted { $0.index < $1.index }) { file in
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(file.name ?? L10n.tr("transfer_kind_file")).lineLimit(1).truncationMode(.middle)
                                    if let fraction = file.fraction, file.status != "transfer_verifying" {
                                        ProgressView(value: fraction)
                                    } else {
                                        let status = file.status == "transfer_verifying"
                                            ? L10n.tr(file.status, L10n.tr("transfer_kind_file")) : L10n.tr(file.status)
                                        let size = file.size.map { ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) }
                                        Text([status, size].compactMap { $0 }.joined(separator: " · "))
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                if let destination = file.destination {
                                    Button(L10n.tr("transfers_reveal")) {
                                        NSWorkspace.shared.activateFileViewerSelecting([destination])
                                    }
                                } else if file.retry {
                                    Button(L10n.tr("transfers_retry")) { retry(batch.peerID, file.id) }
                                }
                            }.padding(.vertical, 3)
                        }
                    } header: {
                        HStack {
                            Image(systemName: batch.direction == .sending ? "arrow.up" : "arrow.down")
                            Text(batch.peerName)
                            Spacer()
                            Text(L10n.tr("transfers_saved_count", batch.saved, batch.count))
                            let destinations = batch.files.compactMap(\.destination)
                            if batch.finished && destinations.count > 1 {
                                Button(L10n.tr("transfers_reveal")) {
                                    NSWorkspace.shared.activateFileViewerSelecting(destinations)
                                }
                            }
                        }
                    }
                }
            }
            Divider()
            HStack {
                Spacer()
                Button(L10n.tr("transfers_open_folder")) {
                    try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                    NSWorkspace.shared.open(folder)
                }
            }.padding(12)
        }
    }
}
