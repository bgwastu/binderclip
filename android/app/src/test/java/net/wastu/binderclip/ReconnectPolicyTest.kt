package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun idleDisconnectedStartsConnect() {
        val policy = ReconnectPolicy()
        assertTrue(policy.shouldStartConnect(force = false, connected = false, connecting = false))
    }

    @Test
    fun connectedIgnoresBackgroundConnect() {
        val policy = ReconnectPolicy()
        assertFalse(policy.shouldStartConnect(force = false, connected = true, connecting = false))
    }

    @Test
    fun connectingIgnoresBackgroundConnect() {
        val policy = ReconnectPolicy()
        assertFalse(policy.shouldStartConnect(force = false, connected = false, connecting = true))
    }

    @Test
    fun userForceReconnectsEvenWhenConnected() {
        val policy = ReconnectPolicy()
        assertTrue(policy.shouldStartConnect(force = true, connected = true, connecting = false))
        assertTrue(policy.shouldStartConnect(force = true, connected = false, connecting = true))
    }

    @Test
    fun backoffDoublesAndCapsAt16() {
        val policy = ReconnectPolicy()
        assertEquals(1L, policy.nextBackoffSeconds())
        assertEquals(2L, policy.nextBackoffSeconds())
        assertEquals(4L, policy.nextBackoffSeconds())
        assertEquals(8L, policy.nextBackoffSeconds())
        assertEquals(16L, policy.nextBackoffSeconds())
        assertEquals(16L, policy.nextBackoffSeconds())
        policy.resetBackoff()
        assertEquals(1L, policy.nextBackoffSeconds())
    }

    @Test
    fun unreachableAnnouncementWaitsForSettledBackoff() {
        val policy = ReconnectPolicy()
        assertFalse(policy.shouldAnnounceUnreachable())
        repeat(2) { policy.nextBackoffSeconds() }
        assertFalse(policy.shouldAnnounceUnreachable())
        policy.nextBackoffSeconds()
        assertTrue(policy.shouldAnnounceUnreachable())
    }

    @Test
    fun phaseDerivation() {
        assertEquals(ConnectionPhase.NotPaired, ConnectionStatus.phase(false, false, false))
        assertEquals(ConnectionPhase.Connected, ConnectionStatus.phase(true, true, false))
        assertEquals(ConnectionPhase.Connected, ConnectionStatus.phase(true, true, true))
        assertEquals(ConnectionPhase.Connecting, ConnectionStatus.phase(true, false, true))
        assertEquals(ConnectionPhase.Reconnecting, ConnectionStatus.phase(true, false, false))
        assertEquals("Reconnecting…", ConnectionStatus.label(ConnectionPhase.Reconnecting, "Studio"))
        assertEquals("Studio", ConnectionStatus.label(ConnectionPhase.Connected, "Studio"))
        assertEquals("Connecting…", ConnectionStatus.label(ConnectionPhase.Connecting, "Studio"))
    }
    @Test
    fun prolongedAbsenceUsesLongerBackgroundCap() {
        val background = ReconnectPolicy()
        repeat(20) { background.nextBackoffSeconds(interactive = false) }
        assertEquals(300L, background.nextBackoffSeconds(interactive = false))
        background.resetBackoff()
        assertEquals(1L, background.nextBackoffSeconds(interactive = false))
        val awake = ReconnectPolicy()
        repeat(20) { awake.nextBackoffSeconds(interactive = true) }
        assertEquals(60L, awake.nextBackoffSeconds(interactive = true))
    }

}
