package net.wastu.binderclip

import org.junit.Assert.assertFalse
import org.junit.Test

class RootGateTest {
    @Test
    fun rootUnavailableOnJvm() {
        assertFalse(RootClipboardBridge.isAvailable())
    }
}
