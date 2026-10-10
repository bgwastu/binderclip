package net.wastu.binderclip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical test. A Mac coordinator verifies delivery and supplies the return clips.
 * Run only with hardwareRoundTrip=true and the coordinator described in battery-validation.md.
 */
@RunWith(AndroidJUnit4::class)
class TransportDeviceTest {
    @Test fun encryptedClipboardRoundTripWithPairedMac() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareRoundTrip") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = AppClipboard(context)
        val status = File(context.filesDir, "device-test-stage.txt")
        val expected = InstrumentationRegistry.getArguments().getString("expectedTransport") ?: "NETWORK"
        val bluetooth = expected == "BLUETOOTH"
        val suffix = (if (bluetooth) " BLE" else "") + "/" + java.util.UUID.randomUUID().toString().take(8)
        val large = InstrumentationRegistry.getArguments().getString("largeText") == "true"
        val promotion = InstrumentationRegistry.getArguments().getString("promotion") == "true"
        val androidText = "BinderClip physical test: Android to Mac — 2026-10-04$suffix".repeat(if (large) 4096 else 1)
        val macText = "BinderClip physical test: Mac to Android — 2026-10-04$suffix".repeat(if (large) 4096 else 1)
        File(context.filesDir, "device-test-android.txt").writeText(androidText)
        File(context.filesDir, "device-test-mac.txt").writeText(macText)
        fun stage(value: String) { status.writeText(value) }
        fun waitFor(label: String, predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (predicate()) return
                Thread.sleep(100)
            }
            assertTrue("Timed out: $label", predicate())
        }
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }
        val outgoing = ImagePayload(mimeType = "image/png", data = png(Color.rgb(25, 160, 90)))
        val incoming = ImagePayload(mimeType = "image/png", data = png(Color.rgb(190, 60, 120)))
        File(context.filesDir, "device-test-android.png").writeBytes(outgoing.data)
        File(context.filesDir, "device-test-mac.png").writeBytes(incoming.data)
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("authenticated transport session") {
                AppRuntime.state.value.peer?.connected == true &&
                    (if (bluetooth) AppRuntime.state.value.transportType == TransportType.BLUETOOTH
                    else AppRuntime.state.value.transportType in setOf(TransportType.LAN, TransportType.MESH))
            }
            assertTrue(AppRuntime.state.value.rootAvailable)
            assertTrue(AppRuntime.state.value.automaticClipboardEnabled)
            if (bluetooth) assertEquals(TransportType.BLUETOOTH, AppRuntime.state.value.transportType)
            else if (expected == "MESH" || expected == "LAN") assertEquals(TransportType.valueOf(expected), AppRuntime.state.value.transportType)
            else assertTrue(AppRuntime.state.value.transportType in setOf(TransportType.MESH, TransportType.LAN))
            File(context.filesDir, "device-test-selected-transport.txt").writeText(AppRuntime.state.value.transportType.name)
            // Let restored pre-test clipboard updates drain before injecting a fresh local change.
            Thread.sleep(3_000)
            val previous = clipboard.primaryClip
            val previousImage = ImageClipboard.read(context, clipboard)
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("BinderClip test", androidText))
                stage("android-text")
                waitFor("Mac text return") { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == macText }
                stage("mac-text-received")
                if (promotion) {
                    ImageClipboard.write(context, clipboard, outgoing)
                    Thread.sleep(3_500)
                    assertEquals("Image must wait for WebSocket", TransportType.BLUETOOTH, AppRuntime.state.value.transportType)
                    stage("deferred-image")
                    waitFor("automatic WebSocket promotion") {
                        AppRuntime.state.value.transportType in setOf(TransportType.MESH, TransportType.LAN)
                    }
                    stage("promoted-image")
                    waitFor("deferred image return") { ImageClipboard.read(context, clipboard)?.sha256 == incoming.sha256 }
                    stage("echo-prior-text")
                    waitFor("Previous text after image") { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == androidText }
                    waitFor("Bluetooth release after WebSocket grace period") { !AppRuntime.state.value.bluetoothActive }
                } else if (!bluetooth) {
                    // A distinct image in each direction also checks encrypted binary framing.
                    ImageClipboard.write(context, clipboard, outgoing)
                    stage("android-image")
                    waitFor("Mac image return") { ImageClipboard.read(context, clipboard)?.sha256 == incoming.sha256 }
                    assertEquals("image/png", ImageClipboard.read(context, clipboard)?.mimeType)
                    stage("echo-prior-text")
                    waitFor("Previous text after image") { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == androidText }
                }
                stage("passed")
            } catch (error: Throwable) {
                val checkpoint = DeviceStore(context).clipboardCheckpoint()
                val head = checkpoint?.optJSONObject("head")
                val actualImage = ImageClipboard.read(context, clipboard)
                File(context.filesDir, "device-test-failure.json").writeText(org.json.JSONObject()
                    .put("transport", AppRuntime.state.value.transportType.name)
                    .put("connected", AppRuntime.state.value.peer?.connected == true)
                    .put("interactive", context.getSystemService(android.os.PowerManager::class.java).isInteractive)
                    .put("outgoingStillOnClipboard", clipboard.primaryClip?.getItemAt(0)?.text?.toString() == androidText)
                    .put("actualImageIsOutgoing", actualImage?.sha256 == outgoing.sha256)
                    .put("actualImageIsIncoming", actualImage?.sha256 == incoming.sha256)
                    .put("versionKind", head?.optString("clipKind"))
                    .put("versionTime", head?.optLong("clipTime"))
                    .put("versionCounter", head?.optLong("clipCounter"))
                    .put("versionIsOutgoingImage", head?.optString("clipHash") == outgoing.sha256)
                    .put("versionIsIncomingImage", head?.optString("clipHash") == incoming.sha256)
                    .toString())
                stage("failed")
                throw error
            } finally {
                if (previousImage != null) ImageClipboard.write(context, clipboard, previousImage)
                else if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
            }
        }
    }
}
