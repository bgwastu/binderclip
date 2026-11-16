package net.wastu.binderclip

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSwapTest {
    private val oldMac = RememberedPeer("Kitchen Mac", "192.168.1.5", 39421, "mac-old")
    private val newMac = RememberedPeer("Office Mac", "100.96.0.2", 39421, "mac-new")

    private fun snapshot(peer: RememberedPeer?, key: String = "old-key") = PairingSnapshot(
        groupKey = key.toByteArray(),
        peer = peer,
        peerCandidates = listOf("192.168.1.5:39421", "100.96.0.2:39421"),
        lastGoodEndpoint = "192.168.1.5:39421",
    )

    @Test
    fun nothingPendingByDefault() {
        val swap = PairingSwap()
        assertFalse(swap.isPending)
        assertNull(swap.abandon())
        assertEquals(PairingSwap.Result.Ignored, swap.authenticated("mac-new"))
    }

    @Test
    fun authenticatingTheNewMacCommits() {
        val swap = PairingSwap()
        swap.begin(snapshot(oldMac), "mac-new", nowMs = 0)
        assertTrue(swap.isPending)
        assertEquals(PairingSwap.Result.CommittedNewMac, swap.authenticated("mac-new"))
        assertFalse(swap.isPending)
        assertNull(swap.abandon())
    }

    @Test
    fun authenticatingTheOldMacDoesNotCommit() {
        val swap = PairingSwap()
        swap.begin(snapshot(oldMac), "mac-new", nowMs = 0)
        assertEquals(PairingSwap.Result.Ignored, swap.authenticated("mac-old"))
        assertTrue(swap.isPending)
    }

    @Test
    fun repairingTheSameMacKeepsItsRoster() {
        val swap = PairingSwap()
        swap.begin(snapshot(oldMac, key = "old-key"), "mac-old", nowMs = 0)
        assertEquals(PairingSwap.Result.CommittedSameMac, swap.authenticated("mac-old"))
    }

    @Test
    fun firstEverPairingCommittedIsANewMac() {
        val swap = PairingSwap()
        swap.begin(PairingSnapshot(null, null, emptyList(), null), "mac-new", nowMs = 0)
        assertEquals(PairingSwap.Result.CommittedNewMac, swap.authenticated("mac-new"))
    }

    @Test
    fun abandonReturnsThePairingToRestoreOnce() {
        val swap = PairingSwap()
        val previous = snapshot(oldMac)
        swap.begin(previous, "mac-new", nowMs = 0)
        val restored = swap.abandon()
        assertArrayEquals("old-key".toByteArray(), restored?.groupKey)
        assertEquals(oldMac, restored?.peer)
        assertEquals(previous.peerCandidates, restored?.peerCandidates)
        assertEquals("192.168.1.5:39421", restored?.lastGoodEndpoint)
        assertNull(swap.abandon())
    }

    @Test
    fun aSecondAttemptStillRollsBackToThePreSwapPairing() {
        val swap = PairingSwap()
        swap.begin(snapshot(oldMac), "mac-new", nowMs = 0)
        // The phone is now pointed at mac-new; pairing again from there must not adopt mac-new.
        swap.begin(snapshot(newMac, key = "new-key"), "mac-third", nowMs = 1_000)
        assertEquals(oldMac, swap.abandon()?.peer)
    }

    @Test
    fun expiresOnlyAfterTheTimeout() {
        val swap = PairingSwap(timeoutMs = 45_000)
        swap.begin(snapshot(oldMac), "mac-new", nowMs = 1_000)
        assertFalse(swap.expired(nowMs = 45_999))
        assertTrue(swap.expired(nowMs = 46_000))
        swap.authenticated("mac-new")
        assertFalse(swap.expired(nowMs = 10_000_000))
    }
}
