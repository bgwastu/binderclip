package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLivenessTest {
    @Test
    fun meshRemoteBindsLocalMeshAddress() {
        val locals = listOf("192.168.60.249", "100.96.0.2")
        assertEquals("100.96.0.2", SessionLiveness.boundLocalAddress("100.96.0.31", locals))
    }

    @Test
    fun lanRemoteBindsLanAddress() {
        val locals = listOf("192.168.60.249", "100.96.0.2")
        assertEquals("192.168.60.249", SessionLiveness.boundLocalAddress("192.168.50.199", locals))
    }

    @Test
    fun evictWhenBoundMeshAddressDisappears() {
        assertTrue(SessionLiveness.shouldEvict("100.96.0.2", listOf("192.168.60.249")))
        assertFalse(SessionLiveness.shouldEvict("192.168.60.249", listOf("192.168.60.249")))
        assertFalse(SessionLiveness.shouldEvict("192.168.60.249", listOf("192.168.60.249", "100.96.0.2")))
    }

    @Test
    fun heartbeatMissIsDead() {
        val bound = "100.96.0.2"
        val locals = listOf("192.168.60.249", "100.96.0.2")
        val now = 1_000_000L
        assertTrue(SessionLiveness.isAlive(bound, locals, now, now))
        assertFalse(SessionLiveness.isAlive(bound, locals, now - 6_000L, now))
        assertFalse(SessionLiveness.isAlive(bound, listOf("192.168.60.249"), now, now))
        assertFalse(SessionLiveness.isAlive(bound, locals, null, now))
    }

    @Test
    fun sleepBudgetKeepsSessionThroughDozeGap() {
        val bound = "100.96.0.2"
        val locals = listOf("192.168.60.249", "100.96.0.2")
        val now = 1_000_000L
        assertTrue(SessionLiveness.isAlive(bound, locals, now - 30_000L, now, SyncProtocol.HEARTBEAT_SLEEP_BUDGET_MS))
        assertFalse(SessionLiveness.isAlive(bound, locals, now - SyncProtocol.HEARTBEAT_SLEEP_BUDGET_MS - 1, now, SyncProtocol.HEARTBEAT_SLEEP_BUDGET_MS))
    }
}
