package net.wastu.binderclip

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun TransfersScreen(state: AppState, onSend: () -> Unit, retry: (String) -> Unit, cancel: (String) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val canSend = state.peer?.connected == true && state.transportType != TransportType.BLUETOOTH
        val peerId = state.peer?.deviceId
        if (peerId == null) {
            EmptyTransfers(Modifier.padding(28.dp))
        } else {
            ShareTransferList(
                peerId = peerId,
                batchId = null,
                progress = state.transferProgress,
                retry = retry,
                cancel = cancel,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
                send = onSend.takeIf { canSend },
            )
        }
    }
}

@Composable
internal fun ShareTransferList(
    peerId: String,
    batchId: String?,
    progress: TransferProgress?,
    retry: (String) -> Unit,
    cancel: (String) -> Unit,
    modifier: Modifier = Modifier.heightIn(max = 380.dp),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    send: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val batches by produceState<List<List<ShareFile>>?>(null, peerId, batchId) {
        FileTransferStore.shareChanges.collect {
            value = withContext(Dispatchers.IO) {
                val store = FileTransferStore.forContext(context)
                val incoming = if (batchId == null) store.downloadHistory(peerId) else emptyList()
                (store.shareFiles(peerId, batchId) + incoming)
                    .groupBy { it.isOutgoing to it.batch.id }.values.take(16).map { rows -> rows.sortedBy { it.batch.index } }
            }
        }
    }
    val rowsByBatch = batches ?: return
    LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (rowsByBatch.isEmpty()) {
            item { EmptyTransfers(Modifier.padding(vertical = 32.dp), send) }
        } else if (send != null) {
            item {
                Button(onClick = send, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).heightIn(min = 52.dp)) {
                    ActionLabel(Icons.Rounded.FolderOpen, R.string.send_file)
                }
            }
        }
        for (rows in rowsByBatch) {
            val first = rows.first()
            item(key = "${first.isOutgoing}:${first.batch.id}") {
                TransferBatchTitle(rows)
            }
            itemsIndexed(rows, key = { _, row -> "${row.isOutgoing}:${row.id}" }) { index, row ->
                val active = progress?.takeIf { it.transferId == row.id && it.isOutgoing == row.isOutgoing && row.state == ShareFileState.QUEUED }
                TransferRow(row, index, rows.size, active, retry, cancel)
            }
        }
    }
}

@Composable
private fun TransferBatchTitle(rows: List<ShareFile>) {
    val first = rows.first()
    Row(
        modifier = Modifier.padding(start = 5.dp, end = 5.dp, top = 14.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (first.isOutgoing) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
            null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(18.dp),
        )
        Column {
            SectionTitle(stringResource(if (first.isOutgoing) R.string.transfers_outgoing else R.string.transfers_incoming))
            Text(
                stringResource(
                    R.string.transfers_saved_count,
                    rows.count { it.state == ShareFileState.SENT || it.state == ShareFileState.RECEIVED },
                    first.batch.count,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun TransferRow(
    row: ShareFile,
    index: Int,
    count: Int,
    progress: TransferProgress?,
    retry: (String) -> Unit,
    cancel: (String) -> Unit,
) {
    val context = LocalContext.current
    val title = row.name ?: stringResource(R.string.transfer_kind_file)
    val status = progress?.message(context) ?: stringResource(row.failure?.message ?: when (row.state) {
        ShareFileState.PREPARING -> R.string.share_preparing
        ShareFileState.QUEUED -> R.string.transfers_waiting
        ShareFileState.SENT, ShareFileState.RECEIVED -> R.string.transfers_saved
        ShareFileState.REJECTED -> R.string.transfer_failed
        ShareFileState.CANCELLED -> R.string.transfer_cancelled
        ShareFileState.UNREADABLE -> R.string.transfers_unreadable
        ShareFileState.TOO_LARGE -> R.string.transfers_too_large
        ShareFileState.STORAGE -> R.string.transfers_storage
        ShareFileState.QUEUE_FULL -> R.string.transfers_queue_full
        ShareFileState.EXPIRED -> R.string.transfers_expired
    })
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = groupedListColors(),
        leadingContent = {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(fileTypeIcon(row.mimeType, title), null, Modifier.size(21.dp))
                }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.bytes?.let { "$status · ${Formatter.formatShortFileSize(context, it)}" } ?: status)
                if (progress?.state == TransferProgress.State.SENDING || progress?.state == TransferProgress.State.RECEIVING) {
                    LinearWavyProgressIndicator(
                        progress = { progress.percent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                when {
                    row.isOutgoing && row.state == ShareFileState.REJECTED ->
                        TextButton(onClick = { retry(row.id) }) { Text(stringResource(R.string.transfers_retry)) }
                    row.isOutgoing && row.state == ShareFileState.QUEUED && (progress == null || progress.canCancel) ->
                        TextButton(onClick = { cancel(row.id) }) { Text(stringResource(R.string.cancel_transfer)) }
                    row.downloadUri != null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            val view = Intent(Intent.ACTION_VIEW, Uri.parse(row.downloadUri))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(view) }
                                .onFailure { runCatching { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } }
                        }) { Text(stringResource(R.string.transfers_open)) }
                        if (row.mimeType in ImagePayload.ALLOWED_MIME_TYPES && (row.bytes ?: 0) in 1..ImagePayload.MAXIMUM_BYTES.toLong()) {
                            TextButton(onClick = {
                                ContextCompat.startForegroundService(context, Intent(context, BinderClipService::class.java)
                                    .setAction(BinderClipService.ACTION_COPY_SHARED_IMAGE)
                                    .putExtra(BinderClipService.EXTRA_TRANSFER_ID, row.id))
                            }) { Text(stringResource(R.string.copy)) }
                        }
                    }
                }
            }
        },
    ) { Text(title) }
}

@Composable
private fun EmptyTransfers(modifier: Modifier = Modifier, send: (() -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(34.dp))
            }
        }
        Text(
            stringResource(R.string.transfers_empty),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        if (send != null) Button(onClick = send, modifier = Modifier.heightIn(min = 52.dp)) {
            ActionLabel(Icons.Rounded.FolderOpen, R.string.send_file)
        }
    }
}
