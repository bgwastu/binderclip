package net.wastu.binderclip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Redacted, persistent diagnostic trail. Clipboard bytes, filenames, keys and invitations never enter it. */
data class DiagnosticEvent(
    val id: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val level: DiagnosticLevel,
    val message: String,
)

enum class DiagnosticLevel { Info, Warning, Error }

object DiagnosticLog {
    private const val MAXIMUM_EVENTS = 2_000
    private const val RETENTION_MS = 7 * 24 * 60 * 60 * 1_000L
    private const val PREFERENCES = "binderclip_diagnostics"
    private const val EVENTS = "events"
    private val lock = Any()
    private val mutableEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events = mutableEvents.asStateFlow()
    @Volatile private var context: Context? = null
    private var nextEventId = 1L

    fun initialize(context: Context) {
        synchronized(lock) {
        if (this.context != null) return
        this.context = context.applicationContext
        val now = System.currentTimeMillis()
        val restored = runCatching {
            JSONArray(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(EVENTS, "[]"))
        }.getOrElse { JSONArray() }
        var legacyId = 0L
        mutableEvents.value = buildList {
            for (index in 0 until restored.length()) {
                val value = restored.optJSONObject(index) ?: continue
                val timestamp = value.optLong("timestamp")
                val level = value.optString("level").let { runCatching { DiagnosticLevel.valueOf(it) }.getOrNull() } ?: continue
                val message = value.optString("message").takeIf { it.isNotBlank() } ?: continue
                if (timestamp < now - RETENTION_MS) continue
                val storedId = value.optLong("id", -1L)
                val id = if (storedId >= 0) storedId else ++legacyId
                add(DiagnosticEvent(id, timestamp, level, message))
            }
        }.takeLast(MAXIMUM_EVENTS)
        nextEventId = (mutableEvents.value.maxOfOrNull { it.id } ?: 0L) + 1L
        }
    }

    fun info(message: String) = append(DiagnosticLevel.Info, message)
    fun warning(message: String) = append(DiagnosticLevel.Warning, message)
    fun error(message: String) = append(DiagnosticLevel.Error, message)

    fun clear() { synchronized(lock) { mutableEvents.value = emptyList(); persistLocked() } }

    private fun append(level: DiagnosticLevel, message: String) {
        synchronized(lock) {
            val event = DiagnosticEvent(id = nextEventId++, level = level, message = message)
            val previous = mutableEvents.value.lastOrNull()
            if (previous?.level == level && previous.message == message && event.timestamp - previous.timestamp < 10_000) return
            mutableEvents.value = (mutableEvents.value + event).filter { it.timestamp >= event.timestamp - RETENTION_MS }.takeLast(MAXIMUM_EVENTS)
            persistLocked()
        }
    }

    private fun persistLocked() {
        val appContext = context ?: return
        val serialized = JSONArray().also { array -> mutableEvents.value.forEach { event ->
            array.put(
                JSONObject()
                    .put("id", event.id)
                    .put("timestamp", event.timestamp)
                    .put("level", event.level.name)
                    .put("message", event.message),
            )
        } }
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(EVENTS, serialized.toString()).apply()
    }
}
