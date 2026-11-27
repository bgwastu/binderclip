package net.wastu.binderclip

import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

data class TransferBatch(val id: String, val index: Int, val count: Int) {
    init {
        require(FileTransferProtocol.validId(id) && count in 1..MAX_FILES && index in 0 until count)
    }

    companion object {
        const val MAX_FILES = 128
        fun parse(json: JSONObject) = TransferBatch(json.getString("id"), json.getInt("index"), json.getInt("count"))
    }

    fun json() = JSONObject().put("id", id).put("index", index).put("count", count)
    fun transferId(): String = UUID.nameUUIDFromBytes("$id:$index".toByteArray(Charsets.UTF_8)).toString()
}

internal enum class ShareFileState { PREPARING, QUEUED, SENT, RECEIVED, REJECTED, CANCELLED, UNREADABLE, TOO_LARGE, STORAGE, QUEUE_FULL, EXPIRED }

enum class TransferFailure(val message: Int) {
    CANCELLED(R.string.transfer_cancelled),
    INVALID(R.string.transfer_invalid),
    INTEGRITY(R.string.transfer_integrity_failed),
    STORAGE(R.string.transfer_storage_failed),
    QUEUE_FULL(R.string.transfer_receiver_full);

    companion object {
        fun parse(value: String): TransferFailure? = entries.find { it.name == value }

        internal fun from(error: Exception): TransferFailure = when (error) {
            is ShareQueueFull -> QUEUE_FULL
            is TransferIntegrityError -> INTEGRITY
            is java.io.IOException, is SecurityException -> STORAGE
            else -> INVALID
        }
    }
}

internal data class ShareFile(
    val id: String,
    val batch: TransferBatch,
    val name: String?,
    val state: ShareFileState,
    val bytes: Long?,
    val isOutgoing: Boolean = true,
    val downloadUri: String? = null,
    val mimeType: String? = null,
    val failure: TransferFailure? = null,
)

internal class ShareQueueFull : java.io.IOException()
internal class ShareFileTooLarge : java.io.IOException()
internal class TransferIntegrityError : java.io.IOException()

internal data class TransferMetadata(
    val id: String,
    val kind: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
    val clipboard: ClipboardStamp? = null,
    val batch: TransferBatch? = null,
) {
    init {
        require(FileTransferProtocol.validId(id))
        require(kind == "file" || kind == "image")
        require(name == FileTransferProtocol.safeName(name))
        require(mimeType.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9.+-]*/[a-zA-Z0-9][a-zA-Z0-9.+-]*")) && mimeType.length <= 127)
        require(size in 0..if (kind == "image") ImagePayload.MAXIMUM_BYTES.toLong() else FileTransferProtocol.MAXIMUM_FILE_BYTES)
        require(kind != "image" || (size > 0 && mimeType in ImagePayload.ALLOWED_MIME_TYPES))
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(batch == null || kind == "file")
        require(clipboard == null || (kind == "image" && clipboard.kind == "image" && clipboard.hash == sha256))
    }

    fun json(): JSONObject = JSONObject().put("type", "transfer_offer").put("id", id).put("kind", kind)
        .put("name", name).put("mimeType", mimeType).put("size", size).put("sha256", sha256)
        .also { json ->
            clipboard?.let { json.put("clipboard", it.putInto(JSONObject())) }
            batch?.let { json.put("batch", it.json()) }
        }

    companion object {
        fun parse(json: JSONObject): TransferMetadata {
            val size = json.get("size")
            require(size is Long || size is Int)
            return TransferMetadata(json.getString("id"), json.getString("kind"), json.getString("name"),
                json.getString("mimeType"), (size as Number).toLong(), json.getString("sha256"),
                if (json.has("clipboard")) requireNotNull(ClipboardStamp.parse(json.getJSONObject("clipboard"))) else null,
                json.optJSONObject("batch")?.let(TransferBatch::parse))
        }
    }
}

internal object FileTransferProtocol {
    const val CAPABILITY = "chunkedTransfers"
    const val VERSION = 1
    const val CHUNK_BYTES = 512 * 1024
    const val MAXIMUM_FILE_BYTES = 512L * 1024 * 1024
    const val HEADER_BYTES = 48
    private val magic = byteArrayOf(0x42, 0x43, 0x54, 0x31)

    fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    fun safeName(value: String): String {
        val base = value.replace('\\', '/').substringAfterLast('/')
        val clean = base.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() && it !in ":<>\"|?*" }.trim().trim('.')
        val result = StringBuilder()
        var bytes = 0
        val points = clean.codePoints().iterator()
        while (points.hasNext()) {
            val next = String(Character.toChars(points.nextInt()))
            bytes += next.toByteArray(Charsets.UTF_8).size
            if (bytes > 180) break
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
internal data class ReceivedFile(val metadata: TransferMetadata, val file: File, val downloadUri: String? = null) {
    val storageId: String get() = file.parentFile!!.name
}
internal data class TransferReceipt(val offset: Long, val completed: Boolean)

data class TransferProgress(
    val state: State,
    val kind: String = "file",
    val percent: Int = 0,
    val transferId: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val isOutgoing: Boolean = true,
    val batch: TransferBatch? = null,
    val failure: TransferFailure? = null,
) {
    enum class State { QUEUED, OFFERING, RECEIVING, SENDING, VERIFYING, SENT, RECEIVED, CANCELLED, DECLINED, FAILED, INVALID, PAUSED }
    val canCancel: Boolean get() = isOutgoing && transferId != null &&
        (state == State.SENDING && percent < 100 || state in
            setOf(State.QUEUED, State.OFFERING, State.PAUSED))
    val isBusy: Boolean get() = state in setOf(State.OFFERING, State.RECEIVING, State.SENDING, State.VERIFYING)
    val isSuccess: Boolean get() = state in setOf(State.SENT, State.RECEIVED)
    val isError: Boolean get() = state in setOf(State.DECLINED, State.FAILED, State.INVALID)
    fun message(context: android.content.Context): String {
        failure?.let { return context.getString(it.message) }
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
            State.VERIFYING -> context.getString(R.string.transfers_verifying)
            State.SENT -> if (kind == "link") context.getString(R.string.url_sent_success) else context.getString(R.string.transfer_sent, label)
            State.RECEIVED -> context.getString(R.string.transfer_received, label)
            State.CANCELLED -> context.getString(R.string.transfer_cancelled)
            State.DECLINED -> context.getString(R.string.transfer_declined)
            State.FAILED -> context.getString(R.string.transfer_failed)
            State.INVALID -> context.getString(R.string.transfer_invalid)
            State.PAUSED -> context.getString(R.string.transfer_paused)
        }
    }
}

internal fun TransferMetadata.progress(
    state: TransferProgress.State,
    percent: Int = 0,
    isOutgoing: Boolean = true,
    failure: TransferFailure? = null,
) = TransferProgress(state, kind, percent, id, name, mimeType, isOutgoing, batch, failure)

/** Disk-backed, peer-bound transfers. Every instance shares the lock with share-sheet imports. */
internal class FileTransferStore(private val root: File) {
    companion object {
        private val lock = Any()
        private const val MAX_PENDING = 8
        private const val MAX_SPOOL_BYTES = 1024L * 1024 * 1024
        private const val MAX_HISTORY_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private var pairingGeneration = 0L
        private val preparing = mutableMapOf<File, Long>()
        private val imports = mutableSetOf<File>()
        val shareChanges = MutableStateFlow(0L)
        private const val MAX_PARTIAL = 4
        private const val MAX_RECEIVED_BYTES = 1024L * 1024 * 1024
        private const val MAX_RECEIVED_FILES = 128
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        fun forContext(context: android.content.Context) = FileTransferStore(File(context.filesDir, "file-transfers"))
    }
    private val outgoing get() = File(root, "outgoing")
    private val remoteBatches get() = File(root, "remote-batches")
    private val batches get() = File(root, "batches")
    private val staging get() = File(root, "preparing")
    private val incoming get() = File(root, "incoming")
    private val received get() = File(root, "received")

    fun beginShare(peerId: String, batch: TransferBatch, validateCommit: () -> Unit) = synchronized(lock) {
        validateCommit()
        val plan = File(batches, batch.id)
        check(batches.mkdirs() || batches.isDirectory)
        if (plan.isFile) {
            val previous = readJson(plan)
            check(previous.optString("peerId") == peerId && previous.optInt("count") == batch.count)
        } else {
            writeJson(plan, batch.json().put("peerId", peerId))
        }
        imports.add(plan)
        shareChanges.update { it + 1 }
    }

    fun endShare(batchId: String) = synchronized(lock) {
        imports.remove(File(batches, batchId))
        shareChanges.update { it + 1 }
    }

    fun stageSharedFile(
        peerId: String,
        batch: TransferBatch,
        name: String,
        mimeType: String,
        open: () -> InputStream,
        checkActive: () -> Unit = {},
        validateCommit: () -> Unit = {},
    ) {
        val id = batch.transferId()
        val target = File(outgoing, id)
        val directory = File(staging, id)
        val body = File(directory, "body")
        val (generation, budget) = synchronized(lock) {
            cleanup()
            if (File(target, "metadata").isFile || directory in preparing) return
            val queued = outgoing.listFiles().orEmpty().filter { File(it, "body").isFile }
            val budget = minOf(FileTransferProtocol.MAXIMUM_FILE_BYTES,
                MAX_SPOOL_BYTES - queued.sumOf { File(it, "body").length() } - preparing.values.sum())
            if (queued.size + preparing.size >= TransferBatch.MAX_FILES || budget < 0) throw ShareQueueFull()
            preparing[directory] = budget
            pairingGeneration to budget
        }
        try {
            check(directory.mkdirs() || directory.isDirectory)
            val (size, hash) = open().use { spool(it, body, FileTransferProtocol.MAXIMUM_FILE_BYTES, budget, checkActive) }
            val meta = TransferMetadata(id, "file", FileTransferProtocol.safeName(name), mimeType, size, hash, batch = batch)
            writeJson(File(directory, "metadata"), meta.json().put("peerId", peerId))
            synchronized(lock) {
                validateCommit()
                check(generation == pairingGeneration)
                check(outgoing.mkdirs() || outgoing.isDirectory)
                check(directory.renameTo(target))
                shareChanges.update { it + 1 }
            }
        } finally {
            synchronized(lock) {
                directory.deleteRecursively()
                preparing.remove(directory)
            }
        }
    }

    fun recordImportFailure(peerId: String, batch: TransferBatch, name: String?, state: ShareFileState, validateCommit: () -> Unit) = synchronized(lock) {
        validateCommit()
        val directory = File(outgoing, batch.transferId())
        val manifest = File(directory, "metadata")
        if (!manifest.exists()) {
            check(directory.mkdirs() || directory.isDirectory)
            writeJson(manifest, JSONObject().put("peerId", peerId).put("batch", batch.json())
                .put("name", name?.let(FileTransferProtocol::safeName)).put("shareState", state.name))
            shareChanges.update { it + 1 }
        }
    }

    fun shareFiles(peerId: String, batchId: String? = null): List<ShareFile> = synchronized(lock) {
        val files = outgoing.listFiles().orEmpty().sortedByDescending { it.lastModified() }.mapNotNull { directory ->
            runCatching {
                val manifest = readJson(File(directory, "metadata"))
                if (manifest.optString("peerId") != peerId) return@mapNotNull null
                val batch = manifest.optJSONObject("batch")?.let(TransferBatch::parse) ?: return@mapNotNull null
                if (batchId != null && batch.id != batchId) return@mapNotNull null
                val state = ShareFileState.valueOf(manifest.optString("shareState", ShareFileState.QUEUED.name))
                ShareFile(directory.name, batch, manifest.optString("name").takeIf(String::isNotEmpty),
                    state, manifest.optLong("size", -1).takeIf { it >= 0 },
                    failure = TransferFailure.parse(manifest.optString("reason")))
            }.getOrNull()
        }
        val seen = files.mapTo(mutableSetOf(), ShareFile::id)
        val missing = batches.listFiles().orEmpty().filter { FileTransferProtocol.validId(it.name) && (batchId == null || it.name == batchId) }.flatMap { plan ->
            runCatching {
                val json = readJson(plan)
                if (json.optString("peerId") != peerId) return@flatMap emptyList()
                val batch = TransferBatch.parse(json)
                val state = if (plan in imports) ShareFileState.PREPARING else ShareFileState.UNREADABLE
                List(batch.count) { batch.copy(index = it) }
                    .mapNotNull { item -> item.transferId().takeUnless(seen::contains)?.let { ShareFile(it, item, null, state, null) } }
            }.getOrDefault(emptyList())
        }
        files + missing
    }

    fun acceptRemoteBatch(peerId: String, json: JSONObject) = synchronized(lock) {
        val rows = parseRemoteBatch(json)
        val file = File(remoteBatches, SyncProtocol.sha256Hex(peerId) + "-" + rows.first().batch.id)
        check(remoteBatches.mkdirs() || remoteBatches.isDirectory)
        if (!file.isFile || file.readText() != json.toString()) {
            writeJson(file, json)
            shareChanges.update { it + 1 }
        }
    }

    private fun parseRemoteBatch(json: JSONObject): List<ShareFile> {
        val id = json.getString("id")
        val count = json.getInt("count")
        val items = json.getJSONArray("items")
        require(items.length() in 1..TransferBatch.MAX_FILES && items.length() <= count)
        val ids = mutableSetOf<String>()
        val indexes = mutableSetOf<Int>()
        return List(items.length()) { index ->
            val row = items.getJSONObject(index)
            val fileId = row.getString("id")
            val batch = TransferBatch(id, row.getInt("index"), count)
            require(FileTransferProtocol.validId(fileId) && ids.add(fileId) && indexes.add(batch.index))
            val name = row.optString("name").takeIf(String::isNotBlank)?.let(FileTransferProtocol::safeName)
            val bytes = row.optLong("size", -1).takeIf { it in 0..FileTransferProtocol.MAXIMUM_FILE_BYTES }
            val state = ShareFileState.valueOf(row.getString("state"))
            ShareFile(fileId, batch, name, state, bytes, isOutgoing = false,
                failure = TransferFailure.parse(row.optString("reason")))
        }
    }

    fun downloadHistory(peerId: String): List<ShareFile> = synchronized(lock) {
        val prefix = SyncProtocol.sha256Hex(peerId) + "-"
        val local = incoming.listFiles().orEmpty().filter { it.name.startsWith(prefix) }.sortedByDescending { it.lastModified() }.mapNotNull { folder ->
            runCatching {
                val json = readJson(File(folder, "metadata"))
                val metadata = TransferMetadata.parse(json)
                if (metadata.kind != "file") return@mapNotNull null
                val state = when {
                    json.optBoolean("completed") -> ShareFileState.RECEIVED
                    json.optBoolean("failed") -> ShareFileState.REJECTED
                    else -> ShareFileState.QUEUED
                }
                ShareFile(metadata.id, metadata.batch ?: TransferBatch(metadata.id, 0, 1), metadata.name, state, metadata.size,
                    isOutgoing = false, downloadUri = json.optString("download").takeIf(String::isNotBlank),
                    mimeType = metadata.mimeType, failure = TransferFailure.parse(json.optString("reason")))
            }.getOrNull()
        }
        val localIds = local.mapTo(mutableSetOf(), ShareFile::id)
        val announced = remoteBatches.listFiles().orEmpty().filter { it.name.startsWith(prefix) }
            .sortedByDescending { it.lastModified() }.take(16).flatMap {
                runCatching { parseRemoteBatch(readJson(it, maximumBytes = 96 * 1024)) }.getOrDefault(emptyList())
            }
        local + announced.filter { localIds.add(it.id) }
    }

    fun retryShare(peerId: String, id: String): Boolean = synchronized(lock) {
        if (!FileTransferProtocol.validId(id)) return@synchronized false
        val directory = File(outgoing, id)
        val file = File(directory, "metadata")
        val manifest = runCatching { readJson(file) }.getOrNull() ?: return@synchronized false
        if (manifest.optString("peerId") != peerId || manifest.optString("shareState") != ShareFileState.REJECTED.name ||
            !File(directory, "body").isFile) return@synchronized false
        manifest.remove("reason")
        writeJson(file, manifest.put("shareState", ShareFileState.QUEUED.name))
        directory.setLastModified(System.currentTimeMillis())
        shareChanges.update { it + 1 }
        true
    }

    private fun spool(input: InputStream, body: File, maximum: Long, budget: Long = maximum, checkActive: () -> Unit = {}): Pair<Long, String> {
        var size = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(body).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > maximum) throw ShareFileTooLarge()
                if (size > budget) throw ShareQueueFull()
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
        return size to digest.digest().joinToString("") { "%02x".format(it) }
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
        require(outgoing.listFiles().orEmpty().count {
            File(it, "body").isFile && runCatching { !readJson(File(it, "metadata")).has("batch") }.getOrDefault(false)
        } < MAX_PENDING) { "Transfer queue full" }
        val id = UUID.randomUUID().toString()
        val directory = File(outgoing, id)
        check(directory.mkdirs())
        val body = File(directory, "body")
        try {
            val maximum = if (kind == "image") ImagePayload.MAXIMUM_BYTES.toLong() else FileTransferProtocol.MAXIMUM_FILE_BYTES
            val (size, hash) = spool(input, body, maximum)
            val meta = TransferMetadata(id, kind, FileTransferProtocol.safeName(name), mimeType, size, hash, clipboard)
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
                if (json.optString("shareState", ShareFileState.QUEUED.name) != ShareFileState.QUEUED.name) return@runCatching null
                val meta = TransferMetadata.parse(json)
                val body = File(directory, "body")
                if (json.getString("peerId") == peerId && body.isFile && body.length() == meta.size)
                    OutgoingTransfer(peerId, meta, body) else null
            }.getOrNull()
        }
    }

    fun finishOutgoing(peerId: String, id: String, state: ShareFileState, failure: TransferFailure? = null) = synchronized(lock) {
        require(FileTransferProtocol.validId(id))
        val directory = File(outgoing, id)
        val manifest = File(directory, "metadata")
        if (!manifest.isFile) return@synchronized
        val json = readJson(manifest)
        require(json.optString("peerId") == peerId)
        if (!json.has("batch")) {
            directory.deleteRecursively()
        } else {
            writeJson(manifest, json.put("shareState", state.name).put("reason", failure?.name))
            if (state != ShareFileState.REJECTED) File(directory, "body").delete()
            shareChanges.update { it + 1 }
        }
        Unit
    }

    fun cancelOutgoing(peerId: String, id: String): TransferMetadata? = synchronized(lock) {
        if (!FileTransferProtocol.validId(id)) return@synchronized null
        val directory = File(outgoing, id)
        val metadata = runCatching {
            val json = readJson(File(directory, "metadata"))
            if (json.getString("peerId") != peerId) return@synchronized null
            if (json.optString("shareState", ShareFileState.QUEUED.name) != ShareFileState.QUEUED.name) return@synchronized null
            TransferMetadata.parse(json)
        }.getOrNull() ?: return@synchronized null
        finishOutgoing(peerId, id, ShareFileState.CANCELLED)
        metadata
    }

    fun accept(peerId: String, meta: TransferMetadata, publishDownload: Boolean = false): TransferReceipt = synchronized(lock) {
        cleanup()
        val directory = incomingDirectory(peerId, meta.id)
        val manifest = File(directory, "metadata")
        if (manifest.isFile) {
            val saved = readJson(manifest)
            require(TransferMetadata.parse(saved) == meta)
            if (saved.optBoolean("failed")) {
                writeJson(manifest, meta.json())
                shareChanges.update { it + 1 }
            }
            if (saved.optBoolean("completed")) {
                val legacyStaged = File(File(received, directory.name), "body").isFile
                if (!publishDownload || saved.optBoolean("published") || !legacyStaged) return TransferReceipt(meta.size, true)
            }
        } else {
            if (incoming.listFiles().orEmpty().count {
                !runCatching {
                    val saved = readJson(File(it, "metadata"))
                    saved.optBoolean("completed") || saved.optBoolean("failed")
                }.getOrDefault(false)
            } >= MAX_PARTIAL) throw ShareQueueFull()
            if (meta.kind == "file") {
                val files = receivedFiles()
                if (files.size >= MAX_RECEIVED_FILES || files.sumOf { it.metadata.size } + meta.size > MAX_RECEIVED_BYTES) throw ShareQueueFull()
            }
            if (!directory.mkdirs()) throw java.io.IOException("Could not create incoming transfer")
            writeJson(manifest, meta.json())
            if (meta.kind == "file") shareChanges.update { it + 1 }
        }
        val body = File(directory, "body")
        val committed = File(File(received, directory.name), "body")
        if (meta.kind == "file" && !body.exists() && committed.isFile && committed.length() == meta.size) {
            require(TransferMetadata.parse(readJson(File(committed.parentFile, "metadata"))) == meta)
            if (FileInputStream(committed).use(FileTransferProtocol::hash) != meta.sha256) throw TransferIntegrityError()
            if (publishDownload) return TransferReceipt(meta.size, false)
            writeJson(manifest, meta.json().put("completed", true))
            return TransferReceipt(meta.size, true)
        }
        if (!body.exists() && !body.createNewFile()) throw java.io.IOException("Could not create transfer body")
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

    fun complete(peerId: String, meta: TransferMetadata, publishDownload: Boolean = false): ReceivedFile = synchronized(lock) {
        val directory = incomingDirectory(peerId, meta.id)
        val body = File(directory, "body")
        val stagedBody = File(File(received, directory.name), "body")
        if (publishDownload && meta.kind == "file" && !body.exists() && stagedBody.isFile) {
            return@synchronized ReceivedFile(meta, stagedBody)
        }
        require(body.isFile && body.length() == meta.size)
        if (FileInputStream(body).use(FileTransferProtocol::hash) != meta.sha256) {
            body.delete()
            throw TransferIntegrityError()
        }
        val destination = if (meta.kind == "file") {
            val files = receivedFiles()
            if (files.size >= MAX_RECEIVED_FILES || files.sumOf { it.metadata.size } + meta.size > MAX_RECEIVED_BYTES) throw ShareQueueFull()
            val targetDirectory = File(received, directory.name)
            if (!targetDirectory.mkdirs() && !targetDirectory.isDirectory) throw java.io.IOException("Could not create download staging directory")
            val target = File(targetDirectory, "body")
            writeJson(File(targetDirectory, "metadata"), meta.json())
            if (!body.renameTo(target)) throw java.io.IOException("Could not stage received file")
            target
        } else body
        writeJson(File(directory, "metadata"), meta.json().put("completed", !publishDownload || meta.kind != "file"))
        ReceivedFile(meta, destination)
    }

    fun finishPublication(peerId: String, file: ReceivedFile, uri: String) = synchronized(lock) {
        val manifest = File(incomingDirectory(peerId, file.metadata.id), "metadata")
        writeJson(manifest, file.metadata.json().put("completed", true).put("published", true).put("download", uri))
        deleteReceived(file.storageId)
        shareChanges.update { it + 1 }
    }

    fun markReceiveFailed(peerId: String, meta: TransferMetadata, reason: TransferFailure) = synchronized(lock) {
        val directory = incomingDirectory(peerId, meta.id)
        val manifest = File(directory, "metadata")
        if (directory.isDirectory) {
            File(directory, "body").delete()
            writeJson(manifest, meta.json().put("failed", true).put("reason", reason.name))
            shareChanges.update { it + 1 }
        }
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
    fun clearPairing() = synchronized(lock) {
        pairingGeneration++
        outgoing.deleteRecursively()
        batches.deleteRecursively()
        remoteBatches.deleteRecursively()
        received.listFiles().orEmpty().forEach { directory ->
            val manifest = File(File(incoming, directory.name), "metadata")
            val completed = runCatching { readJson(manifest).optBoolean("completed") }.getOrDefault(false)
            if (!completed) directory.deleteRecursively()
        }
        incoming.deleteRecursively()
        shareChanges.update { it + 1 }
    }

    private fun incomingDirectory(peerId: String, id: String): File {
        require(peerId.isNotBlank() && FileTransferProtocol.validId(id))
        return File(incoming, SyncProtocol.sha256Hex(peerId) + "-" + id)
    }
    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val oldIncoming = incoming.listFiles().orEmpty().filter { directory ->
            if (directory.lastModified() >= cutoff) return@filter false
            val completed = runCatching { readJson(File(directory, "metadata")).optBoolean("completed") }.getOrDefault(false)
            !completed || directory.lastModified() < System.currentTimeMillis() - MAX_HISTORY_AGE_MS
        }
        oldIncoming.forEach { directory ->
            File(received, directory.name).deleteRecursively()
            directory.deleteRecursively()
        }
        staging.listFiles().orEmpty().filter { it.lastModified() < cutoff && it !in preparing }.forEach { it.deleteRecursively() }
        val historyCutoff = System.currentTimeMillis() - MAX_HISTORY_AGE_MS
        batches.listFiles().orEmpty().filter { it.lastModified() < historyCutoff && it !in imports }.forEach { it.delete() }
        remoteBatches.listFiles().orEmpty().filter { it.lastModified() < historyCutoff }.forEach { it.delete() }
        outgoing.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach { directory ->
            val manifest = File(directory, "metadata")
            val data = runCatching { readJson(manifest) }.getOrNull()
            if (data == null || !data.has("batch") || directory.lastModified() < historyCutoff) {
                directory.deleteRecursively()
            } else if (File(directory, "body").isFile) {
                writeJson(manifest, data.put("shareState", ShareFileState.EXPIRED.name))
                File(directory, "body").delete()
                shareChanges.update { it + 1 }
            }
        }
    }
    private fun readJson(file: File, maximumBytes: Long = 8192): JSONObject {
        require(file.length() in 1..maximumBytes)
        return JSONObject(file.readText())
    }
    private fun writeJson(file: File, json: JSONObject) {
        val temporary = File(file.parentFile, "metadata.tmp")
        FileOutputStream(temporary).use { it.write(json.toString().toByteArray()); it.fd.sync() }
        check(temporary.renameTo(file))
    }
}
