package net.wastu.binderclip

import org.json.JSONObject
import java.io.RandomAccessFile
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Called only on the transfer executor; one acknowledged chunk is in flight per direction. */
internal class ChunkedTransferSession(
    private val peerId: String,
    private val store: FileTransferStore,
    private val executor: ScheduledExecutorService,
    private val sendText: (JSONObject) -> Boolean,
    private val sendBinary: (ByteArray) -> Boolean,
    private val disconnect: () -> Unit,
    private val onImage: (ImagePayload) -> Unit,
    private val onFile: (ReceivedFile) -> Unit,
    private val onStatus: (TransferProgress) -> Unit,
    private val onOrderedImage: ((ImagePayload, ClipboardStamp?) -> Unit)? = null,
) {
    private var outgoing: OutgoingTransfer? = null
    private var offered = false
    private var expectedOffset = 0L
    private var lastAcknowledged = 0L
    private var deadline: ScheduledFuture<*>? = null
    private val incoming = LinkedHashMap<String, TransferMetadata>()
    private var closed = false
    private val cancelled = LinkedHashSet<String>()

    fun cancel(id: String): Boolean {
        val transfer = outgoing?.takeIf { it.metadata.id == id && !closed } ?: return false
        // The final chunk may have reached the Mac before its receipt reaches us.
        // Once all bytes are in flight, leave the receipt to determine completion.
        if (!offered && expectedOffset == transfer.metadata.size) {
            onStatus(transfer.metadata.progress(TransferProgress.State.SENDING, 100))
            return true
        }
        val metadata = store.cancelOutgoing(peerId, id) ?: return false
        deadline?.cancel(false)
        outgoing = null
        cancelled += id
        if (cancelled.size > 128) cancelled.remove(cancelled.first())
        onStatus(metadata.progress(TransferProgress.State.CANCELLED))
        if (!sendText(JSONObject().put("type", "transfer_cancel").put("id", id))) {
            disconnect(); close()
        } else pump()
        return true
    }

    fun pump() {
        if (closed || outgoing != null) return
        val next = store.next(peerId) ?: return
        outgoing = next
        offered = true
        if (!sendText(next.metadata.json())) { close(); return }
        waitForReply()
        onStatus(next.metadata.progress(TransferProgress.State.OFFERING))
    }

    fun handle(json: JSONObject) {
        if (closed) return
        val id = json.optString("id")
        if (!FileTransferProtocol.validId(id)) { disconnect(); return }
        if (id in cancelled) return // Ignore acknowledgements/receipts already in flight when cancelled.
        try {
            when (json.getString("type")) {
                "transfer_offer" -> {
                    val meta = TransferMetadata.parse(json)
                    require(incoming.size < 4 || incoming.containsKey(id))
                    val state = store.accept(peerId, meta)
                    if (state.completed) {
                        sendText(JSONObject().put("type", "transfer_done").put("id", id))
                    } else if (state.offset == meta.size) {
                        complete(meta)
                    } else {
                        incoming[id] = meta
                        acknowledge(id, state.offset)
                        onStatus(meta.progress(TransferProgress.State.RECEIVING, isOutgoing = false))
                    }
                }
                "transfer_ack" -> {
                    val transfer = outgoing?.takeIf { it.metadata.id == id } ?: return
                    val value = json.get("offset")
                    require(value is Int || value is Long)
                    val offset = (value as Number).toLong()
                    require(offset in 0 until transfer.metadata.size && offset % FileTransferProtocol.CHUNK_BYTES == 0L)
                    if (offered) {
                        offered = false
                    } else {
                        if (offset == lastAcknowledged) return // Duplicate ack cannot advance or enqueue another chunk.
                        require(offset == expectedOffset)
                    }
                    deadline?.cancel(false)
                    lastAcknowledged = offset
                    val count = minOf(FileTransferProtocol.CHUNK_BYTES.toLong(), transfer.metadata.size - offset).toInt()
                    val bytes = ByteArray(count)
                    RandomAccessFile(transfer.file, "r").use { it.seek(offset); it.readFully(bytes) }
                    expectedOffset = offset + count
                    if (!sendBinary(FileTransferProtocol.packChunk(id, offset, bytes))) { disconnect(); close(); return }
                    waitForReply()
                    onStatus(transfer.metadata.progress(TransferProgress.State.SENDING, (expectedOffset * 100 / transfer.metadata.size).toInt()))
                }
                "transfer_done" -> {
                    val transfer = outgoing?.takeIf { it.metadata.id == id } ?: return
                    require(offered || expectedOffset == transfer.metadata.size)
                    deadline?.cancel(false)
                    store.removeOutgoing(id)
                    outgoing = null
                    onStatus(transfer.metadata.progress(TransferProgress.State.SENT))
                    pump()
                }
                "transfer_cancel" -> {
                    incoming.remove(id)?.let { store.discardIncoming(peerId, id) }
                    outgoing?.takeIf { it.metadata.id == id }?.let { transfer ->
                        deadline?.cancel(false)
                        store.removeOutgoing(id)
                        outgoing = null
                        onStatus(transfer.metadata.progress(TransferProgress.State.DECLINED))
                        pump()
                    }
                }
            }
        } catch (_: Exception) {
            val metadata = outgoing?.metadata?.takeIf { it.id == id } ?: incoming[id]
            incoming.remove(id)
            store.discardIncoming(peerId, id)
            sendText(JSONObject().put("type", "transfer_cancel").put("id", id))
            onStatus(metadata?.progress(TransferProgress.State.FAILED) ?: TransferProgress(TransferProgress.State.FAILED, transferId = id))
            if (outgoing?.metadata?.id == id) { disconnect(); close() }
        }
    }

    fun handleChunk(packet: ByteArray) {
        if (closed) return
        var id: String? = null
        var metadata: TransferMetadata? = null
        try {
            val (chunkId, offset, bytes) = FileTransferProtocol.unpackChunk(packet)
            id = chunkId
            val meta = incoming[chunkId] ?: error("Unsolicited chunk")
            metadata = meta
            val next = store.append(peerId, meta, offset, bytes)
            if (next == meta.size) complete(meta) else {
                acknowledge(chunkId, next)
                onStatus(meta.progress(TransferProgress.State.RECEIVING, (next * 100 / meta.size).toInt(), isOutgoing = false))
            }
        } catch (_: Exception) {
            if (id != null) {
                incoming.remove(id)
                store.discardIncoming(peerId, id)
                sendText(JSONObject().put("type", "transfer_cancel").put("id", id))
            } else disconnect()
            onStatus(metadata?.progress(TransferProgress.State.INVALID) ?: TransferProgress(TransferProgress.State.INVALID, transferId = id))
        }
    }

    private fun acknowledge(id: String, offset: Long) {
        if (!sendText(JSONObject().put("type", "transfer_ack").put("id", id).put("offset", offset))) disconnect()
    }
    private fun complete(meta: TransferMetadata) {
        val file = store.complete(peerId, meta)
        incoming.remove(meta.id)
        if (meta.kind == "image") {
            val image = ImagePayload(id = meta.id, mimeType = meta.mimeType, data = file.file.readBytes())
            if (onOrderedImage != null) onOrderedImage.invoke(image, meta.clipboard) else onImage(image)
            file.file.delete()
        } else onFile(file)
        sendText(JSONObject().put("type", "transfer_done").put("id", meta.id))
        onStatus(meta.progress(TransferProgress.State.RECEIVED, isOutgoing = false))
    }
    private fun waitForReply() {
        deadline?.cancel(false)
        deadline = executor.schedule({
            if (!closed) { onStatus(outgoing?.metadata?.progress(TransferProgress.State.PAUSED) ?: TransferProgress(TransferProgress.State.PAUSED)); disconnect(); close() }
        }, 60, TimeUnit.SECONDS)
    }
    fun close() {
        closed = true
        deadline?.cancel(false)
        deadline = null
        incoming.clear()
        outgoing = null
    }
}
