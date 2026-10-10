package net.wastu.binderclip

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.concurrent.Executors

class FileTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "00000000-0000-0000-0000-000000000001"

    @Test fun chunkMatchesCrossPlatformWireVectorAndRejectsMalformedFrames() {
        val packet = FileTransferProtocol.packChunk(id, 524288, byteArrayOf(1, 2, 3))
        val expected = "42435431" + "30303030303030302d303030302d303030302d303030302d303030303030303030303031" + "0000000000080000" + "010203"
        assertEquals(expected, packet.joinToString("") { "%02x".format(it) })
        val (actualId, offset, body) = FileTransferProtocol.unpackChunk(packet)
        assertEquals(id, actualId)
        assertEquals(524288L, offset)
        assertArrayEquals(byteArrayOf(1, 2, 3), body)
        assertThrows(Exception::class.java) { FileTransferProtocol.unpackChunk(packet.copyOf(48)) }
        assertThrows(Exception::class.java) { FileTransferProtocol.unpackChunk(packet.also { it[40] = 0x80.toByte() }) }
        assertThrows(Exception::class.java) { FileTransferProtocol.packChunk(id, 0, ByteArray(FileTransferProtocol.CHUNK_BYTES + 1)) }
    }

    @Test fun metadataRejectsTraversalInvalidTypesAndOversizedFiles() {
        val valid = TransferMetadata(id, "file", "note.txt", "text/plain", 0, SyncProtocol.sha256Hex(byteArrayOf()))
        for ((key, value) in listOf("name" to "../note.txt", "name" to "a\\b", "size" to -1,
            "size" to 100L * 1024 * 1024 + 1, "size" to "12", "size" to true, "size" to 1.5,
            "id" to "../../other", "sha256" to "wrong", "mimeType" to "../file", "kind" to "executable")) {
            assertThrows("$key=$value", Exception::class.java) { TransferMetadata.parse(valid.json().put(key, value)) }
        }
        assertEquals("photo.png", FileTransferProtocol.safeName("../../photo.png"))
        assertEquals("report.pdf", FileTransferProtocol.safeName("C:\\private\\report.pdf"))
        assertEquals("file", FileTransferProtocol.safeName(".."))
        val unicode = FileTransferProtocol.safeName("😀".repeat(100) + ".txt")
        assertTrue(unicode.toByteArray().size <= 180)
        assertFalse(unicode.contains('\uFFFD'))
    }

    @Test fun imageVersionSurvivesRestartAndCatchupDoesNotQueueItTwice() {
        val root = temporary.newFolder()
        val store = FileTransferStore(root)
        val bytes = byteArrayOf(1, 2, 3)
        val stamp = ClipboardStamp(100, 0, "phone", id, "image", SyncProtocol.sha256Hex(bytes))
        val original = store.stage("mac", "image", "image/png", "image", bytes.inputStream(), stamp)
        val resumed = FileTransferStore(root)
        val repeated = resumed.stage("mac", "image", "image/png", "image", bytes.inputStream(), stamp)
        assertEquals(original.metadata, repeated.metadata)
        assertEquals(stamp, repeated.metadata.clipboard)
        for (value in listOf(true, JSONObject(), JSONObject().put("clipTime", 100))) {
            assertThrows(Exception::class.java) { TransferMetadata.parse(repeated.metadata.json().put("clipboard", value)) }
        }
        assertThrows(Exception::class.java) {
            TransferMetadata.parse(repeated.metadata.json().put("kind", "file"))
        }
        assertThrows(Exception::class.java) {
            TransferMetadata.parse(repeated.metadata.json().put("clipboard", stamp.putInto(JSONObject()).put("clipHash", SyncProtocol.sha256Hex("wrong"))))
        }
    }

    @Test fun restartResumesDurableChunksAndBindsThemToPeerAndMetadata() {
        val root = temporary.newFolder()
        var store = FileTransferStore(root)
        val bytes = ByteArray(FileTransferProtocol.CHUNK_BYTES * 2 + 37) { (it % 251).toByte() }
        val meta = TransferMetadata(id, "file", "metadata", "application/octet-stream", bytes.size.toLong(), SyncProtocol.sha256Hex(bytes))
        assertEquals(0, store.accept("phone", meta).offset)
        store.append("phone", meta, 0, bytes.copyOf(FileTransferProtocol.CHUNK_BYTES))
        val partial = File(root, "incoming").listFiles()!!.single().resolve("body")
        RandomAccessFile(partial, "rw").use { it.seek(it.length()); it.write(byteArrayOf(1, 2, 3)) }
        store = FileTransferStore(root)
        assertEquals(FileTransferProtocol.CHUNK_BYTES.toLong(), store.accept("phone", meta).offset)
        assertEquals(FileTransferProtocol.CHUNK_BYTES.toLong(), partial.length())
        assertThrows(Exception::class.java) { store.accept("phone", meta.copy(name = "different")) }
        assertEquals(0, store.accept("other-phone", meta).offset)
        assertThrows(Exception::class.java) { store.append("phone", meta, 0, bytes.copyOf(FileTransferProtocol.CHUNK_BYTES)) }
        val offset = store.append("phone", meta, FileTransferProtocol.CHUNK_BYTES.toLong(), bytes.copyOfRange(FileTransferProtocol.CHUNK_BYTES, FileTransferProtocol.CHUNK_BYTES * 2))
        store.append("phone", meta, offset, bytes.copyOfRange(offset.toInt(), bytes.size))
        val saved = store.complete("phone", meta)
        assertArrayEquals(bytes, saved.file.readBytes())
        assertEquals("metadata", store.receivedFiles().single().metadata.name)
        assertTrue(FileTransferStore(root).accept("phone", meta).completed)
        store.clearPairing()
        assertArrayEquals(bytes, store.receivedFiles().single().file.readBytes())
    }

    @Test fun corruptionIsRejectedBeforeAReceivedFileIsPublished() {
        val store = FileTransferStore(temporary.newFolder())
        val bytes = byteArrayOf(4, 5, 6)
        val meta = TransferMetadata(id, "file", "test", "application/octet-stream", 3, SyncProtocol.sha256Hex(bytes))
        store.accept("phone", meta)
        store.append("phone", meta, 0, byteArrayOf(4, 5, 7))
        assertThrows(Exception::class.java) { store.complete("phone", meta) }
        assertTrue(store.receivedFiles().isEmpty())
        assertEquals(0, store.accept("phone", meta).offset)
    }

    @Test fun interruptedFinalCommitIsRecoveredWithoutCreatingAnotherFile() {
        val root = temporary.newFolder()
        val store = FileTransferStore(root)
        val bytes = byteArrayOf(7, 8)
        val meta = TransferMetadata(id, "file", "test.txt", "text/plain", 2, SyncProtocol.sha256Hex(bytes))
        store.accept("phone", meta)
        store.append("phone", meta, 0, bytes)
        store.complete("phone", meta)
        val manifest = File(root, "incoming").listFiles()!!.single().resolve("metadata")
        manifest.writeText(meta.json().toString()) // Simulate a crash before the completion receipt commit.
        assertTrue(FileTransferStore(root).accept("phone", meta).completed)
        assertEquals(1, store.receivedFiles().size)
    }

    @Test fun outgoingQueueAndIncomingReservationsAreBoundedAndClearedOnUnpair() {
        val root = temporary.newFolder()
        val store = FileTransferStore(root)
        repeat(8) { byteArrayOf().inputStream().use { store.stage("mac", "empty", "text/plain", "file", it) } }
        assertThrows(Exception::class.java) { byteArrayOf().inputStream().use { store.stage("mac", "empty", "text/plain", "file", it) } }
        assertNotNull(FileTransferStore(root).next("mac"))
        store.clearPairing()
        assertNull(store.next("mac"))
        repeat(4) { index -> store.accept("phone", TransferMetadata(java.util.UUID.randomUUID().toString(), "file", "f$index", "text/plain", 1, SyncProtocol.sha256Hex(byteArrayOf(1)))) }
        assertThrows(Exception::class.java) { store.accept("phone", TransferMetadata(id, "file", "f5", "text/plain", 1, SyncProtocol.sha256Hex(byteArrayOf(1)))) }
    }

    private class Wire(val sender: FileTransferStore, val receiver: FileTransferStore) : AutoCloseable {
        private data class Packet(val toReceiver: Boolean, val kind: Byte, val bytes: ByteArray)
        private val queue = ArrayDeque<Packet>()
        private val executor = Executors.newSingleThreadScheduledExecutor()
        private val key = ByteArray(32) { it.toByte() }
        private val serverNonce = SecureChannel.randomNonce()
        private val clientNonce = SecureChannel.randomNonce()
        private val client = SecureChannel(key, serverNonce, clientNonce, "ws")
        private val server = SecureChannel(key, serverNonce, clientNonce, "ws", true)
        var maximumQueuedBytes = 0
        var deliveredImage: ImagePayload? = null
        var deliveredFiles = 0
        val resumeOffsets = ArrayList<Long>()
        private fun enqueue(toReceiver: Boolean, kind: Byte, bytes: ByteArray): Boolean {
            val encrypted = (if (toReceiver) client else server).seal(bytes, kind)
            queue.add(Packet(toReceiver, kind, encrypted))
            maximumQueuedBytes = maxOf(maximumQueuedBytes, queue.sumOf { it.bytes.size })
            return true
        }
        val a = ChunkedTransferSession("mac", sender, executor,
            sendText = { enqueue(true, 1, it.toString().toByteArray()) }, sendBinary = { enqueue(true, 2, it) },
            disconnect = { fail("Unexpected disconnect") }, onImage = { fail("Wrong image direction") }, onFile = { fail("Wrong file direction") }, onStatus = {})
        val b = ChunkedTransferSession("phone", receiver, executor,
            sendText = { if (it.optString("type") == "transfer_ack") resumeOffsets += it.getLong("offset"); enqueue(false, 1, it.toString().toByteArray()) },
            sendBinary = { enqueue(false, 2, it) }, disconnect = { fail("Unexpected disconnect") },
            onImage = { deliveredImage = it }, onFile = { deliveredFiles++ }, onStatus = {})
        fun step(): Boolean {
            val packet = queue.poll() ?: return false
            val plaintext = (if (packet.toReceiver) server else client).open(packet.bytes, packet.kind)
            val destination = if (packet.toReceiver) b else a
            if (packet.kind == 1.toByte()) destination.handle(JSONObject(plaintext.toString(Charsets.UTF_8))) else destination.handleChunk(plaintext)
            return true
        }
        fun drain() { var count = 0; while (step()) { check(++count < 1000) } }
        override fun close() { a.close(); b.close(); executor.shutdownNow() }
    }

    @Test fun seventeenMiBImageUsesBoundedEncryptedChunks() {
        val sender = FileTransferStore(temporary.newFolder())
        val receiver = FileTransferStore(temporary.newFolder())
        val image = ByteArray(17 * 1024 * 1024 + 113) { (it % 251).toByte() }
        image.inputStream().use { sender.stage("mac", "image", "image/png", "image", it) }
        Wire(sender, receiver).use { wire ->
            wire.a.pump()
            wire.drain()
            assertArrayEquals(image, wire.deliveredImage!!.data)
            assertTrue(wire.maximumQueuedBytes < 1024 * 1024)
            assertNull(sender.next("mac"))
            assertTrue(receiver.receivedFiles().isEmpty())
        }
    }

    @Test fun sessionsResumeAfterRestartAndCompletionReceiptDoesNotDeliverAgain() {
        val senderRoot = temporary.newFolder()
        val receiverRoot = temporary.newFolder()
        val sender = FileTransferStore(senderRoot)
        val receiver = FileTransferStore(receiverRoot)
        val bytes = ByteArray(FileTransferProtocol.CHUNK_BYTES * 3 + 59) { (it % 211).toByte() }
        val staged = bytes.inputStream().use { sender.stage("mac", "report.txt", "text/plain", "file", it) }
        Wire(sender, receiver).use { wire -> wire.a.pump(); repeat(3) { assertTrue(wire.step()) } }
        Wire(FileTransferStore(senderRoot), FileTransferStore(receiverRoot)).use { wire ->
            wire.a.pump()
            // Finish receiving but lose the final transfer_done before it reaches the sender.
            repeat(7) { assertTrue(wire.step()) }
            assertEquals(FileTransferProtocol.CHUNK_BYTES.toLong(), wire.resumeOffsets.first())
            assertEquals(1, wire.deliveredFiles)
            assertNotNull(sender.next("mac"))
        }
        Wire(FileTransferStore(senderRoot), FileTransferStore(receiverRoot)).use { wire ->
            wire.a.pump(); wire.drain()
            assertEquals(0, wire.deliveredFiles)
            assertNull(sender.next("mac"))
        }
        assertEquals(staged.metadata.sha256, receiver.receivedFiles().single().file.inputStream().use(FileTransferProtocol::hash))
        assertArrayEquals(bytes, receiver.receivedFiles().single().file.readBytes())
    }
}
