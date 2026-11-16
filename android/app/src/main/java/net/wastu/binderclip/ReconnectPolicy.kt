package net.wastu.binderclip

enum class ConnectionPhase {
    NotPaired,
    Connecting,
    Connected,
    Reconnecting,
}

object ConnectionStatus {
    fun phase(paired: Boolean, connected: Boolean, connecting: Boolean): ConnectionPhase = when {
        !paired -> ConnectionPhase.NotPaired
        connected -> ConnectionPhase.Connected
        connecting -> ConnectionPhase.Connecting
        else -> ConnectionPhase.Reconnecting
    }

    /** String resource for a phase; a connected peer shows its own name instead. */
    fun labelRes(phase: ConnectionPhase): Int = when (phase) {
        ConnectionPhase.NotPaired -> R.string.tile_not_paired
        ConnectionPhase.Connecting -> R.string.status_connecting
        ConnectionPhase.Connected -> R.string.status_connected
        ConnectionPhase.Reconnecting -> R.string.status_reconnecting
    }
}

/** What the WebSocket client reports; the service turns these into localized text. */
sealed interface ClientStatus {
    data class Pairing(val macName: String) : ClientStatus
    data object Connecting : ClientStatus
    data object Connected : ClientStatus
    data object Reconnecting : ClientStatus
    data object NotPaired : ClientStatus
}

sealed interface ClientFailure {
    data object NoAddress : ClientFailure
    data object SecureFailed : ClientFailure
    data object KeyRejected : ClientFailure
    data class Unreachable(val hosts: List<String>) : ClientFailure
}

/** Single owner for whether a connect race may start and how long to wait after failure. */
class ReconnectPolicy(
    private val maxDelaySeconds: Long = 16L,
) {
    private var failures = 0
    var delaySeconds: Long = 1L
        private set

    fun shouldStartConnect(force: Boolean, connected: Boolean, connecting: Boolean): Boolean {
        if (force) return true
        return !connected && !connecting
    }

    fun resetBackoff() {
        delaySeconds = 1L
        failures = 0
    }

    fun shouldAnnounceUnreachable(): Boolean = delaySeconds >= 8L

    fun nextBackoffSeconds(interactive: Boolean = true): Long {
        failures++
        val current = delaySeconds
        delaySeconds = (delaySeconds * 2).coerceAtMost(if (failures > 6) { if (interactive) 60L else 300L } else maxDelaySeconds)
        return current
    }
}
