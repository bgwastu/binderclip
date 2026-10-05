package net.wastu.binderclip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.flow.MutableStateFlow

/** Debug never acquires the system clipboard service. All callers share its private board. */
class AppClipboard(context: Context) {
    private val system = if (AppProfile.current.isDebug) null else context.getSystemService(ClipboardManager::class.java)
    val primaryClip: ClipData? get() = if (AppProfile.current.isDebug) DebugClipboard.content.value else system?.primaryClip
    fun setPrimaryClip(clip: ClipData) {
        if (AppProfile.current.isDebug) DebugClipboard.set(clip) else system!!.setPrimaryClip(clip)
    }
    fun clearPrimaryClip() {
        if (AppProfile.current.isDebug) DebugClipboard.set(null) else system!!.clearPrimaryClip()
    }
    fun addPrimaryClipChangedListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        if (AppProfile.current.isDebug) DebugClipboard.listeners.add(listener) else system!!.addPrimaryClipChangedListener(listener)
    }
    fun removePrimaryClipChangedListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        if (AppProfile.current.isDebug) DebugClipboard.listeners.remove(listener) else system!!.removePrimaryClipChangedListener(listener)
    }
}

object DebugClipboard {
    val content = MutableStateFlow<ClipData?>(null)
    internal val listeners = CopyOnWriteArraySet<ClipboardManager.OnPrimaryClipChangedListener>()
    internal fun set(clip: ClipData?) {
        content.value = clip
        listeners.forEach { it.onPrimaryClipChanged() }
    }
}
