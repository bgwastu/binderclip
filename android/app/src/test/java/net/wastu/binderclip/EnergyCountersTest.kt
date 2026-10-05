package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Test

class EnergyCountersTest {
    @Test fun cumulativeSnapshotsSurviveReportingWindowReset() {
        val before = EnergyCounters.snapshot()["counter.test"] ?: 0L
        EnergyCounters.add("counter.test", 2)
        val lastReport = EnergyCounters::class.java.getDeclaredField("lastReport").apply { isAccessible = true }
        lastReport.setLong(EnergyCounters, System.nanoTime() - 301_000_000_000L)
        EnergyCounters.add("counter.test", 3)
        assertEquals(5L, (EnergyCounters.snapshot()["counter.test"] ?: 0L) - before)
    }
}
