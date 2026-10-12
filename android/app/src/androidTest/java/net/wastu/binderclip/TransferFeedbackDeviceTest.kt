package net.wastu.binderclip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in UI acceptance with synthetic runtime state; never pairs or sends content. */
@RunWith(AndroidJUnit4::class)
class TransferFeedbackDeviceTest {
    @Test fun transferOverlayReplacesQueueFeedbackWithoutMovingTheActions() {
        assumeTrue(AppProfile.current.isDebug)
        assumeTrue(InstrumentationRegistry.getArguments().getString("uiFeedback") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("UI acceptance needs an awake, unlocked test device",
            context.getSystemService(android.os.PowerManager::class.java).isInteractive &&
                !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun find(label: String): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it)?.let { found -> return found } }
                return null
            }
            return automation.rootInActiveWindow?.let(::walk)
        }
        fun await(label: String): AccessibilityNodeInfo {
            val until = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < until) {
                find(label)?.let { return it }
                Thread.sleep(50)
            }
            error("Missing UI label: $label")
        }
        fun capture(name: String) {
            val bitmap = automation.takeScreenshot() ?: error("No screenshot")
            File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var cancelledId: String? = null
            val fixture = AppState(rootAvailable = true,
                peer = RememberedPeer("Test Mac", "192.0.2.1", 39422, "ui-test", connected = true),
                transportType = TransportType.LAN, transferProgress = null, transferStatus = null)
            val screen = mutableStateOf(fixture)
            scenario.onActivity { activity ->
                activity.setContent {
                    BinderClipTheme {
                        BinderClipScreen(state = screen.value, notificationsGranted = true,
                            bluetoothGranted = true, permissionRevision = 0,
                            onScan = {}, onRequestNotifications = {}, onSend = {}, onSendFile = {},
                            onCancelTransfer = { cancelledId = it },
                            onCopy = {}, onReconnect = {}, onToggleBtFallback = {},
                            onRequestBluetooth = {}, onRemove = {}, onUpdateDeviceName = { _, _ -> }, onRefresh = {})
                    }
                }
            }
            var revision = 0L
            fun show(progress: TransferProgress, ageMs: Long = 0) {
                scenario.onActivity {
                    screen.value = fixture.copy(
                        transferProgress = progress, transferStatus = progress.message(context),
                        transferRevision = ++revision, transferUpdatedAtMs = SystemClock.elapsedRealtime() - ageMs)
                }
            }
            show(TransferProgress(TransferProgress.State.QUEUED))
            await(context.getString(R.string.transfer_queued))
            val clipboard = await(context.getString(R.string.send_current_clipboard))
            val files = await(context.getString(R.string.send_file))
            val clipboardBounds = Rect().also(clipboard::getBoundsInScreen)
            val fileBounds = Rect().also(files::getBoundsInScreen)
            assertTrue("Send actions must have a visible gap", fileBounds.top > clipboardBounds.bottom)
            capture("transfer-queued.png")

            val fileName = "BinderClip-project-notes-with-a-very-long-filename-for-the-transfer-preview.pdf"
            val sending = TransferProgress(TransferProgress.State.SENDING, percent = 42,
                transferId = "00000000-0000-0000-0000-000000000001",
                fileName = fileName, mimeType = "application/pdf")
            show(sending)
            await(sending.message(context))
            await(fileName)
            assertNull("Queue warning must disappear once sending begins", find(context.getString(R.string.transfer_queued)))
            assertEquals(fileBounds, Rect().also(await(context.getString(R.string.send_file))::getBoundsInScreen))
            capture("transfer-sending.png")

            fun click(label: String) {
                var node = await(label)
                while (!node.isClickable && node.parent != null) node = node.parent
                assertTrue("Button must be accessible: $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            await(context.getString(R.string.cancel_transfer))
            assertNull("No ambiguous X while sending", find(context.getString(R.string.close)))
            click(context.getString(R.string.hide_transfer))
            instrumentation.waitForIdleSync()
            show(sending.copy(percent = 70))
            val hideDeadline = SystemClock.elapsedRealtime() + 1000
            while (find(fileName) != null && SystemClock.elapsedRealtime() < hideDeadline) Thread.sleep(50)
            assertNull("Progress updates must not reopen a hidden transfer", find(fileName))
            assertNull("Hide must leave delivery running", cancelledId)

            val next = sending.copy(transferId = "00000000-0000-0000-0000-000000000002")
            show(next)
            await(next.message(context))
            click(context.getString(R.string.cancel_transfer))
            instrumentation.waitForIdleSync()
            assertEquals(next.transferId, cancelledId)
            show(next.copy(state = TransferProgress.State.CANCELLED))
            await(context.getString(R.string.transfer_cancelled))
            assertNull("Completed cancellation has no cancel button", find(context.getString(R.string.cancel_transfer)))

            val sent = sending.copy(state = TransferProgress.State.SENT,
                transferId = "00000000-0000-0000-0000-000000000003")
            show(sent)
            await(sent.message(context))
            assertNull(find(context.getString(R.string.transfer_queued)))
            assertNull(find(sending.message(context)))
            capture("transfer-sent.png")
            var dismiss = await(context.getString(R.string.close))
            while (!dismiss.isClickable && dismiss.parent != null) dismiss = dismiss.parent
            assertTrue("Dismiss button must be accessible", dismiss.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val dismissDeadline = SystemClock.elapsedRealtime() + 1000
            while (find(sent.message(context)) != null && SystemClock.elapsedRealtime() < dismissDeadline) Thread.sleep(50)
            assertNull("Success can be dismissed", find(sent.message(context)))
            show(sent) // The same label from a later transfer is a new event.
            await(sent.message(context))

            show(sent, ageMs = 5000)
            val expiryDeadline = SystemClock.elapsedRealtime() + 1000
            while (find(sent.message(context)) != null && SystemClock.elapsedRealtime() < expiryDeadline) Thread.sleep(50)
            assertNull("Old success must not reappear on resume", find(sent.message(context)))
        }
    }
}
