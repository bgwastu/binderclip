package net.wastu.binderclip

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardSyncDeviceTest {
    @Test fun offlineCopiesConvergeAndIdenticalRecopiesRemainNewEvents() {
        assumeTrue(AppProfile.current.isDebug)
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareClipboardSync") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = AppClipboard(context)
        val status = File(context.filesDir, "device-ordering-stage.txt")
        val ack = File(context.filesDir, "device-ordering-ack.txt")
        val suffix = UUID.randomUUID().toString().take(8)
        val values = listOf("baseline", "phone earlier", "Mac newer", "Mac earlier", "phone newer", "phone changed", "Mac changed")
            .map { "BinderClip ordering $it/$suffix" }
        File(context.filesDir, "device-ordering-values.json").writeText(org.json.JSONArray(values).toString())
        fun stage(value: String) { status.writeText(value) }
        fun waitFor(label: String, predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (predicate()) return
                Thread.sleep(100)
            }
            assertTrue("Timed out: $label", predicate())
        }
        fun acknowledged(value: String) = waitFor(value) { ack.exists() && ack.readText() == value }
        fun connected() = AppRuntime.state.value.peer?.connected == true
        fun text() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        fun copy(value: String) {
            clipboard.setPrimaryClip(ClipData.newPlainText("BinderClip ordering test", value))
            Thread.sleep(1_500)
        }
        fun reconnect() {
            context.startService(Intent(context, BinderClipService::class.java).setAction(BinderClipService.ACTION_SEARCH_RECONNECT))
            waitFor("authenticated reconnection", ::connected)
        }
        fun disconnect(round: Int) {
            stage("block-$round")
            acknowledged("blocked-$round")
            waitFor("isolated debug disconnection") { !connected() }
        }
        fun converged(round: Int, expected: String) {
            waitFor("phone clipboard converged") { text() == expected }
            stage("verify-$round")
            acknowledged("verified-$round")
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("initial authenticated session", ::connected)
            Thread.sleep(2_000)
            val previous = clipboard.primaryClip
            val previousImage = ImageClipboard.read(context, clipboard)
            try {
                stage("baseline")
                acknowledged("baseline")
                waitFor("baseline clipboard") { text() == values[0] }

                disconnect(1)
                copy(values[1])
                stage("mac-newer")
                acknowledged("unblocked-1")
                reconnect()
                converged(1, values[2])

                disconnect(2)
                stage("mac-earlier")
                acknowledged("mac-earlier")
                copy(values[4])
                stage("unblock-2")
                acknowledged("unblocked-2")
                reconnect()
                converged(2, values[4])

                disconnect(3)
                copy(values[5])
                stage("mac-recopy")
                acknowledged("unblocked-3")
                reconnect()
                converged(3, values[4])

                disconnect(4)
                stage("mac-changed")
                acknowledged("mac-changed")
                // The phone still has the same bytes received in round 3. This is a new user copy.
                copy(values[4])
                stage("unblock-4")
                acknowledged("unblocked-4")
                reconnect()
                converged(4, values[4])
                stage("passed")
            } catch (error: Throwable) {
                stage("failed")
                throw error
            } finally {
                if (previousImage != null) ImageClipboard.write(context, clipboard, previousImage)
                else if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
            }
        }
    }
}
