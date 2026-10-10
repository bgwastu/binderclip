package net.wastu.binderclip

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileTransferDeviceTest {
    @Test fun explicitFilesRoundTripWithPairedMac() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareFileTransfer") == "true")
        assumeTrue(AppProfile.current.isDebug)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = AppClipboard(context)
        val store = FileTransferStore.forContext(context)
        val stage = File(context.filesDir, "device-test-file-stage.txt")
        val ack = File(context.filesDir, "device-test-file-ack.txt")
        val run = UUID.randomUUID().toString().take(8)
        val phoneName = "binderclip-device-$run-phone.bin"
        val emptyName = "binderclip-device-$run-empty.txt"
        val macName = "binderclip-device-$run-mac.bin"
        val size = 50L * 1024 * 1024 + 137
        val fixtures = ArrayList<Uri>()
        val receivedIds = ArrayList<String>()
        fun waitFor(label: String, timeout: Long = 180_000, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < deadline) {
                if (predicate()) return
                Thread.sleep(100)
            }
            assertTrue("Timed out: $label", predicate())
        }
        fun fixture(name: String, bytes: Long, seed: Int, mime: String): Pair<Uri, String> {
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Could not create test document")
            fixtures += uri
            val hash = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                val buffer = ByteArray(64 * 1024) { ((it * 31 + seed) and 255).toByte() }
                var remaining = bytes
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size.toLong()).toInt()
                    output.write(buffer, 0, count)
                    hash.update(buffer, 0, count)
                    remaining -= count
                }
            }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri to hash.digest().joinToString("") { "%02x".format(it) }
        }
        fun share(uri: Uri, mime: String) {
            val intent = Intent(context, ShareReceiverActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra("share_as_file", true)
                clipData = ClipData.newUri(context.contentResolver, "BinderClip synthetic test file", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ActivityScenario.launch<ShareReceiverActivity>(intent).use {
                waitFor("share activity import") { it.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { main ->
            main.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            waitFor("authenticated WebSocket") { AppRuntime.state.value.peer?.connected == true &&
                AppRuntime.state.value.transportType in setOf(TransportType.LAN, TransportType.MESH) }
            Thread.sleep(2_000)
            val previous = clipboard.primaryClip
            val previousImage = ImageClipboard.read(context, clipboard)
            val marker = "BinderClip private file test $run"
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("BinderClip synthetic test", marker))
                val (phoneUri, phoneHash) = fixture(phoneName, size, 7, "application/octet-stream")
                val (emptyUri, emptyHash) = fixture(emptyName, 0, 0, "text/plain")
                val macHash = MessageDigest.getInstance("SHA-256").run {
                    val buffer = ByteArray(64 * 1024) { ((it * 31 + 19) and 255).toByte() }
                    var remaining = size
                    while (remaining > 0) {
                        val count = minOf(remaining, buffer.size.toLong()).toInt()
                        update(buffer, 0, count)
                        remaining -= count
                    }
                    digest().joinToString("") { "%02x".format(it) }
                }
                File(context.filesDir, "device-test-files.json").writeText(JSONObject()
                    .put("run", run).put("deviceId", DeviceStore(context).deviceId)
                    .put("phoneName", phoneName).put("phoneHash", phoneHash)
                    .put("emptyName", emptyName).put("emptyHash", emptyHash)
                    .put("macName", macName).put("macHash", macHash).put("size", size)
                    .put("transport", AppRuntime.state.value.transportType.name).toString())
                stage.writeText("phone-file")
                share(phoneUri, "application/octet-stream")
                waitFor("Mac file verification") { ack.exists() && ack.readText() == "phone-file" }
                assertEquals(marker, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                stage.writeText("empty-file")
                share(emptyUri, "text/plain")
                waitFor("empty file verification") { ack.exists() && ack.readText() == "empty-file" }
                assertEquals(marker, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                stage.writeText("mac-file")
                waitFor("Mac file delivery") { store.receivedFiles().any { it.metadata.name == macName } }
                val received = store.receivedFiles().single { it.metadata.name == macName }
                receivedIds += received.storageId
                assertEquals(size, received.file.length())
                assertEquals(macHash, received.file.inputStream().use(FileTransferProtocol::hash))
                assertEquals(marker, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                stage.writeText("save-file")
                ActivityScenario.launch(ReceivedFilesActivity::class.java).use {
                    waitFor("Save As verification") { ack.exists() && ack.readText() == "save-file" }
                }
                stage.writeText("passed")
            } catch (error: Throwable) {
                stage.writeText("failed")
                throw error
            } finally {
                fixtures.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                receivedIds.forEach(store::deleteReceived)
                if (previousImage != null) ImageClipboard.write(context, clipboard, previousImage)
                else if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
            }
        }
    }
}
