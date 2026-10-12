package net.wastu.binderclip

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

internal data class TransferMetadata(
    val id: String,
    val kind: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
    val clipboard: ClipboardStamp? = null,
) {
    init {
        require(FileTransferProtocol.validId(id))
        require(kind == "file" || kind == "image")
        require(name == FileTransferProtocol.safeName(name))
        require(mimeType.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9.+-]*/[a-zA-Z0-9][a-zA-Z0-9.+-]*")) && mimeType.length <= 127)
        require(size in 0..if (kind == "image") ImagePayload.MAXIMUM_BYTES.toLong() else FileTransferProtocol.MAXIMUM_FILE_BYTES)
        require(kind != "image" || (size > 0 && mimeType in ImagePayload.ALLOWED_MIME_TYPES))
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(clipboard == null || (kind == "image" && clipboard.kind == "image" && clipboard.hash == sha256))
    }

    fun json(): JSONObject = JSONObject().put("type", "transfer_offer").put("id", id).put("kind", kind)
        .put("name", name).put("mimeType", mimeType).put("size", size).put("sha256", sha256)
        .also { json -> clipboard?.let { json.put("clipboard", it.putInto(JSONObject())) } }

    companion object {
        fun parse(json: JSONObject): TransferMetadata {
            val size = json.get("size")
            require(size is Long || size is Int)
            return TransferMetadata(json.getString("id"), json.getString("kind"), json.getString("name"),
                json.getString("mimeType"), (size as Number).toLong(), json.getString("sha256"),
                if (json.has("clipboard")) requireNotNull(ClipboardStamp.parse(json.getJSONObject("clipboard"))) else null)
        }
    }
}

internal object FileTransferProtocol {
    const val CAPABILITY = "chunkedTransfers"
    const val VERSION = 1
    const val CHUNK_BYTES = 512 * 1024
    const val MAXIMUM_FILE_BYTES = 100L * 1024 * 1024
    const val HEADER_BYTES = 48
    private val magic = byteArrayOf(0x42, 0x43, 0x54, 0x31)

    fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    fun safeName(value: String): String {
        val base = value.replace('\\', '/').substringAfterLast('/')
        val clean = base.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() && it !in ":<>\"|?*" }.trim().trim('.')
        val result = StringBuilder()
        for (point in clean.codePoints().toArray()) {
            val next = String(Character.toChars(point))
            if ((result.toString() + next).toByteArray(Charsets.UTF_8).size > 180) break
            result.append(next)
        }
        return result.toString().trim().trim('.').ifEmpty { "file" }
    }

    fun isChunk(data: ByteArray): Boolean = data.size >= 4 && data.copyOfRange(0, 4).contentEquals(magic)
    fun packChunk(id: String, offset: Long, data: ByteArray): ByteArray {
        require(validId(id) && offset >= 0 && data.size in 1..CHUNK_BYTES)
        return ByteBuffer.allocate(HEADER_BYTES + data.size).put(magic).put(id.toByteArray(Charsets.US_ASCII))
            .putLong(offset).put(data).array()
    }
    fun unpackChunk(packet: ByteArray): Triple<String, Long, ByteArray> {
        require(isChunk(packet) && packet.size in (HEADER_BYTES + 1)..(HEADER_BYTES + CHUNK_BYTES))
        val buffer = ByteBuffer.wrap(packet)
        buffer.position(4)
        val id = ByteArray(36).also(buffer::get).toString(Charsets.US_ASCII)
        val offset = buffer.long
        require(validId(id) && offset >= 0)
        return Triple(id, offset, ByteArray(buffer.remaining()).also(buffer::get))
    }

    fun hash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal data class OutgoingTransfer(val peerId: String, val metadata: TransferMetadata, val file: File)
internal data class ReceivedFile(val metadata: TransferMetadata, val file: File) {
    val storageId: String get() = file.parentFile!!.name
}
internal data class TransferReceipt(val offset: Long, val completed: Boolean)

internal data class SharedFileSource(val name: String, val mimeType: String, val kind: String, val open: () -> InputStream)

data class TransferProgress(
    val state: State,
    val kind: String = "file",
    val percent: Int = 0,
    val transferId: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val isOutgoing: Boolean = true,
) {
    enum class State { QUEUED, OFFERING, RECEIVING, SENDING, SENT, RECEIVED, CANCELLED, DECLINED, FAILED, INVALID, PAUSED, UPDATE_REQUIRED }
    val canCancel: Boolean get() = isOutgoing && transferId != null &&
        (state == State.SENDING && percent < 100 || state in
            setOf(State.QUEUED, State.OFFERING, State.PAUSED, State.UPDATE_REQUIRED))
    val isBusy: Boolean get() = state in setOf(State.OFFERING, State.RECEIVING, State.SENDING)
    val isSuccess: Boolean get() = state in setOf(State.SENT, State.RECEIVED)
    val isError: Boolean get() = state in setOf(State.DECLINED, State.FAILED, State.INVALID, State.UPDATE_REQUIRED)
    fun message(context: android.content.Context): String {
        val label = context.getString(when (kind) {
            "image" -> R.string.transfer_kind_image
            "text" -> R.string.transfer_kind_text
            else -> R.string.transfer_kind_file
        })
        return when (state) {
            State.QUEUED -> context.getString(R.string.transfer_queued)
            State.OFFERING -> context.getString(R.string.transfer_offering, label)
            State.RECEIVING -> context.getString(R.string.transfer_receiving, label) + if (percent > 0) " ($percent%)" else ""
            State.SENDING -> context.getString(R.string.transfer_sending, label, percent)
            State.SENT -> if (kind == "link") context.getString(R.string.url_sent_success) else context.getString(R.string.transfer_sent, label)
            State.RECEIVED -> context.getString(R.string.transfer_received, label)
            State.CANCELLED -> context.getString(R.string.transfer_cancelled)
            State.DECLINED -> context.getString(R.string.transfer_declined)
            State.FAILED -> context.getString(R.string.transfer_failed)
            State.INVALID -> context.getString(R.string.transfer_invalid)
            State.PAUSED -> context.getString(R.string.transfer_paused)
            State.UPDATE_REQUIRED -> context.getString(R.string.transfer_update_required)
        }
    }
}

internal fun TransferMetadata.progress(state: TransferProgress.State, percent: Int = 0, isOutgoing: Boolean = true) =
    TransferProgress(state, kind, percent, id, name, mimeType, isOutgoing)

/** Disk-backed, peer-bound transfers. Every instance shares the lock with share-sheet imports. */
internal class FileTransferStore(private val root: File) {
    companion object {
        private val lock = Any()
        private const val MAX_PENDING = 8
        private const val MAX_PARTIAL = 4
        private const val MAX_RECEIVED_BYTES = 500L * 1024 * 1024
        private const val MAX_RECEIVED_FILES = 128
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        fun forContext(context: android.content.Context) = FileTransferStore(File(context.filesDir, "file-transfers"))
    }
    private val outgoing get() = File(root, "outgoing")
    private val incoming get() = File(root, "incoming")
    private val received get() = File(root, "received")

    // Share imports and the transport's next() use the same lock. A failed batch is
    // rolled back before any sender can offer an earlier file from that batch.
    fun stageBatch(peerId: String, sources: List<SharedFileSource>, validate: () -> Unit = {}): List<OutgoingTransfer> = synchronized(lock) {
        require(sources.isNotEmpty() && sources.size <= MAX_PENDING)
        val staged = ArrayList<OutgoingTransfer>()
        try {
            for (source in sources) {
                source.open().use { staged += stage(peerId, source.name, source.mimeType, source.kind, it) }
            }
            validate()
            staged
        } catch (error: Exception) {
            staged.forEach { removeOutgoing(it.metadata.id) }
            throw error
        }
    }

    fun stage(peerId: String, name: String, mimeType: String, kind: String, input: InputStream, clipboard: ClipboardStamp? = null): OutgoingTransfer = synchronized(lock) {
        cleanup()
        if (clipboard != null) {
            outgoing.listFiles().orEmpty().forEach { directory ->
                val json = runCatching { readJson(File(directory, "metadata")) }.getOrNull() ?: return@forEach
                val meta = runCatching { TransferMetadata.parse(json) }.getOrNull() ?: return@forEach
                val body = File(directory, "body")
                if (json.optString("peerId") == peerId && meta.clipboard?.event == clipboard.event && body.length() == meta.size) {
                    return@synchronized OutgoingTransfer(peerId, meta, body)
                }
            }
        }
        require(peerId.isNotBlank())
        require(outgoing.listFiles().orEmpty().size < MAX_PENDING) { "Transfer queue full" }
        val id = UUID.randomUUID().toString()
        val directory = File(outgoing, id)
        check(directory.mkdirs())
        val body = File(directory, "body")
        try {
            val maximum = if (kind == "image") ImagePayload.MAXIMUM_BYTES.toLong() else FileTransferProtocol.MAXIMUM_FILE_BYTES
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            FileOutputStream(body).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    require(size <= maximum) { "Transfer too large" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            val meta = TransferMetadata(id, kind, FileTransferProtocol.safeName(name), mimeType, size,
                digest.digest().joinToString("") { "%02x".format(it) }, clipboard)
            writeJson(File(directory, "metadata"), meta.json().put("peerId", peerId))
            OutgoingTransfer(peerId, meta, body)
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun next(peerId: String): OutgoingTransfer? = synchronized(lock) {
        cleanup()
        outgoing.listFiles().orEmpty().sortedBy { it.lastModified() }.firstNotNullOfOrNull { directory ->
            runCatching {
                val json = readJson(File(directory, "metadata"))
                val meta = TransferMetadata.parse(json)
                val body = File(directory, "body")
                if (json.getString("peerId") == peerId && body.isFile && body.length() == meta.size)
                    OutgoingTransfer(peerId, meta, body) else null
            }.getOrNull()
        }
    }

    fun removeOutgoing(id: String) = synchronized(lock) {
        require(FileTransferProtocol.validId(id))
        File(outgoing, id).deleteRecursively()
        Unit
    }

    fun cancelOutgoing(peerId: String, id: String): TransferMetadata? = synchronized(lock) {
        if (!FileTransferProtocol.validId(id)) return@synchronized null
        val directory = File(outgoing, id)
        val metadata = runCatching {
            val json = readJson(File(directory, "metadata"))
            if (json.getString("peerId") != peerId) return@synchronized null
            TransferMetadata.parse(json)
        }.getOrNull() ?: return@synchronized null
        check(directory.deleteRecursively())
        metadata
    }

    fun accept(peerId: String, meta: TransferMetadata): TransferReceipt = synchronized(lock) {
        cleanup()
        val directory = incomingDirectory(peerId, meta.id)
        val manifest = File(directory, "metadata")
        if (manifest.isFile) {
            val saved = readJson(manifest)
            require(TransferMetadata.parse(saved) == meta)
            if (saved.optBoolean("completed")) return TransferReceipt(meta.size, true)
        } else {
            require(incoming.listFiles().orEmpty().count { !runCatching { readJson(File(it, "metadata")).optBoolean("completed") }.getOrDefault(false) } < MAX_PARTIAL)
            if (meta.kind == "file") {
                val files = receivedFiles()
                require(files.size < MAX_RECEIVED_FILES && files.sumOf { it.metadata.size } + meta.size <= MAX_RECEIVED_BYTES)
            }
            check(directory.mkdirs())
            writeJson(manifest, meta.json())
        }
        val body = File(directory, "body")
        val committed = File(File(received, directory.name), "body")
        if (meta.kind == "file" && !body.exists() && committed.isFile && committed.length() == meta.size) {
            require(TransferMetadata.parse(readJson(File(committed.parentFile, "metadata"))) == meta)
            require(FileInputStream(committed).use(FileTransferProtocol::hash) == meta.sha256)
            writeJson(manifest, meta.json().put("completed", true))
            return TransferReceipt(meta.size, true)
        }
        if (!body.exists()) check(body.createNewFile())
        require(body.length() <= meta.size)
        // An interrupted write can leave a fraction of a chunk. Re-request that chunk.
        val length = body.length()
        val durable = if (length == meta.size) length else length / FileTransferProtocol.CHUNK_BYTES * FileTransferProtocol.CHUNK_BYTES
        if (durable != length) RandomAccessFile(body, "rw").use { it.setLength(durable) }
        TransferReceipt(durable, false)
    }

    fun append(peerId: String, meta: TransferMetadata, offset: Long, bytes: ByteArray): Long = synchronized(lock) {
        val directory = incomingDirectory(peerId, meta.id)
        val body = File(directory, "body")
        require(body.isFile && body.length() == offset)
        require(offset < meta.size && bytes.size.toLong() == minOf(FileTransferProtocol.CHUNK_BYTES.toLong(), meta.size - offset))
        FileOutputStream(body, true).use { it.write(bytes); it.fd.sync() }
        directory.setLastModified(System.currentTimeMillis())
        offset + bytes.size
    }

    fun complete(peerId: String, meta: TransferMetadata): ReceivedFile = synchronized(lock) {
        val directory = incomingDirectory(peerId, meta.id)
        val body = File(directory, "body")
        require(body.isFile && body.length() == meta.size)
        if (FileInputStream(body).use(FileTransferProtocol::hash) != meta.sha256) {
            directory.deleteRecursively()
            error("Transfer integrity failed")
        }
        val destination = if (meta.kind == "file") {
            val files = receivedFiles()
            require(files.size < MAX_RECEIVED_FILES && files.sumOf { it.metadata.size } + meta.size <= MAX_RECEIVED_BYTES)
            val targetDirectory = File(received, directory.name)
            check(targetDirectory.mkdirs() || targetDirectory.isDirectory)
            val target = File(targetDirectory, "body")
            writeJson(File(targetDirectory, "metadata"), meta.json())
            check(body.renameTo(target))
            target
        } else body
        writeJson(File(directory, "metadata"), meta.json().put("completed", true))
        ReceivedFile(meta, destination)
    }

    fun discardIncoming(peerId: String, id: String) = synchronized(lock) { incomingDirectory(peerId, id).deleteRecursively(); Unit }
    fun receivedFiles(): List<ReceivedFile> = synchronized(lock) {
        received.listFiles().orEmpty().sortedByDescending { it.lastModified() }.mapNotNull { directory ->
            runCatching {
                val meta = TransferMetadata.parse(readJson(File(directory, "metadata")))
                File(directory, "body").takeIf { it.isFile && it.length() == meta.size }?.let { ReceivedFile(meta, it) }
            }.getOrNull()
        }
    }
    fun deleteReceived(id: String) = synchronized(lock) {
        require(id.length == 101 && id.take(64).matches(Regex("[0-9a-f]{64}")) && id[64] == '-' && FileTransferProtocol.validId(id.substring(65)))
        File(received, id).deleteRecursively()
        Unit
    }
    fun clearPairing() = synchronized(lock) { outgoing.deleteRecursively(); incoming.deleteRecursively(); Unit }

    private fun incomingDirectory(peerId: String, id: String): File {
        require(peerId.isNotBlank() && FileTransferProtocol.validId(id))
        return File(incoming, SyncProtocol.sha256Hex(peerId) + "-" + id)
    }
    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        listOf(outgoing, incoming).forEach { base -> base.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach { it.deleteRecursively() } }
    }
    private fun readJson(file: File): JSONObject {
        require(file.length() in 1..8192)
        return JSONObject(file.readText())
    }
    private fun writeJson(file: File, json: JSONObject) {
        val temporary = File(file.parentFile, "metadata.tmp")
        FileOutputStream(temporary).use { it.write(json.toString().toByteArray()); it.fd.sync() }
        check(temporary.renameTo(file))
    }
}
