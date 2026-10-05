package net.wastu.binderclip

import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual share activities, Mac Services, and Browser Links notifications. */
@RunWith(AndroidJUnit4::class)
class ManualShareDeviceTest {
    @Test fun shareSheetServicesAndBrowserLinkNotification() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareManualShare") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val status = File(context.filesDir, "device-test-stage.txt")
        val bluetooth = InstrumentationRegistry.getArguments().getString("expectedTransport") == "BLUETOOTH"
        val androidText = "BinderClip manual share Android to Mac"
        val macText = "BinderClip Mac Services to Android"
        val copiedUrl = "https://example.com/?binderclip-test=copied"
        val sharedUrl = "https://example.com/?binderclip-test=shared"
        fun waitFor(label: String, predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (predicate()) return
                Thread.sleep(150)
            }
            assertTrue(label, predicate())
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("Authenticated transport") { AppRuntime.state.value.peer?.connected == true &&
                if (bluetooth) AppRuntime.state.value.transportType == TransportType.BLUETOOTH
                else AppRuntime.state.value.transportType in setOf(TransportType.LAN, TransportType.MESH) }
            File(context.filesDir, "device-test-selected-transport.txt").writeText(AppRuntime.state.value.transportType.name)
            val previous = clipboard.primaryClip
            val previousImage = ImageClipboard.read(context, clipboard)
            val previousLink = notifications.activeNotifications.firstOrNull { it.id == 103 }?.notification
            try {
                notifications.cancel(103)
                val share = Intent(context, ShareReceiverActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, androidText)
                }
                ActivityScenario.launch<ShareReceiverActivity>(share).close()
                status.writeText("manual-android-text")
                waitFor("Mac Services text return") { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == macText }
                clipboard.setPrimaryClip(ClipData.newPlainText("BinderClip copied URL test", copiedUrl))
                Thread.sleep(1_000)
                assertTrue("Copying a URL must not create Browser Links", notifications.activeNotifications.none { it.id == 103 })
                status.writeText("manual-copied-url")
                waitFor("Browser Links notification") { notifications.activeNotifications.any {
                    it.id == 103 && it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == sharedUrl
                } }
                val link = notifications.activeNotifications.first { it.id == 103 }.notification
                assertNotNull(link.contentIntent)
                assertTrue(link.channelId != "binderclip")
                status.writeText("passed")
            } catch (error: Throwable) {
                status.writeText("failed")
                throw error
            } finally {
                notifications.cancel(103)
                if (previousLink != null) notifications.notify(103, previousLink)
                if (previousImage != null) ImageClipboard.write(context, clipboard, previousImage)
                else if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
            }
        }
    }
}
