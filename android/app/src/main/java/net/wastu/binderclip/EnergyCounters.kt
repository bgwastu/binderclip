package net.wastu.binderclip

/** Aggregate fixed labels while work occurs; never log clipboard bytes or wake to report. */
object EnergyCounters {
    private val lock = Any()
    private val values = mutableMapOf<String, Long>()
    private val totals = mutableMapOf<String, Long>()
    fun snapshot(): Map<String, Long> = synchronized(lock) { totals.toMap() }
    private var lastReport = System.nanoTime()
    fun add(name: String, amount: Long = 1) {
        val report = synchronized(lock) {
            values[name] = (values[name] ?: 0) + amount
            totals[name] = (totals[name] ?: 0) + amount
            val now = System.nanoTime()
            if (now - lastReport >= 300_000_000_000L) {
                val summary = values.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }
                values.clear(); lastReport = now
                summary
            } else null
        }
        if (report != null) DiagnosticLog.info("Activity counters: $report")
    }
}
