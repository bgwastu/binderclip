package net.wastu.binderclip

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.initialize(this)
        val progress = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            addView(ProgressBar(this@ShareReceiverActivity))
            addView(TextView(this@ShareReceiverActivity).apply { setText(R.string.share_sending) })
        }
        setContentView(progress)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { importShare() }.getOrDefault(R.string.share_error_unsupported) }
            if (result != null) {
                Toast.makeText(this@ShareReceiverActivity, result, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            try {
                ContextCompat.startForegroundService(this@ShareReceiverActivity,
                    Intent(this@ShareReceiverActivity, BinderClipService::class.java).setAction(BinderClipService.ACTION_SEND_SHARED))
                Toast.makeText(this@ShareReceiverActivity, R.string.share_queued, Toast.LENGTH_SHORT).show()
            } catch (_: RuntimeException) {
                DiagnosticLog.warning("Could not start share delivery")
                Toast.makeText(this@ShareReceiverActivity, R.string.share_error_delivery, Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun importShare(): Int? {
        if (!(AppProfile.current.isDebug || RootClipboardBridge.isAvailable())) return R.string.root_required_title
        val deviceStore = DeviceStore(this)
        val peerId = deviceStore.peer?.deviceId?.takeIf { it.isNotBlank() }
        if (deviceStore.groupKey == null || peerId == null) return R.string.tile_not_paired
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return R.string.share_error_unsupported
        val explicitStreams: List<Uri> = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else @Suppress("DEPRECATION") (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty())
        } else {
            val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            listOfNotNull(uri)
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.takeIf { it.isNotBlank() }
        // Explicit streams are documents, even when their MIME type is text/plain.
        // A browser thumbnail in ClipData must not take precedence over a URL share.
        if (explicitStreams.isEmpty() && text != null) {
            return if (runCatching { deviceStore.enqueueShare(text) }.getOrDefault(false)) null else R.string.share_error_queue_full
        }
        val streams = explicitStreams.ifEmpty {
            intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        }.distinct()
        if (streams.isEmpty()) return R.string.share_error_unsupported
        if (streams.size > 8) return R.string.file_picker_help
        val transfers = FileTransferStore.forContext(this)
        return try {
            val sources = streams.map { uri ->
                require(uri.scheme == "content" && uri.authority != "$packageName.images")
                val mime = (contentResolver.getType(uri) ?: intent.type)?.substringBefore(';')?.trim()?.lowercase()
                    ?.takeIf { it.matches(Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")) && it.length <= 127 } ?: "application/octet-stream"
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "file"
                val kind = if (!intent.getBooleanExtra("share_as_file", false) && mime in ImagePayload.ALLOWED_MIME_TYPES) "image" else "file"
                SharedFileSource(name, mime, kind) { contentResolver.openInputStream(uri) ?: error("Unreadable shared content") }
            }
            transfers.stageBatch(peerId, sources) {
                val current = DeviceStore(this)
                check(current.groupKey != null && current.peer?.deviceId == peerId)
            }
            null
        } catch (_: Exception) {
            DiagnosticLog.warning("Shared file import failed")
            if (DeviceStore(this).groupKey == null || DeviceStore(this).peer?.deviceId != peerId) R.string.tile_not_paired
            else R.string.share_error_file
        }
    }
}
