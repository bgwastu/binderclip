package net.wastu.binderclip

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifecycleDeviceTest {
    @Test fun controlledServiceStopsAndRestartsPublishLiveState() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareLifecycle") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun waitFor(label: String, predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (predicate()) return
                Thread.sleep(100)
            }
            assertTrue(label, predicate())
        }
        val intent = Intent(context, BinderClipService::class.java).setAction(BinderClipService.ACTION_START)
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("Initial connection") { AppRuntime.state.value.peer?.connected == true }
            try {
                repeat(3) {
                    assertTrue("Service was not running", context.stopService(intent))
                    waitFor("Stopped service reports disconnected") { AppRuntime.state.value.peer?.connected != true &&
                        !AppRuntime.state.value.bluetoothActive && AppRuntime.state.value.transportType == TransportType.NONE }
                    ContextCompat.startForegroundService(context, intent)
                    waitFor("Service reauthenticated") { AppRuntime.state.value.peer?.connected == true }
                }
            } finally {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
