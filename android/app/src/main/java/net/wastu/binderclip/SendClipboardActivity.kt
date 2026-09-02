package net.wastu.binderclip

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Lightweight, invisible trampoline activity launched by the Quick Settings Tile
 * to gain brief window focus so that the system clipboard (text or image) can be
 * read safely without root, then immediately dispatched to the paired Mac.
 */
class SendClipboardActivity : Activity() {
    private var dispatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("BinderClipSend", "SendClipboardActivity onCreate")
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        android.util.Log.i("BinderClipSend", "SendClipboardActivity onWindowFocusChanged: hasFocus=$hasFocus, dispatched=$dispatched")
        if (hasFocus && !dispatched) {
            dispatched = true
            captureAndDispatch()
        }
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.i("BinderClipSend", "SendClipboardActivity onResume: hasWindowFocus=${hasWindowFocus()}, dispatched=$dispatched")
        if (hasWindowFocus() && !dispatched) {
            dispatched = true
            captureAndDispatch()
        }
    }

    private fun captureAndDispatch() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        android.util.Log.i("BinderClipSend", "SendClipboardActivity captureAndDispatch hasPrimaryClip=${clipboard.hasPrimaryClip()}")
        when (val content = ClipboardClassifier.read(this, clipboard)) {
            is LocalClipboardContent.Text -> {
                android.util.Log.i("BinderClipSend", "SendClipboardActivity read text: ${content.value}")
                SharedPayloadCache.value = SharedPayload.Text(content.value)
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, BinderClipService::class.java).setAction(BinderClipService.ACTION_SEND_SHARED),
                )
                Toast.makeText(this, "Sent to Mac", Toast.LENGTH_SHORT).show()
            }
            is LocalClipboardContent.Image -> {
                android.util.Log.i("BinderClipSend", "SendClipboardActivity read image: ${content.value.mimeType} (${content.value.data.size} bytes)")
                SharedPayloadCache.value = SharedPayload.Image(content.value)
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, BinderClipService::class.java).setAction(BinderClipService.ACTION_SEND_SHARED),
                )
                Toast.makeText(this, "Sent image to Mac", Toast.LENGTH_SHORT).show()
            }
            is LocalClipboardContent.Unsupported -> {
                android.util.Log.w("BinderClipSend", "SendClipboardActivity clip unsupported or empty")
                Toast.makeText(this, "Clipboard is empty or unsupported", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
