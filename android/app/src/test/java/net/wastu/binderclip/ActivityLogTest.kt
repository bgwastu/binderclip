package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLogTest {
    private fun event(kind: ActivityEvent.Kind = ActivityEvent.Kind.Text, outgoing: Boolean = true, at: Long) = ActivityEvent(kind, outgoing, at)

    @Test fun duplicateWithinWindowIsOneEvent() {
        val first = ActivityLog.record(emptyList(), event(at = 1_000))
        assertEquals(1, ActivityLog.record(first, event(at = 2_500)).size)
        assertEquals(2, ActivityLog.record(first, event(at = 3_500)).size)
    }

    @Test fun differentDirectionOrKindIsKept() {
        val first = ActivityLog.record(emptyList(), event(at = 1_000))
        assertEquals(2, ActivityLog.record(first, event(outgoing = false, at = 1_100)).size)
        assertEquals(2, ActivityLog.record(first, event(kind = ActivityEvent.Kind.File, at = 1_100)).size)
    }

    @Test fun keepsNewestEightFirst() {
        var list = emptyList<ActivityEvent>()
        for (i in 0 until 12) list = ActivityLog.record(list, event(at = i * 10_000L))
        assertEquals(8, list.size)
        assertEquals(110_000L, list.first().atMs)
    }
}
