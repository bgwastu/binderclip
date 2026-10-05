package net.wastu.binderclip

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

sealed interface SharedPayload {
    data class Image(val value: ImagePayload) : SharedPayload
    data class Text(val value: String) : SharedPayload
}

object SharedPayloadCache {
    private val images = LinkedHashMap<String, ImagePayload>()

    @Synchronized fun put(image: ImagePayload): String {
        val id = java.util.UUID.randomUUID().toString()
        images[id] = image
        return id
    }

    @Synchronized fun take(id: String?): ImagePayload? = images.remove(id)
}

/** Native Android share-sheet endpoint; sends to the paired Mac. */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.initialize(this)

        if (!(AppProfile.current.isDebug || RootClipboardBridge.isAvailable())) {
            Toast.makeText(this, getString(R.string.root_required_title), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val payload = when (intent.action) {
            Intent.ACTION_SEND -> {
                val extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                val extraStream = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                val intentType = intent.type?.lowercase()

                when {
                    extraText != null && (intentType == null || intentType == "text/plain" || !intentType.startsWith("image/") || extraStream == null) -> {
                        SharedPayload.Text(extraText)
                    }
                    extraStream != null && (intentType == null || intentType.startsWith("image/") || intentType == "*/*") -> {
                        ImageClipboard.readUri(this, extraStream, intentType)?.let(SharedPayload::Image)
                            ?: extraText?.let(SharedPayload::Text)
                    }
                    intent.clipData != null && intent.clipData!!.itemCount > 0 -> {
                        val item = intent.clipData!!.getItemAt(0)
                        val clipText = item.text?.toString()?.takeIf { it.isNotBlank() }
                        val clipUri = item.uri
                        when {
                            clipText != null -> SharedPayload.Text(clipText)
                            clipUri != null -> ImageClipboard.readUri(this, clipUri, intentType)?.let(SharedPayload::Image)
                            else -> null
                        }
                    }
                    extraText != null -> SharedPayload.Text(extraText)
                    else -> null
                }
            }
            else -> null
        }

        if (payload == null) {
            Log.w("BinderClip", "Share sheet did not provide supported content")
            DiagnosticLog.error("Could not read shared content")
            Toast.makeText(this, getString(R.string.share_error_unsupported), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (DeviceStore(this).groupKey == null) {
            Toast.makeText(this, getString(R.string.tile_not_paired), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (payload is SharedPayload.Image && AppRuntime.state.value.transportType !in setOf(TransportType.LAN, TransportType.MESH)) {
            Toast.makeText(this, getString(R.string.share_error_image_connection), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        sendPayload(payload)
    }

    private fun sendPayload(payload: SharedPayload) {
        Log.i("BinderClip", "Accepted shared ${if (payload is SharedPayload.Image) "image" else "text"} for Mac")
        if (payload is SharedPayload.Text) {
            val queued = runCatching { DeviceStore(this).enqueueShare(payload.value) }.getOrDefault(false)
            if (!queued) {
                Toast.makeText(this, getString(R.string.share_error_queue_full), Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }
        val serviceIntent = Intent(this, BinderClipService::class.java).apply {
            action = BinderClipService.ACTION_SEND_SHARED
            if (payload is SharedPayload.Image) {
                putExtra(BinderClipService.EXTRA_SHARED_IMAGE_ID, SharedPayloadCache.put(payload.value))
            }
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (error: RuntimeException) {
            SharedPayloadCache.take(serviceIntent.getStringExtra(BinderClipService.EXTRA_SHARED_IMAGE_ID))
            DiagnosticLog.warning("Could not start share delivery: ${error.javaClass.simpleName}")
            Toast.makeText(this, getString(R.string.share_error_delivery), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Toast.makeText(
            this,
            if (payload is SharedPayload.Text) getString(R.string.share_queued) else getString(R.string.share_sending),
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }
}
