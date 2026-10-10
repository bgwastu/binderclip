package net.wastu.binderclip

import org.json.JSONObject
import java.util.UUID

data class ClipboardStamp(
    val time: Long, val counter: Long, val origin: String, val event: String, val kind: String, val hash: String,
) : Comparable<ClipboardStamp> {
    init {
        require(time in 0..253402300799999L && counter in 0..1_000_000)
        require(origin.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(FileTransferProtocol.validId(event))
        require(kind in setOf("text", "image", "empty") && hash.matches(Regex("[0-9a-f]{64}")))
    }
    val fingerprint get() = "$kind:$hash"
    override fun compareTo(other: ClipboardStamp): Int = compareValuesBy(this, other,
        ClipboardStamp::time, ClipboardStamp::counter, ClipboardStamp::origin, ClipboardStamp::event)
    fun fields(): List<Pair<String, Any>> = listOf("clipTime" to time, "clipCounter" to counter,
        "clipOrigin" to origin, "clipEvent" to event, "clipKind" to kind, "clipHash" to hash)
    fun putInto(json: JSONObject): JSONObject = json.also { fields().forEach { (key, value) -> it.put(key, value) } }
    companion object {
        const val CAPABILITY = "clipboardSync"
        fun parse(json: JSONObject): ClipboardStamp? {
            if (!json.has("clipEvent")) {
                require(fields.none(json::has))
                return null
            }
            fun integer(key: String): Long = json.get(key).let { require(it is Long || it is Int); (it as Number).toLong() }
            return ClipboardStamp(integer("clipTime"), integer("clipCounter"), json.getString("clipOrigin"),
                json.getString("clipEvent"), json.getString("clipKind"), json.getString("clipHash"))
        }
        private val fields = listOf("clipTime", "clipCounter", "clipOrigin", "clipKind", "clipHash")
    }
}

data class ClipboardClockSample(val serverAtReceipt: Long, val receivedUptime: Long)

/** One current version, with a bounded event-id set. Content hashes never suppress later user copies. */
internal class ClipboardOrdering(
    private val localId: String,
    private val wall: () -> Long = System::currentTimeMillis,
    private val uptime: () -> Long,
    private val bootId: String,
    checkpoint: JSONObject? = null,
) {
    var head: ClipboardStamp? = null
        private set
    private var capturedWall: Long? = null
    private var capturedUptime: Long? = null
    private var capturedBoot = bootId
    private var baseline: ClipboardStamp? = null
    private var clock: ClipboardClockSample? = null
    private var published = false
    private val seen = LinkedHashSet<String>()
    val pending get() = capturedWall != null
    init {
        runCatching {
            checkpoint ?: return@runCatching
            head = ClipboardStamp.parse(checkpoint.getJSONObject("head"))
            head?.let { remember(it.event) }
            if (checkpoint.has("capturedWall")) {
                capturedWall = checkpoint.getLong("capturedWall")
                capturedUptime = checkpoint.getLong("capturedUptime")
                capturedBoot = checkpoint.getString("boot")
                baseline = checkpoint.optJSONObject("baseline")?.let(ClipboardStamp::parse)
                published = checkpoint.optBoolean("published")
            }
        }.onFailure { reset() }
    }
    private fun now(): Long = clock?.let { it.serverAtReceipt + (uptime() - it.receivedUptime).coerceAtLeast(0) } ?: wall()
    fun calibrate(sample: ClipboardClockSample) {
        clock = sample
        if (published) return
        val old = head ?: return
        val captured = capturedWall ?: return
        val age = if (capturedBoot == bootId && capturedUptime != null && uptime() >= capturedUptime!!) {
            uptime() - capturedUptime!!
        } else (wall() - captured).coerceAtLeast(0)
        val time = maxOf((now() - age).coerceAtLeast(0), baseline?.time ?: 0)
        val count = if (time == baseline?.time) baseline!!.counter + 1 else if (time == old.time) old.counter else 0
        head = old.copy(time = if (count > 1_000_000) time + 1 else time, counter = if (count > 1_000_000) 0 else count)
    }
    fun observe(kind: String, hash: String, force: Boolean = false, bootstrap: Boolean = false): ClipboardStamp? {
        if (!force && head?.fingerprint == "$kind:$hash") return null
        if (bootstrap && kind == "empty") return null
        if (bootstrap) {
            val time = head?.time ?: 0
            val count = head?.let { it.counter + 1 } ?: 0
            if (published) { capturedWall = null; capturedUptime = null; baseline = null }
            published = false
            return ClipboardStamp(if (count > 1_000_000) time + 1 else time, if (count > 1_000_000) 0 else count,
                localId, UUID.randomUUID().toString(), kind, hash).also { head = it; remember(it.event) }
        }
        if (!pending || published) baseline = head
        published = false
        capturedWall = wall(); capturedUptime = uptime(); capturedBoot = bootId
        val time = maxOf(now(), head?.time ?: 0)
        val count = if (time == head?.time) head!!.counter + 1 else 0
        return ClipboardStamp(if (count > 1_000_000) time + 1 else time, if (count > 1_000_000) 0 else count,
            localId, UUID.randomUUID().toString(), kind, hash).also { head = it; remember(it.event) }
    }
    fun receive(stamp: ClipboardStamp): Boolean {
        val current = head
        if (current?.event == stamp.event) {
            require(current.fingerprint == stamp.fingerprint && current.origin == stamp.origin)
            // The hub's echo confirms a locally submitted event, including after a lost acknowledgement.
            head = stamp
            capturedWall = null; capturedUptime = null; baseline = null
            published = false
            remember(stamp.event)
            return false
        }
        if (stamp.event in seen || (current != null && stamp <= current)) return false
        head = stamp; capturedWall = null; capturedUptime = null; baseline = null
        published = false
        remember(stamp.event)
        return current?.fingerprint != stamp.fingerprint
    }
    fun checkpoint(): JSONObject? = head?.let { stamp ->
        JSONObject().put("head", stamp.putInto(JSONObject())).also { json ->
            capturedWall?.let { json.put("capturedWall", it).put("capturedUptime", capturedUptime).put("boot", capturedBoot).put("published", published) }
            baseline?.let { json.put("baseline", it.putInto(JSONObject())) }
        }
    }
    fun markPublished(event: String) { if (head?.event == event) published = true }
    fun reset() { head = null; capturedWall = null; capturedUptime = null; baseline = null; clock = null; published = false; seen.clear() }
    private fun remember(event: String) { seen.add(event); while (seen.size > 128) seen.remove(seen.first()) }
}

internal fun clipboardClockSample(json: JSONObject, receivedUptime: Long): ClipboardClockSample? = runCatching {
    val probe = json.getLong("clockProbe")
    val received = json.getLong("clockReceive")
    val sent = json.getLong("clockNow")
    val rtt = receivedUptime - probe
    require(probe >= 0 && rtt in 0..20_000 && received in 1..253402300799999L && sent >= received)
    val processing = sent - received
    require(processing <= rtt)
    ClipboardClockSample(sent + (rtt - processing) / 2, receivedUptime)
}.getOrNull()
