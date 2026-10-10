import Foundation
import CoreFoundation

final class ChunkedTransferSession {
    let peerID: String
    private let store: FileTransferStore
    private let queue: DispatchQueue
    private let sendText: ([String: Any]) -> Void
    private let sendBinary: (Data) -> Void
    private let disconnect: () -> Void
    private let onImage: (ImagePayload) -> Void
    private let onFile: (ReceivedFile) -> Void
    private let onStatus: (String) -> Void
    private let onProgress: (TransferProgress) -> Void
    private let onOrderedImage: ((ImagePayload, ClipboardStamp?) -> Void)?
    private var outgoing: OutgoingTransfer?
    private var offered = false
    private var expectedOffset: Int64 = 0
    private var lastAcknowledged: Int64 = 0
    private var deadline: DispatchWorkItem?
    private var incoming: [String: TransferMetadata] = [:]
    private var incomingOffsets: [String: Int64] = [:]
    private var closed = false

    init(peerID: String, store: FileTransferStore, queue: DispatchQueue,
         sendText: @escaping ([String: Any]) -> Void, sendBinary: @escaping (Data) -> Void,
         disconnect: @escaping () -> Void, onImage: @escaping (ImagePayload) -> Void,
         onFile: @escaping (ReceivedFile) -> Void, onStatus: @escaping (String) -> Void,
         onProgress: @escaping (TransferProgress) -> Void = { _ in },
         onOrderedImage: ((ImagePayload, ClipboardStamp?) -> Void)? = nil) {
        self.peerID = peerID; self.store = store; self.queue = queue
        self.sendText = sendText; self.sendBinary = sendBinary; self.disconnect = disconnect
        self.onImage = onImage; self.onFile = onFile; self.onStatus = onStatus
        self.onProgress = onProgress
        self.onOrderedImage = onOrderedImage
    }
    func pump() {
        guard !closed, outgoing == nil, let next = store.next(peerID: peerID) else { return }
        outgoing = next
        lastAcknowledged = 0
        expectedOffset = 0
        offered = true
        sendText(next.metadata.json)
        waitForReply()
        onStatus(L10n.tr("transfer_offering", kind(next.metadata)))
        progress(next.metadata, .preparing, bytes: 0, direction: .sending)
    }
    func handle(_ json: [String: Any]) {
        guard !closed else { return }
        guard let id = json["id"] as? String, FileTransferProtocol.validID(id) else { disconnect(); return }
        do {
            switch json["type"] as? String {
            case "transfer_offer":
                let meta = try TransferMetadata.parse(json)
                guard incoming.count < 4 || incoming[id] != nil else { throw FileTransferError.queueFull }
                let state = try store.accept(peerID: peerID, meta: meta)
                if state.completed {
                    sendText(["type": "transfer_done", "id": id])
                    progress(meta, .received, bytes: meta.size, direction: .receiving)
                } else {
                    incoming[id] = meta
                    incomingOffsets[id] = state.offset
                    if state.offset == meta.size { try complete(meta) }
                    else {
                        acknowledge(id, offset: state.offset)
                        onStatus(L10n.tr("transfer_receiving", kind(meta)))
                        progress(meta, .receiving, bytes: state.offset, direction: .receiving)
                    }
                }
            case "transfer_ack":
                guard let transfer = outgoing, transfer.metadata.id == id else { return }
                guard let number = json["offset"] as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
                      String(cString: number.objCType) != "d" else { throw FileTransferError.invalidChunk }
                let offset = number.int64Value
                guard (0..<transfer.metadata.size).contains(offset), offset % Int64(FileTransferProtocol.chunkBytes) == 0 else { throw FileTransferError.invalidChunk }
                if offered { offered = false }
                else {
                    if offset == lastAcknowledged { return }
                    guard offset == expectedOffset else { throw FileTransferError.invalidChunk }
                }
                deadline?.cancel()
                lastAcknowledged = offset
                let count = Int(min(Int64(FileTransferProtocol.chunkBytes), transfer.metadata.size - offset))
                let input = try FileHandle(forReadingFrom: transfer.file)
                defer { try? input.close() }
                try input.seek(toOffset: UInt64(offset))
                guard let data = try input.read(upToCount: count), data.count == count else { throw FileTransferError.storage }
                expectedOffset = offset + Int64(count)
                sendBinary(try FileTransferProtocol.packChunk(id: id, offset: offset, data: data))
                waitForReply()
                onStatus(L10n.tr("transfer_sending", kind(transfer.metadata), Int(offset * 100 / transfer.metadata.size)))
                progress(transfer.metadata, .sending, bytes: offset, direction: .sending)
            case "transfer_done":
                guard let transfer = outgoing, transfer.metadata.id == id else { return }
                guard offered || expectedOffset == transfer.metadata.size else { throw FileTransferError.invalidChunk }
                deadline?.cancel()
                store.removeOutgoing(id)
                outgoing = nil
                onStatus(L10n.tr("transfer_sent", kind(transfer.metadata)))
                progress(transfer.metadata, .sent, bytes: transfer.metadata.size, direction: .sending)
                pump()
            case "transfer_cancel":
                if let meta = incoming.removeValue(forKey: id) {
                    progress(meta, .declined, bytes: incomingOffsets.removeValue(forKey: id) ?? 0, direction: .receiving)
                    store.discardIncoming(peerID: peerID, id: id)
                }
                if let transfer = outgoing, transfer.metadata.id == id {
                    deadline?.cancel()
                    store.removeOutgoing(id)
                    outgoing = nil
                    onStatus(L10n.tr("transfer_declined"))
                    progress(transfer.metadata, .declined, bytes: lastAcknowledged, direction: .sending)
                    pump()
                }
            default: break
            }
        } catch {
            if let meta = incoming.removeValue(forKey: id) {
                progress(meta, .failed, bytes: incomingOffsets.removeValue(forKey: id) ?? 0, direction: .receiving)
            }
            store.discardIncoming(peerID: peerID, id: id)
            sendText(["type": "transfer_cancel", "id": id])
            onStatus(L10n.tr("transfer_failed"))
            if let transfer = outgoing, transfer.metadata.id == id {
                progress(transfer.metadata, .failed, bytes: lastAcknowledged, direction: .sending)
                outgoing = nil
                disconnect(); close()
            }
        }
    }
    func handleChunk(_ packet: Data) {
        guard !closed else { return }
        var id: String?
        var metadata: TransferMetadata?
        do {
            let (chunkID, offset, data) = try FileTransferProtocol.unpackChunk(packet)
            id = chunkID
            guard let meta = incoming[chunkID] else { throw FileTransferError.invalidChunk }
            metadata = meta
            let next = try store.append(peerID: peerID, meta: meta, offset: offset, bytes: data)
            incomingOffsets[chunkID] = next
            if next == meta.size { try complete(meta) }
            else {
                acknowledge(chunkID, offset: next)
                onStatus(L10n.tr("transfer_receiving", kind(meta)) + " (\(next * 100 / meta.size)%)")
                progress(meta, .receiving, bytes: next, direction: .receiving)
            }
        } catch {
            if let id {
                incoming.removeValue(forKey: id)
                let bytes = incomingOffsets.removeValue(forKey: id) ?? 0
                if let metadata { progress(metadata, .invalid, bytes: bytes, direction: .receiving) }
                store.discardIncoming(peerID: peerID, id: id)
                sendText(["type": "transfer_cancel", "id": id])
            } else { disconnect() }
            onStatus(L10n.tr("transfer_invalid"))
        }
    }
    private func complete(_ meta: TransferMetadata) throws {
        progress(meta, .verifying, bytes: meta.size, direction: .receiving)
        let file = try store.complete(peerID: peerID, meta: meta)
        incoming.removeValue(forKey: meta.id)
        incomingOffsets.removeValue(forKey: meta.id)
        if meta.kind == "image" {
            let image = try ImagePayload(id: UUID(uuidString: meta.id)!, mimeType: meta.mimeType, data: Data(contentsOf: file.file))
            if let onOrderedImage { onOrderedImage(image, meta.clipboard) } else { onImage(image) }
            try? FileManager.default.removeItem(at: file.file)
        } else { onFile(file) }
        sendText(["type": "transfer_done", "id": meta.id])
        onStatus(L10n.tr("transfer_received", kind(meta)))
        progress(meta, .received, bytes: meta.size, direction: .receiving)
    }
    private func progress(_ meta: TransferMetadata, _ phase: TransferProgress.Phase, bytes: Int64, direction: TransferProgress.Direction) {
        onProgress(TransferProgress(peerID: peerID, metadata: meta, direction: direction, phase: phase, completedBytes: bytes))
    }
    private func kind(_ meta: TransferMetadata) -> String { L10n.tr(meta.kind == "image" ? "transfer_kind_image" : "transfer_kind_file") }
    private func acknowledge(_ id: String, offset: Int64) { sendText(["type": "transfer_ack", "id": id, "offset": offset]) }
    private func waitForReply() {
        deadline?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.closed else { return }
            self.onStatus(L10n.tr("transfer_paused"))
            self.disconnect()
            self.close()
        }
        deadline = work
        queue.asyncAfter(deadline: .now() + 60, execute: work)
    }
    func close() {
        guard !closed else { return }
        closed = true; deadline?.cancel(); deadline = nil
        if let outgoing { progress(outgoing.metadata, .paused, bytes: lastAcknowledged, direction: .sending) }
        for meta in incoming.values { progress(meta, .paused, bytes: incomingOffsets[meta.id] ?? 0, direction: .receiving) }
        incoming.removeAll(); incomingOffsets.removeAll(); outgoing = nil
    }
}
