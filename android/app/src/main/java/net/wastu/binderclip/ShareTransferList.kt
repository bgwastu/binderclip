package net.wastu.binderclip

import android.app.DownloadManager
import android.content.Intent
import androidx.core.content.ContextCompat
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ShareTransferList(
    peerId: String,
    batchId: String?,
    progress: TransferProgress?,
    retry: (String) -> Unit,
    cancel: (String) -> Unit,
) {
    val context = LocalContext.current
    val batches by produceState(emptyList<List<ShareFile>>(), peerId, batchId) {
        FileTransferStore.shareChanges.collect {
            value = withContext(Dispatchers.IO) {
                val store = FileTransferStore.forContext(context)
                val incoming = if (batchId == null) store.downloadHistory(peerId) else emptyList()
                (store.shareFiles(peerId, batchId) + incoming)
                    .groupBy { it.isOutgoing to it.batch.id }.values.take(16).map { rows -> rows.sortedBy { it.batch.index } }
            }
        }
    }
    LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (batches.isEmpty()) item { Text(stringResource(R.string.transfers_empty)) }
        for (rows in batches) {
            val batch = rows.first().batch
            item(key = "${rows.first().isOutgoing}:${batch.id}") {
                Text(
                    stringResource(R.string.transfers_saved_count, rows.count { it.state in setOf(ShareFileState.SENT, ShareFileState.RECEIVED) }, batch.count),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(rows, key = { "${it.isOutgoing}:${it.id}" }) { row ->
                val active = progress?.takeIf { it.transferId == row.id && it.isOutgoing == row.isOutgoing && row.state == ShareFileState.QUEUED }
                ListItem(
                    headlineContent = { Text(row.name ?: stringResource(R.string.transfer_kind_file)) },
                    supportingContent = {
                        Column {
                            val status = active?.message(context) ?: stringResource(row.failure?.message ?: when (row.state) {
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
                            Text(row.bytes?.let { "$status · ${Formatter.formatShortFileSize(context, it)}" } ?: status)
                            if (active?.state == TransferProgress.State.SENDING) {
                                LinearProgressIndicator(progress = { active.percent / 100f })
                            }
                        }
                    },
                    trailingContent = {
                        when {
                            row.isOutgoing && row.state == ShareFileState.REJECTED ->
                                TextButton(onClick = { retry(row.id) }) { Text(stringResource(R.string.transfers_retry)) }
                            row.isOutgoing && row.state == ShareFileState.QUEUED && (active == null || active.canCancel) ->
                                TextButton(onClick = { cancel(row.id) }) { Text(stringResource(R.string.cancel_transfer)) }
                            row.downloadUri != null -> Column {
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
                    },
                )
            }
        }
    }
}
