package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Test

class BtPolicyTest {
    @Test
    fun idleWhenWsConnectedEvenIfFallbackEnabled() {
        val decision = BtPolicy.decide(
            paired = true,
            fallbackEnabled = true,
            wsConnected = true,
            btConnected = false,
            backoffSeconds = 16,
            networkAvailable = true,
            btAdapterOn = true,
            permissionGranted = true,
        )
        assertEquals(BtPolicy.Decision.IDLE, decision)
    }

    @Test
    fun idleWhenBtAlreadyConnected() {
        val decision = BtPolicy.decide(
            paired = true,
            fallbackEnabled = true,
            wsConnected = false,
            btConnected = true,
            backoffSeconds = 16,
            networkAvailable = true,
            btAdapterOn = true,
            permissionGranted = true,
        )
        assertEquals(BtPolicy.Decision.IDLE, decision)
    }

    @Test
    fun requiresPermissionBeforePrompting() {
        val decision = BtPolicy.decide(
            paired = true,
            fallbackEnabled = true,
            wsConnected = false,
            btConnected = false,
            backoffSeconds = 16,
            networkAvailable = false,
            btAdapterOn = true,
            permissionGranted = false,
        )
        assertEquals(BtPolicy.Decision.NEEDS_PERMISSION, decision)
    }

    @Test
    fun promptEnableBtWhenRadioOffAndFallbackActive() {
        val decision = BtPolicy.decide(
            paired = true,
            fallbackEnabled = true,
            wsConnected = false,
            btConnected = false,
            backoffSeconds = 1,
            networkAvailable = false,
            btAdapterOn = false,
            permissionGranted = true,
        )
        assertEquals(BtPolicy.Decision.PROMPT_ENABLE_BT, decision)
    }

    @Test
    fun listenBtWhenNoNetwork() {
        val decision = BtPolicy.decide(
            paired = true,
            fallbackEnabled = true,
            wsConnected = false,
            btConnected = false,
            backoffSeconds = 1,
            networkAvailable = false,
            btAdapterOn = true,
            permissionGranted = true,
        )
        assertEquals(BtPolicy.Decision.LISTEN_BT, decision)
    }

    @Test
    fun listenBtWhenBackoffExhausted() {
        val decision = BtPolicy.decide(
            paired = true,
            fallbackEnabled = true,
            wsConnected = false,
            btConnected = false,
            backoffSeconds = BtPolicy.FALLBACK_THRESHOLD_SECONDS,
            networkAvailable = true,
            btAdapterOn = true,
            permissionGranted = true,
        )
        assertEquals(BtPolicy.Decision.LISTEN_BT, decision)
    }

    @Test
    fun idleWhileBackoffStillSmallAndRecentlyConnected() {
        val now = 1_000_000L
        val decision = BtPolicy.decide(
            paired = true,
            fallbackEnabled = true,
            wsConnected = false,
            btConnected = false,
            backoffSeconds = 1,
            networkAvailable = true,
            btAdapterOn = true,
            permissionGranted = true,
            nowMs = now,
            lastWsConnectedMs = now - 10_000L,
        )
        assertEquals(BtPolicy.Decision.IDLE, decision)
    }

    @Test
    fun listenBtWhenWsStalledEvenIfBackoffSmall() {
        val now = 1_000_000L
        // NSD re-discovery keeps the backoff pinned at 1s; the WS has been dead longer than the
        // stall window, so Bluetooth must engage regardless of the (small) backoff.
        val decision = BtPolicy.decide(
            paired = true,
            fallbackEnabled = true,
            wsConnected = false,
            btConnected = false,
            backoffSeconds = 1,
            networkAvailable = true,
            btAdapterOn = true,
            permissionGranted = true,
            nowMs = now,
            lastWsConnectedMs = now - BtPolicy.FALLBACK_AFTER_STALL_MS,
        )
        assertEquals(BtPolicy.Decision.LISTEN_BT, decision)
    }

    @Test
    fun listenBtOnFreshStartNeverConnectedEvenWithSmallBackoff() {
        val now = 1_000_000L
        // Fresh process: lastConnectedMs is 0 (never connected), so even a small backoff with
        // network up must arm Bluetooth after the stall window elapses.
        val decision = BtPolicy.decide(
            paired = true,
            fallbackEnabled = true,
            wsConnected = false,
            btConnected = false,
            backoffSeconds = 1,
            networkAvailable = true,
            btAdapterOn = true,
            permissionGranted = true,
            nowMs = now,
            lastWsConnectedMs = 0L,
        )
        assertEquals(BtPolicy.Decision.LISTEN_BT, decision)
    }

    @Test
    fun idleWhenUnpairedEvenIfEverythingElseSaysFallback() {
        val decision = BtPolicy.decide(
            paired = false,
            fallbackEnabled = true,
            wsConnected = false,
            btConnected = false,
            backoffSeconds = 16,
            networkAvailable = false,
            btAdapterOn = true,
            permissionGranted = true,
        )
        assertEquals(BtPolicy.Decision.IDLE, decision)
    }
}
