package net.wastu.binderclip

import kotlinx.coroutines.flow.MutableStateFlow

/** What happened, never what was copied; in memory only, so it is gone when the process is. */
data class ActivityEvent(val kind: Kind, val outgoing: Boolean, val atMs: Long) {
    enum class Kind { Text, Link, Image, File }
}

object ActivityLog {
    private const val LIMIT = 8
    private const val DUPLICATE_WINDOW_MS = 2_000L
    val events = MutableStateFlow<List<ActivityEvent>>(emptyList())

    fun record(kind: ActivityEvent.Kind, outgoing: Boolean, now: Long = System.currentTimeMillis()) {
        events.value = record(events.value, ActivityEvent(kind, outgoing, now))
    }

    /** The same event twice within the window is one event: a transfer reports both progress and completion. */
    internal fun record(current: List<ActivityEvent>, event: ActivityEvent): List<ActivityEvent> {
        val latest = current.firstOrNull()
        if (latest != null && latest.kind == event.kind && latest.outgoing == event.outgoing &&
            event.atMs - latest.atMs < DUPLICATE_WINDOW_MS) return current
        return (listOf(event) + current).take(LIMIT)
    }

    fun clear() { events.value = emptyList() }
}
