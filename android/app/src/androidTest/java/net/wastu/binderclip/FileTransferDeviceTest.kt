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
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileTransferDeviceTest {
    @Test fun mixedShareResumesWithPairedMac() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareFileTransfer") == "true")
        check(AppProfile.current.isDebug)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val devices = DeviceStore(context)
        check(devices.groupKey == null && devices.peer == null) { "Hardware tests require an unpaired debug app" }
        val pairingFile = File(context.filesDir, "device-test-pair.json")
        val pairing = JSONObject(pairingFile.readText())
        pairingFile.delete()
        val peerId = pairing.getString("id")
        val host = pairing.getString("host")
        val port = pairing.getInt("port")
        check(port == AppProfile.current.port)
        val run = peerId.take(8)
        val zipName = "binderclip-$run-batch.zip"
        val photoName = "binderclip-$run-photo.png"
        val clipboard = AppClipboard(context)
        val store = FileTransferStore.forContext(context)
        val fixtures = mutableListOf<Uri>()
        val autoApply = devices.autoApplyClipboard
        val previous = clipboard.primaryClip
        val previousImage = ImageClipboard.read(context, clipboard)

        fun document(name: String, mime: String, write: (OutputStream) -> Unit): Uri {
            val resolver = context.contentResolver
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            fixtures += uri
            checkNotNull(resolver.openOutputStream(uri, "w")).use(write)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        }

        fun hash(uri: Uri) = checkNotNull(context.contentResolver.openInputStream(uri)).use(FileTransferProtocol::hash)
        fun startAction(action: String, id: String? = null) {
            context.startForegroundService(Intent(context, BinderClipService::class.java).setAction(action)
                .putExtra(BinderClipService.EXTRA_TRANSFER_ID, id))
        }
        fun currentText() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

        val result = File(context.filesDir, "device-test-files.json")
        try {
            devices.groupKey = java.util.Base64.getDecoder().decode(pairing.getString("key"))
            devices.peer = RememberedPeer("BinderClip Transfer Test", host, port, peerId)
            devices.peerCandidates = listOf("$host:$port")
            devices.autoApplyClipboard = true
            val zip = document(zipName, "application/zip") { output ->
                ZipOutputStream(output).use { archive ->
                    archive.setLevel(0)
                    archive.putNextEntry(ZipEntry("payload.bin"))
                    val block = ByteArray(64 * 1024) { (it % 251).toByte() }
                    repeat(160) { archive.write(block) }
                    archive.closeEntry()
                }
            }
            val photo = document(photoName, "image/png") { output ->
                val image = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
                try {
                    image.eraseColor(android.graphics.Color.BLUE)
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
                } finally { image.recycle() }
            }
            val photoHash = hash(photo)
            result.writeText(JSONObject().put(zipName, hash(zip)).put(photoName, photoHash).toString())

            ActivityScenario.launch(MainActivity::class.java).use { main ->
                main.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                waitFor("authenticated WebSocket") {
                    AppRuntime.state.value.peer?.connected == true &&
                        AppRuntime.state.value.transportType in setOf(TransportType.LAN, TransportType.MESH)
                }
                val marker = "BinderClip private file test $run"
                clipboard.setPrimaryClip(ClipData.newPlainText("BinderClip synthetic test", marker))
                val share = Intent(context, ShareReceiverActivity::class.java).apply {
                    action = Intent.ACTION_SEND_MULTIPLE
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(zip, photo))
                    clipData = ClipData.newUri(context.contentResolver, "BinderClip test batch", zip).apply {
                        addItem(ClipData.Item(photo))
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ActivityScenario.launch<ShareReceiverActivity>(share).use {
                    waitFor("both batch receipts") {
                        store.shareFiles(peerId).count { row ->
                            row.name in setOf(zipName, photoName) && row.state == ShareFileState.SENT
                        } == 2
                    }
                }
                assertEquals(1, store.shareFiles(peerId).filter { it.name in setOf(zipName, photoName) }
                    .map { it.batch.id }.distinct().size)
                waitFor("explicit Mac image download") {
                    store.downloadHistory(peerId).any { it.state == ShareFileState.RECEIVED && it.downloadUri != null }
                }
                val image = store.downloadHistory(peerId).first { it.downloadUri != null }
                val uri = Uri.parse(image.downloadUri)
                fixtures += uri
                assertEquals(photoHash, hash(uri))
                assertTrue("Shared files or text changed the clipboard", currentText() == marker)

                startAction(BinderClipService.ACTION_COPY_SHARED_IMAGE, image.id)
                waitFor("manually copied image") { ImageClipboard.read(context, clipboard)?.sha256 == photoHash }

                waitFor("first text share") { devices.nextReceivedShare()?.text == "first test share" }
                val first = checkNotNull(devices.nextReceivedShare())
                startAction(BinderClipService.ACTION_COPY_SHARED_TEXT)
                waitFor("ordered second text share") { devices.nextReceivedShare()?.text == "second test share" }
                assertTrue("First text share could not be copied", currentText() == "first test share")
                assertTrue(devices.receiveShare(first)) // An ACK lost on reconnect must not put the item back.
                startAction(BinderClipService.ACTION_COPY_SHARED_TEXT)
                waitFor("empty inbox after copying both shares") { !devices.hasPendingSharedText() }
                assertTrue("Second text share could not be copied", currentText() == "second test share")
            }
        } finally {
            context.stopService(Intent(context, BinderClipService::class.java))
            devices.unpair()
            devices.autoApplyClipboard = autoApply
            fixtures.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            if (previousImage != null) ImageClipboard.write(context, clipboard, previousImage)
            else if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
        }
    }

    private fun waitFor(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        assertTrue("Timed out: $label", predicate())
    }
}
