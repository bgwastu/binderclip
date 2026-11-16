package net.wastu.binderclip

/** The pairing a swap started from. Restored verbatim if the new Mac never authenticates. */
data class PairingSnapshot(
    val groupKey: ByteArray?,
    val peer: RememberedPeer?,
    val peerCandidates: List<String>,
    val lastGoodEndpoint: String?,
)

/**
 * Staged pairing: the Mac you already have stays restorable until the new one authenticates.
 *
 * Pairing has to write the new key and peer before the new Mac has proven it is reachable, so a
 * stale QR, a typo or a Mac that never answers would otherwise leave the phone paired with nothing.
 * The snapshot of the old pairing lives here until the new device id authenticates (commit) or the
 * attempt fails or times out (rollback).
 */
class PairingSwap(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    enum class Result {
        /** Not the machine we were pairing with, or nothing is pending. */
        Ignored,

        /** The same Mac came back with a new key: nothing else about the pairing changed. */
        CommittedSameMac,

        /** A different Mac took over, so the old group's roster and clipboard history are stale. */
        CommittedNewMac,
    }

    companion object {
        /** Long enough to try every endpoint and the Bluetooth fallback, short enough not to strand. */
        const val DEFAULT_TIMEOUT_MS = 45_000L
    }

    private var snapshot: PairingSnapshot? = null
    private var targetDeviceId: String? = null
    private var targetKeepsSameMac = false
    private var startedAtMs = 0L

    val isPending: Boolean get() = snapshot != null

    fun begin(previous: PairingSnapshot, deviceId: String, nowMs: Long) {
        // A second pairing attempt while one is pending must still roll back to the pre-swap pairing.
        if (snapshot == null) snapshot = previous
        targetDeviceId = deviceId
        targetKeepsSameMac = previous.peer?.deviceId != null && previous.peer.deviceId == deviceId
        startedAtMs = nowMs
    }

    fun authenticated(deviceId: String): Result {
        if (snapshot == null || deviceId != targetDeviceId) return Result.Ignored
        val result = if (targetKeepsSameMac) Result.CommittedSameMac else Result.CommittedNewMac
        clear()
        return result
    }

    /** Gives up on the swap and returns the pairing to restore, or null when nothing was staged. */
    fun abandon(): PairingSnapshot? {
        val previous = snapshot ?: return null
        clear()
        return previous
    }

    fun expired(nowMs: Long): Boolean = snapshot != null && nowMs - startedAtMs >= timeoutMs

    private fun clear() {
        snapshot = null
        targetDeviceId = null
        targetKeepsSameMac = false
        startedAtMs = 0L
    }
}
