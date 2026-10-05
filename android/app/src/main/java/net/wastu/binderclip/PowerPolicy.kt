package net.wastu.binderclip

/** All durations use a monotonic clock. User actions bypass unattended retry delays. */
object PowerPolicy {
    const val IDLE_HEARTBEAT_MS = 15_000L
    const val IDLE_BUDGET_MS = 60_000L
    const val SCAN_WINDOW_MS = 8_000L
    const val POLL_MS = 3_000L
    const val REMOTE_SLEEP_MS = 60_000L
    fun heartbeatMs(interactive: Boolean) = if (interactive) SyncProtocol.HEARTBEAT_INTERVAL_MS else IDLE_HEARTBEAT_MS
    fun budgetMs(interactive: Boolean) = if (interactive) SyncProtocol.HEARTBEAT_BUDGET_MS else IDLE_BUDGET_MS
    fun shouldPoll(enabled: Boolean, interactive: Boolean, paired: Boolean, connected: Boolean) =
        enabled && interactive && paired && connected
    fun shouldProbePreferredTransport(wsConnected: Boolean, btConnected: Boolean, networkAvailable: Boolean, becameAvailable: Boolean) =
        !wsConnected && btConnected && networkAvailable && becameAvailable
    fun shouldProbeHeartbeat(nowMs: Long, lastAuthenticatedFrameMs: Long, interactive: Boolean) =
        nowMs - lastAuthenticatedFrameMs >= heartbeatMs(interactive)
}

class ScanRetryPolicy {
    private var delayMs = 15_000L
    @Volatile var nextAttemptMs = 0L
        private set
    fun canAttempt(nowMs: Long) = nowMs >= nextAttemptMs
    @Synchronized fun failed(nowMs: Long, jitterMs: Long = 0L) {
        nextAttemptMs = nowMs + (delayMs + jitterMs.coerceAtLeast(0)).coerceAtMost(300_000L)
        delayMs = (delayMs * 2).coerceAtMost(300_000L)
    }
    @Synchronized fun reset() { delayMs = 15_000L; nextAttemptMs = 0L }
}

/** Metadata changes and explicit clipboard callbacks invalidate byte-level caches. */
class ClipboardReadCache<T> {
    private var key: String? = null
    private var value: T? = null
    private var generation = 0L
    @Synchronized fun invalidate() { generation++; key = null; value = null }
    @Synchronized fun generation() = generation
    fun read(fingerprint: String, loader: () -> T?): T? {
        val ticket: Long
        synchronized(this) {
            if (key == fingerprint) return value
            ticket = generation
        }
        val loaded = loader()
        return synchronized(this) {
            if (ticket != generation) return null
            key = fingerprint
            value = loaded
            value
        }
    }
}
