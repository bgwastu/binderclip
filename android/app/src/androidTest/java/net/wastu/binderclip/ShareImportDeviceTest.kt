package net.wastu.binderclip

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ShareImportDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun mixedShareIntentStagesFilesAndReportsUnreadableEntry() {
        assumeTrue(AppProfile.current.isDebug)
        val devices = DeviceStore(context)
        assumeTrue(devices.groupKey == null)
        val peerId = UUID.randomUUID().toString()
        val run = peerId.take(8)
        val fixtures = linkedMapOf<Uri, ByteArray>()
        fun document(name: String, mime: String, bytes: ByteArray): Uri {
            val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "binderclip-$run-$name")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            fixtures[uri] = bytes
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        }
        try {
            devices.groupKey = SecureChannel.randomNonce()
            devices.peer = RememberedPeer("Transfer test", "192.0.2.1", 39422, peerId)
            val zip = ByteArrayOutputStream().apply {
                ZipOutputStream(this).use {
                    it.putNextEntry(ZipEntry("fixture.txt"))
                    it.write("fixture".toByteArray())
                    it.closeEntry()
                }
            }
            val png = ByteArrayOutputStream().apply {
                val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                image.compress(Bitmap.CompressFormat.PNG, 100, this)
                image.recycle()
            }
            val missing = document("removed.txt", "text/plain", byteArrayOf())
            context.contentResolver.delete(missing, null, null)
            fixtures.remove(missing)
            val attachments = listOf(
                document("archive.zip", "application/zip", zip.toByteArray()),
                missing,
                document("photo.png", "image/png", png.toByteArray()),
                document("notes.txt", "text/plain", "plain text file".toByteArray()),
            )
            val clip = ClipData.newUri(context.contentResolver, "BinderClip test batch", attachments.first())
            // ClipData carries the grants the sender can issue. EXTRA_STREAM still names the unreadable item.
            attachments.drop(1).filter { it != missing }.forEach { clip.addItem(ClipData.Item(it)) }
            val share = Intent(Intent.ACTION_SEND_MULTIPLE).setPackage(context.packageName).apply {
                type = "*/*"
                clipData = clip
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachments))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val store = FileTransferStore.forContext(context)
            ActivityScenario.launch<ShareReceiverActivity>(share).use {
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val rows = store.shareFiles(peerId)
                    if (rows.size == attachments.size && rows.none { it.state == ShareFileState.PREPARING }) break
                    Thread.sleep(100)
                }
                val rows = store.shareFiles(peerId).sortedBy { it.batch.index }
                assertEquals(attachments.size, rows.size)
                assertEquals(1, rows.map { it.batch.id }.distinct().size)
                assertEquals(listOf(ShareFileState.QUEUED, ShareFileState.UNREADABLE, ShareFileState.QUEUED, ShareFileState.QUEUED),
                    rows.map { it.state })
                val files = rows.filter { it.state == ShareFileState.QUEUED }.associateBy { it.id }
                repeat(files.size) {
                    val file = checkNotNull(store.next(peerId))
                    assertNotNull(files[file.metadata.id])
                    assertEquals("file", file.metadata.kind)
                    assertArrayEquals(fixtures[attachments[file.metadata.batch!!.index]], file.file.readBytes())
                    store.finishOutgoing(peerId, file.metadata.id, ShareFileState.SENT)
                }
            }
        } finally {
            context.stopService(Intent(context, BinderClipService::class.java))
            devices.unpair()
            fixtures.keys.forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    @Test fun publicationReturnsTheSameVisibleDownloadWhenRetried() {
        assumeTrue(AppProfile.current.isDebug)
        val directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdir() }
        val content = byteArrayOf(4, 3, 2, 1)
        val body = File(directory, "body").apply { writeBytes(content) }
        val id = UUID.randomUUID().toString()
        val meta = TransferMetadata(id, "file", "binderclip-$id.zip", "application/zip", body.length(),
            content.inputStream().use(FileTransferProtocol::hash))
        val file = ReceivedFile(meta, body)
        val publisher = DownloadPublisher(context.contentResolver)
        var uri: Uri? = null
        try {
            val destination = publisher.publish(file)
            uri = Uri.parse(destination)
            assertEquals(destination, publisher.publish(file))
            val saved = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            assertArrayEquals(content, saved)
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                check(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            directory.deleteRecursively()
        }
    }
}
