package net.wastu.binderclip

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : ComponentActivity() {
    private var message by mutableIntStateOf(R.string.share_preparing)
    private var importing by mutableStateOf(true)
    private var failed by mutableStateOf(false)
    private var fileShare = false
    private var deliveryRevision by mutableLongStateOf(0)
    private var stagedTransferIds = emptySet<String>()
    private var cancelling by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.32f)
        DiagnosticLog.initialize(this)
        setContent {
            BinderClipTheme {
                val runtime by AppRuntime.state.collectAsState()
                val progress = runtime.transferProgress?.takeIf {
                    !importing && !failed && if (fileShare) it.transferId in stagedTransferIds
                    else runtime.transferRevision > deliveryRevision && it.kind in setOf("text", "link") && it.isSuccess
                }
                AlertDialog(
                    onDismissRequest = { if (!importing) finish() },
                    icon = {
                        if (progress?.fileName != null) Icon(fileTypeIcon(progress.mimeType, progress.fileName),
                            contentDescription = null, modifier = Modifier.size(32.dp))
                        else if (importing || progress?.isBusy == true) CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        else Icon(if (failed || progress?.isError == true) Icons.Outlined.ErrorOutline
                            else if (progress?.isSuccess == true) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule,
                            contentDescription = null)
                    },
                    title = { Text(if (importing) stringResource(message) else AppProfile.current.displayName) },
                    text = {
                        if (!importing) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            progress?.fileName?.let { name ->
                                Text(name, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(progress?.message(this@ShareReceiverActivity) ?: stringResource(message))
                        }
                        else if (importing && intent.hasExtra(Intent.EXTRA_STREAM)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.file_picker_help), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    },
                    confirmButton = {
                        if (!importing) TextButton(onClick = { finish() }) {
                            Text(stringResource(if (progress?.canCancel == true) R.string.hide_transfer else R.string.close))
                        }
                    },
                    dismissButton = {
                        if (progress?.canCancel == true) TextButton(enabled = !cancelling, onClick = {
                            cancelling = true
                            ContextCompat.startForegroundService(this@ShareReceiverActivity,
                                Intent(this@ShareReceiverActivity, BinderClipService::class.java)
                                    .setAction(BinderClipService.ACTION_CANCEL_TRANSFER)
                                    .putExtra(BinderClipService.EXTRA_TRANSFER_ID, progress.transferId))
                        }) { Text(stringResource(R.string.cancel_transfer), color = MaterialTheme.colorScheme.error) }
                    },
                )
            }
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { importShare() }.getOrDefault(R.string.share_error_unsupported) }
            if (result != null) {
                importing = false
                failed = true
                message = result
                return@launch
            }
            try {
                deliveryRevision = AppRuntime.state.value.transferRevision
                ContextCompat.startForegroundService(this@ShareReceiverActivity,
                    Intent(this@ShareReceiverActivity, BinderClipService::class.java).setAction(BinderClipService.ACTION_SEND_SHARED))
                val runtime = AppRuntime.state.value
                val canSend = runtime.peer?.connected == true &&
                    (!fileShare || runtime.transportType == TransportType.LAN || runtime.transportType == TransportType.MESH)
                if (!canSend) {
                    importing = false
                    message = if (fileShare) R.string.transfer_queued else R.string.share_queued
                    kotlinx.coroutines.delay(2200)
                }
            } catch (_: RuntimeException) {
                DiagnosticLog.warning("Could not start share delivery")
                importing = false
                failed = true
                message = R.string.share_error_delivery
                return@launch
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
        fileShare = true
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
            stagedTransferIds = transfers.stageBatch(peerId, sources) {
                val current = DeviceStore(this)
                check(current.groupKey != null && current.peer?.deviceId == peerId)
            }.map { it.metadata.id }.toSet()
            null
        } catch (_: Exception) {
            DiagnosticLog.warning("Shared file import failed")
            if (DeviceStore(this).groupKey == null || DeviceStore(this).peer?.deviceId != peerId) R.string.tile_not_paired
            else R.string.share_error_file
        }
    }
}
