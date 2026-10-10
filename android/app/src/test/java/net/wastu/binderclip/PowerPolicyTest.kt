package net.wastu.binderclip

import org.junit.Assert.*
import org.junit.Test

class PowerPolicyTest {
    @Test fun fragmentedTransferDoesNotStarveReverseHeartbeat() {
        assertFalse(PowerPolicy.shouldProbeHeartbeat(1_999, 0, true))
        assertTrue(PowerPolicy.shouldProbeHeartbeat(2_000, 0, true))
        assertFalse(PowerPolicy.shouldProbeHeartbeat(14_999, 0, false))
        assertTrue(PowerPolicy.shouldProbeHeartbeat(15_000, 0, false))
        assertFalse(PowerPolicy.shouldProbeHeartbeat(15_000, 14_999, true))
    }
    @Test fun newNetworkProbesWebSocketWithoutDroppingWorkingBluetooth() {
        assertTrue(PowerPolicy.shouldProbePreferredTransport(false, true, true, true))
        assertFalse(PowerPolicy.shouldProbePreferredTransport(false, true, true, false))
        assertFalse(PowerPolicy.shouldProbePreferredTransport(false, true, false, true))
        assertFalse(PowerPolicy.shouldProbePreferredTransport(true, true, true, true))
        assertFalse(PowerPolicy.shouldProbePreferredTransport(false, false, true, true))
    }
    @Test fun startupWithScreenOffDoesNotPoll() {
        assertFalse(PowerPolicy.shouldPoll(true, false, true))
        assertFalse(PowerPolicy.shouldPoll(true, true, false))
        assertTrue(PowerPolicy.shouldPoll(true, true, true))
    }
    @Test fun sleepingHeartbeatFitsDeadline() {
        assertTrue(PowerPolicy.heartbeatMs(false) > PowerPolicy.heartbeatMs(true))
        assertTrue(PowerPolicy.budgetMs(false) >= 3 * PowerPolicy.heartbeatMs(false))
    }
    @Test fun retryIsBoundedAndUserResetRecoversImmediately() {
        val policy = ScanRetryPolicy()
        var now = 0L
        repeat(15) {
            policy.failed(now)
            assertFalse(policy.canAttempt(now))
            assertTrue(policy.nextAttemptMs - now <= 300_000L)
            now = policy.nextAttemptMs
            assertTrue(policy.canAttempt(now))
        }
        policy.reset()
        assertTrue(policy.canAttempt(0))
    }
    @Test fun unchangedImageIsReadOnceAndCallbackCanInvalidateSameUri() {
        val cache = ClipboardReadCache<String>()
        var reads = 0
        repeat(20) { assertEquals("image", cache.read("same-uri") { reads++; "image" }) }
        assertEquals(1, reads)
        cache.invalidate()
        cache.read("same-uri") { reads++; "changed-image" }
        assertEquals(2, reads)
    }
    @Test fun clipboardInvalidationDuringReadDiscardsStalePayload() {
        val cache = ClipboardReadCache<String>()
        assertNull(cache.read("uri") { cache.invalidate(); "stale" })
        assertEquals("fresh", cache.read("uri") { "fresh" })
    }

}
