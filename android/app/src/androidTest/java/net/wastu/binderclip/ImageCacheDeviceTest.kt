package net.wastu.binderclip

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageCacheDeviceTest {
    @Test fun largeImageIsCachedAndMutationBehindSameUriIsDelivered() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareImageCache") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = AppClipboard(context)
        val stage = File(context.filesDir, "device-test-stage.txt")
        val ack = File(context.filesDir, "device-test-ack.txt")
        val observeSeconds = InstrumentationRegistry.getArguments().getString("cacheObserveSeconds")?.toIntOrNull()?.coerceIn(70, 600) ?: 70
        val allowedReads = (observeSeconds + 59) / 60 + 1
        val random = Random(1234)
        val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(1024 * 1024) { random.nextInt() or -0x1000000 }, 0, 1024, 0, 0, 1024, 1024)
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val changed = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        File(context.filesDir, "device-test-cache-large.png").writeBytes(bytes)
        File(context.filesDir, "device-test-cache-changed.png").writeBytes(changed)
        fun waitFor(label: String, predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (predicate()) return
                Thread.sleep(150)
            }
            assertTrue(label, predicate())
        }
        fun snapshot() = EnergyCounters.snapshot()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            waitFor("Network session") { AppRuntime.state.value.peer?.connected == true && AppRuntime.state.value.transportType in setOf(TransportType.LAN, TransportType.MESH) }
            File(context.filesDir, "device-test-selected-transport.txt").writeText(AppRuntime.state.value.transportType.name)
            // Let the coordinator's restored clipboard settle across the new connection.
            Thread.sleep(3_000)
            val previous = clipboard.primaryClip
            val previousImage = ImageClipboard.read(context, clipboard)
            try {
                ImageClipboard.write(context, clipboard, ImagePayload(mimeType = "image/png", data = bytes))
                val originalUri = clipboard.primaryClip!!.getItemAt(0).uri!!
                stage.writeText("cache-large-image")
                waitFor("Large image delivered") { ack.exists() && ack.readText() == "cache-large-image" }
                Thread.sleep(4_000)
                val before = snapshot()
                val end = android.os.SystemClock.elapsedRealtime() + observeSeconds * 1_000L
                while (android.os.SystemClock.elapsedRealtime() < end) {
                    assertTrue("Test clipboard was replaced; leave paired devices' clipboards untouched",
                        clipboard.primaryClip?.getItemAt(0)?.uri == originalUri)
                    Thread.sleep(1_000)
                }
                val after = snapshot()
                val reads = (after["clipboard.read"] ?: 0) - (before["clipboard.read"] ?: 0)
                val hashed = (after["image.hash.bytes"] ?: 0) - (before["image.hash.bytes"] ?: 0)
                assertTrue("Unchanged clipboard repeatedly decoded: $reads", reads in 0..allowedReads)
                assertTrue("Unchanged image repeatedly hashed: $hashed bytes", hashed in 0..allowedReads.toLong() * bytes.size)
                File(context.filesDir, "device-test-cache.json").writeText(JSONObject()
                    .put("imageBytes", bytes.size).put("unchangedSeconds", observeSeconds).put("reads", reads).put("hashBytes", hashed).toString())
                context.contentResolver.openOutputStream(originalUri, "w")!!.use { it.write(changed) }
                // Do not replace ClipData or notify: the URI and callback generation remain stable.
                stage.writeText("cache-mutated-image")
                waitFor("Mutation behind unchanged URI delivered") { ack.exists() && ack.readText() == "cache-mutated-image" }
                stage.writeText("passed")
            } catch (error: Throwable) {
                stage.writeText("failed")
                throw error
            } finally {
                if (previousImage != null) ImageClipboard.write(context, clipboard, previousImage)
                else if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
            }
        }
    }
}
