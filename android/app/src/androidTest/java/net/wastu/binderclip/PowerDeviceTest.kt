package net.wastu.binderclip

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical test; the coordinator turns the screen off before instrumentation. */
@RunWith(AndroidJUnit4::class)
class PowerDeviceTest {
    @Test fun serviceStartedWithScreenOffStaysConnectedWithoutClipboardPolling() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareScreenOff") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val power = context.getSystemService(PowerManager::class.java)
        assertFalse("Turn the phone screen off before this test", power.isInteractive)
        ContextCompat.startForegroundService(context,
            Intent(context, BinderClipService::class.java).setAction(BinderClipService.ACTION_START))
        val bluetooth = InstrumentationRegistry.getArguments().getString("expectedTransport") == "BLUETOOTH"
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while ((AppRuntime.state.value.peer?.connected != true || (bluetooth && AppRuntime.state.value.transportType != TransportType.BLUETOOTH)) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Screen-off startup did not authenticate", AppRuntime.state.value.peer?.connected == true)
        assertTrue(AppRuntime.state.value.automaticClipboardEnabled)
        if (bluetooth) assertEquals(TransportType.BLUETOOTH, AppRuntime.state.value.transportType)
        fun counts() = EnergyCounters.snapshot()
        val before = counts()
        val end = SystemClock.elapsedRealtime() + 65_000
        while (SystemClock.elapsedRealtime() < end) {
            assertFalse("Screen was awakened during the test", power.isInteractive)
            assertTrue("Sleeping session disconnected", AppRuntime.state.value.peer?.connected == true)
            Thread.sleep(250)
        }
        val after = counts()
        for (label in listOf("clipboard.poll", "clipboard.read")) {
            assertEquals("Screen-off work: $label", before[label] ?: 0L, after[label] ?: 0L)
        }
    }
}
