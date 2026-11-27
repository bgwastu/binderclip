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
    private val announceBatches: Boolean = false,
    private val publishFile: ((ReceivedFile) -> String)? = null,
    private val onOrderedImage: ((ImagePayload, ClipboardStamp?) -> Unit)? = null,
) {
    private var outgoing: OutgoingTransfer? = null
    private var offered = false
    private var expectedOffset = 0L
    private var lastAcknowledged = 0L
    private var deadline: ScheduledFuture<*>? = null
    private val incoming = LinkedHashMap<String, TransferMetadata>()
    private var closed = false
    private val announced = mutableMapOf<String, String>()
    private val cancelled = LinkedHashSet<String>()

    fun cancel(id: String): Boolean {
        val transfer = outgoing?.takeIf { it.metadata.id == id && !closed } ?: return false
        // The final chunk may have reached the Mac before its receipt reaches us.
        // Once all bytes are in flight, leave the receipt to determine completion.
        if (!offered && expectedOffset == transfer.metadata.size) {
            onStatus(transfer.metadata.progress(TransferProgress.State.VERIFYING, 100))
            return true
        }
        val metadata = store.cancelOutgoing(peerId, id) ?: return false
        deadline?.cancel(false)
        outgoing = null
        cancelled += id
        if (cancelled.size > 128) cancelled.remove(cancelled.first())
        onStatus(metadata.progress(TransferProgress.State.CANCELLED))
        if (!sendText(rejection(id, TransferFailure.CANCELLED))) {
            disconnect(); close()
        } else pump()
        return true
    }

    fun pump() {
        if (closed) return
        if (announceBatches && !announceShares()) { disconnect(); close(); return }
        if (outgoing != null) return
        val next = store.next(peerId) ?: return
        outgoing = next
        offered = true
        expectedOffset = 0L
        lastAcknowledged = 0L
        if (!sendText(next.metadata.json())) { close(); return }
        waitForReply()
        onStatus(next.metadata.progress(TransferProgress.State.OFFERING))
    }

    private fun announceShares(): Boolean {
        val batches = store.shareFiles(peerId).filter { it.state != ShareFileState.PREPARING }.groupBy { it.batch.id }.values.take(16)
        val active = batches.mapTo(mutableSetOf()) { it.first().batch.id }
        announced.keys.retainAll(active)
        for (items in batches) {
            val batch = items.first().batch
            val entries = org.json.JSONArray().also { array ->
                items.sortedBy { it.batch.index }.forEach { file ->
                    array.put(JSONObject().put("id", file.id).put("index", file.batch.index)
                        .put("name", file.name).put("size", file.bytes).put("state", file.state.name)
                        .put("reason", file.failure?.name))
                }
            }
            val json = JSONObject().put("type", "transfer_batch").put("id", batch.id)
                .put("count", batch.count).put("items", entries)
            val snapshot = json.toString()
            if (announced[batch.id] == snapshot) continue
            if (!sendText(json)) return false
            announced[batch.id] = snapshot
        }
        return true
    }

    fun handle(json: JSONObject) {
        if (closed) return
        val id = json.optString("id")
        if (!FileTransferProtocol.validId(id)) { disconnect(); return }
        if (id in cancelled) return // Ignore acknowledgements/receipts already in flight when cancelled.
        val type = json.optString("type")
        var offeredMeta: TransferMetadata? = null
        var acceptedOffer = false
        try {
            when (type) {
                "transfer_offer" -> {
                    val meta = TransferMetadata.parse(json)
                    offeredMeta = meta
                    if (incoming.size >= 4 && !incoming.containsKey(id)) throw ShareQueueFull()
                    val state = store.accept(peerId, meta, publishDownload = publishFile != null)
                    if (state.completed) {
                        sendText(JSONObject().put("type", "transfer_done").put("id", id))
                    } else {
                        acceptedOffer = true
                        incoming[id] = meta
                        if (state.offset == meta.size) {
                            complete(meta)
                            return
                        }
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
                    val state = if (expectedOffset == transfer.metadata.size) TransferProgress.State.VERIFYING else TransferProgress.State.SENDING
                    onStatus(transfer.metadata.progress(state, (offset * 100 / transfer.metadata.size).toInt()))
                }
                "transfer_done" -> {
                    val transfer = outgoing?.takeIf { it.metadata.id == id } ?: return
                    require(offered || expectedOffset == transfer.metadata.size)
                    deadline?.cancel(false)
                    store.finishOutgoing(peerId, id, ShareFileState.SENT)
                    outgoing = null
                    onStatus(transfer.metadata.progress(TransferProgress.State.SENT))
                    pump()
                }
                "transfer_cancel" -> {
                    val reason = TransferFailure.parse(json.optString("reason"))
                    if (reason != null && reason != TransferFailure.CANCELLED &&
                        (incoming.containsKey(id) || outgoing?.metadata?.id == id)
                    ) DiagnosticLog.warning("Peer transfer failure: ${reason.name}")
                    incoming.remove(id)?.let { meta ->
                        if (reason == null || reason == TransferFailure.CANCELLED) store.discardIncoming(peerId, id)
                        else failReceive(meta, reason)
                    }
                    outgoing?.takeIf { it.metadata.id == id }?.let { transfer ->
                        deadline?.cancel(false)
                        val cancelled = reason == TransferFailure.CANCELLED
                        store.finishOutgoing(peerId, id, if (cancelled) ShareFileState.CANCELLED else ShareFileState.REJECTED, reason)
                        outgoing = null
                        onStatus(transfer.metadata.progress(
                            if (cancelled) TransferProgress.State.CANCELLED else TransferProgress.State.DECLINED,
                            failure = reason,
                        ))
                        pump()
                    }
                }
            }
        } catch (error: Exception) {
            val reason = TransferFailure.from(error)
            if (type == "transfer_offer") {
                if (offeredMeta != null && (acceptedOffer || reason != TransferFailure.INVALID)) failReceive(offeredMeta, reason)
                reject(id, reason)
                offeredMeta?.let { onStatus(it.progress(TransferProgress.State.FAILED, isOutgoing = false, failure = reason)) }
            } else if (outgoing?.metadata?.id == id) {
                reject(id, reason)
                outgoing?.let { onStatus(it.metadata.progress(TransferProgress.State.FAILED, failure = reason)) }
                disconnect()
                close()
            }
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
        } catch (error: Exception) {
            val reason = TransferFailure.from(error)
            if (id != null) {
                metadata?.let { failReceive(it, reason) }
                reject(id, reason)
            } else {
                DiagnosticLog.warning("Transfer rejected: ${reason.name}")
                disconnect()
            }
            onStatus(metadata?.progress(TransferProgress.State.FAILED, isOutgoing = false, failure = reason)
                ?: TransferProgress(TransferProgress.State.FAILED, isOutgoing = false, failure = reason))
        }
    }

    private fun rejection(id: String, reason: TransferFailure) =
        JSONObject().put("type", "transfer_cancel").put("id", id).put("reason", reason.name)

    private fun reject(id: String, reason: TransferFailure) {
        DiagnosticLog.warning("Transfer rejected: ${reason.name}")
        if (!sendText(rejection(id, reason))) disconnect()
    }

    private fun failReceive(meta: TransferMetadata, reason: TransferFailure) {
        incoming.remove(meta.id)
        runCatching {
            if (meta.kind == "file") store.markReceiveFailed(peerId, meta, reason)
            else store.discardIncoming(peerId, meta.id)
        }
    }

    private fun acknowledge(id: String, offset: Long) {
        if (!sendText(JSONObject().put("type", "transfer_ack").put("id", id).put("offset", offset))) disconnect()
    }
    private fun complete(meta: TransferMetadata) {
        val file = store.complete(peerId, meta, publishDownload = publishFile != null)
        if (meta.kind == "image") {
            val image = ImagePayload(id = meta.id, mimeType = meta.mimeType, data = file.file.readBytes())
            if (onOrderedImage != null) onOrderedImage.invoke(image, meta.clipboard) else onImage(image)
            file.file.delete()
        } else if (publishFile != null) {
            val uri = try {
                publishFile.invoke(file).also { store.finishPublication(peerId, file, it) }
            } catch (error: Exception) {
                throw java.io.IOException("Could not publish download", error)
            }
            onFile(file.copy(downloadUri = uri))
        } else onFile(file)
        incoming.remove(meta.id)
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
