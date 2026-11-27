package net.wastu.binderclip

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

class ShareReceiverActivity : ComponentActivity() {
    private val importer by viewModels<ShareImport>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDimAmount(0.32f)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (importer.state.value.message != R.string.share_preparing) finish()
            }
        })
        val batchId = savedInstanceState?.getString("batchId") ?: UUID.randomUUID().toString()
        importer.start(intent, batchId) {
            ContextCompat.startForegroundService(applicationContext, Intent(applicationContext, BinderClipService::class.java)
                .setAction(BinderClipService.ACTION_SEND_SHARED))
        }
        setContent {
            BinderClipTheme {
                val state by importer.state.collectAsState()
                val runtime by AppRuntime.state.collectAsState()
                val delivered = remember(state.textId, runtime.transferRevision) {
                    state.textId?.let { it in DeviceStore(applicationContext).deliveredShares() } == true
                }
                AlertDialog(
                    onDismissRequest = { if (state.message != R.string.share_preparing) finish() },
                    title = { Text(stringResource(if (state.message == R.string.share_preparing) R.string.share_preparing else R.string.transfers_title)) },
                    text = {
                        val peerId = state.peerId
                        if (peerId != null && state.files) {
                            if (state.message == R.string.share_error_delivery) Text(stringResource(state.message))
                            ShareTransferList(peerId, batchId, runtime.transferProgress,
                                retry = { fileAction(BinderClipService.ACTION_RETRY_TRANSFER, it) },
                                cancel = { fileAction(BinderClipService.ACTION_CANCEL_TRANSFER, it) },
                            )
                        } else {
                            val receipt = runtime.transferProgress?.takeIf { state.textId != null && it.transferId == state.textId }
                            Text(receipt?.message(this) ?: if (delivered) {
                                stringResource(R.string.share_sent_confirmation, runtime.peer?.name.orEmpty())
                            } else stringResource(state.message))
                        }
                    },
                    confirmButton = {
                        if (state.message != R.string.share_preparing) {
                            TextButton(onClick = { finish() }) { Text(stringResource(R.string.close)) }
                        }
                    },
                )
            }
        }
    }

    private fun fileAction(action: String, id: String) {
        ContextCompat.startForegroundService(this, Intent(this, BinderClipService::class.java)
            .setAction(action).putExtra(BinderClipService.EXTRA_TRANSFER_ID, id))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("batchId", importer.batchId)
        super.onSaveInstanceState(outState)
    }
}

internal class ShareImport(private val app: Application) : AndroidViewModel(app) {
    data class State(
        val message: Int = R.string.share_preparing,
        val peerId: String? = null,
        val files: Boolean = false,
        val textId: String? = null,
    )

    val state = MutableStateFlow(State())
    var batchId: String? = null
        private set

    fun start(intent: Intent, id: String, startDelivery: () -> Unit) {
        if (batchId != null) return
        batchId = id
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    import(intent, id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    State(R.string.share_error_unsupported)
                }
            }
            if (result.files || result.textId != null) {
                try {
                    startDelivery()
                } catch (_: RuntimeException) {
                    state.value = result.copy(message = R.string.share_error_delivery)
                    return@launch
                }
            }
            state.value = result
        }
    }

    private suspend fun import(intent: Intent, id: String): State {
        val devices = DeviceStore(app)
        val peerId = devices.peer?.deviceId?.takeIf(String::isNotBlank)
        val key = devices.groupKey
        if (key == null || peerId == null) return State(R.string.tile_not_paired)
        val multiple = intent.action == Intent.ACTION_SEND_MULTIPLE
        if (!multiple && intent.action != Intent.ACTION_SEND) return State(R.string.share_error_unsupported)

        val explicit = if (multiple) {
            val extras: List<*> = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            extras.map { it as? Uri ?: error("Invalid share item") }
        } else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))

        val clip = intent.clipData
        val items = clip?.let { data -> List(data.itemCount) { data.getItemAt(it) } }.orEmpty()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf(String::isNotBlank)
            ?: items.firstNotNullOfOrNull { it.text?.toString()?.takeIf(String::isNotBlank) }
        val clipUris = items.mapNotNull { it.uri }
        val textShare = !multiple && explicit.isEmpty() && text != null && (clipUris.isEmpty() ||
            (intent.type?.startsWith("text/", ignoreCase = true) == true &&
                (WebLinks.isWebUrl(text.trim()) || clipUris.all {
                    runCatching { app.contentResolver.getType(it)?.startsWith("image/") == true }.getOrDefault(false)
                })))
        val streams = if (textShare) emptyList() else explicit.ifEmpty { clipUris }.distinct()
        if (streams.isEmpty()) {
            if (text == null) return State(R.string.share_error_unsupported)
            if (!devices.enqueueShare(text, id)) return State(R.string.share_error_queue_full)
            return State(R.string.share_queued, peerId, textId = id)
        }
        if (streams.size > TransferBatch.MAX_FILES) return State(R.string.share_error_too_many)
        val transfers = FileTransferStore.forContext(app)
        val resolver = app.contentResolver
        val jobContext = coroutineContext
        fun checkPairing() {
            jobContext.ensureActive()
            if (devices.peer?.deviceId != peerId || devices.groupKey?.contentEquals(key) != true) throw PairingChanged()
        }
        try {
            transfers.beginShare(peerId, TransferBatch(id, 0, streams.size), ::checkPairing)
            for ((index, uri) in streams.withIndex()) {
                jobContext.ensureActive()
                val batch = TransferBatch(id, index, streams.size)
                var name: String? = null
                val failure = try {
                    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority == null ||
                        uri.authority?.substringAfterLast('@') == "${app.packageName}.images"
                    ) throw FileNotFoundException()
                    val document = resolver.describe(uri, intent.type)
                    name = document.name
                    transfers.stageSharedFile(peerId, batch, document.name, document.mime, document.open,
                        checkActive = jobContext::ensureActive, validateCommit = ::checkPairing)
                    null
                } catch (e: PairingChanged) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (_: ShareFileTooLarge) {
                    ShareFileState.TOO_LARGE
                } catch (_: ShareQueueFull) {
                    ShareFileState.QUEUE_FULL
                } catch (_: FileNotFoundException) {
                    ShareFileState.UNREADABLE
                } catch (_: SecurityException) {
                    ShareFileState.UNREADABLE
                } catch (_: Exception) {
                    ShareFileState.STORAGE
                }
                if (failure != null) {
                    // A full disk can prevent even a history update. The batch plan still records a missing item.
                    try {
                        transfers.recordImportFailure(peerId, batch, name, failure, ::checkPairing)
                    } catch (e: PairingChanged) {
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: java.io.IOException) {
                        DiagnosticLog.warning("Could not record a share import failure")
                    }
                }
            }
        } catch (_: PairingChanged) {
            return State(R.string.tile_not_paired)
        } finally {
            transfers.endShare(id)
        }
        return State(R.string.share_queued, peerId, files = true)
    }
}

private class PairingChanged : Exception()

private data class SharedDocument(val name: String, val mime: String, val open: () -> InputStream)

private fun ContentResolver.describe(uri: Uri, intentType: String?): SharedDocument {
    val name = runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.takeIf(String::isNotBlank) ?: "file"
    val mime = sequenceOf(runCatching { getType(uri) }.getOrNull(), intentType)
        .mapNotNull { it?.substringBefore(';')?.trim()?.lowercase() }
        .firstOrNull { it.length <= 127 && it.matches(Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")) }
        ?: "application/octet-stream"
    if (!isVirtualDocument(uri)) {
        return SharedDocument(name, mime) { openInputStream(uri) ?: throw FileNotFoundException() }
    }
    val export = getStreamTypes(uri, "*/*")?.let { types -> types.firstOrNull { it == mime } ?: types.firstOrNull() }
        ?: throw FileNotFoundException()
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(export)
    val exportName = if (extension == null || name.endsWith(".$extension", ignoreCase = true)) name else "$name.$extension"
    return SharedDocument(exportName, export) {
        val descriptor = openTypedAssetFileDescriptor(uri, export, null) ?: throw FileNotFoundException()
        try {
            descriptor.createInputStream()
        } catch (e: Exception) {
            descriptor.close()
            throw e
        }
    }
}

private fun ContentResolver.isVirtualDocument(uri: Uri): Boolean = runCatching {
    query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
        val column = it.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
        column >= 0 && it.moveToFirst() && it.getLong(column) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT.toLong() != 0L
    } == true
}.getOrDefault(false)
