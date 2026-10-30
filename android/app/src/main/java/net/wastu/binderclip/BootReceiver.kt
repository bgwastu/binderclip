package net.wastu.binderclip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the sync service after boot, OEM quick-boot, or an app update when the phone is paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DiagnosticLog.initialize(context)
        val store = DeviceStore(context)
        val paired = store.groupKey != null
        if (!store.isAutostartEnabled()) return
        if (!ServiceAutostart.shouldStart(intent?.action, paired)) return
        DiagnosticLog.info("Starting BinderClip after ${intent?.action}")
        BinderClipService.startFromBackground(context)
    }
}
