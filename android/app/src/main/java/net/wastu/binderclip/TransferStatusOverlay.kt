package net.wastu.binderclip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Hide leaves delivery running; cancellation is an explicit, separate action. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TransferStatusOverlay(
    progress: TransferProgress,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var cancelling by remember(progress.transferId) { mutableStateOf(false) }
    val background = when {
        progress.isError -> colors.errorContainer
        progress.isSuccess -> colors.primaryContainer
        else -> colors.surfaceContainerHigh
    }
    val foreground = when {
        progress.isError -> colors.onErrorContainer
        progress.isSuccess -> colors.onPrimaryContainer
        else -> colors.onSurface
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = background,
        contentColor = foreground,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = MaterialTheme.shapes.medium,
                    color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
                    if (progress.fileName != null) {
                        Icon(fileTypeIcon(progress.mimeType, progress.fileName), contentDescription = null,
                            modifier = Modifier.padding(10.dp).size(24.dp))
                    } else if (progress.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.padding(10.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(when {
                            progress.isSuccess -> Icons.Rounded.CheckCircle
                            progress.isError -> Icons.Rounded.ErrorOutline
                            else -> Icons.Rounded.Schedule
                        }, contentDescription = null, modifier = Modifier.padding(10.dp).size(24.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    progress.fileName?.let { name ->
                        Text(name, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(progress.message(LocalContext.current), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (progress.state == TransferProgress.State.SENDING || progress.state == TransferProgress.State.RECEIVING) {
                LinearProgressIndicator(progress = { progress.percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth())
            } else if (progress.state == TransferProgress.State.OFFERING && progress.fileName != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (progress.canCancel || progress.isBusy && progress.isOutgoing && progress.transferId != null) {
                FlowRow(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(stringResource(R.string.hide_transfer))
                    }
                    OutlinedButton(onClick = { cancelling = true; onCancel() }, enabled = progress.canCancel && !cancelling,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, if (cancelling || !progress.canCancel) colors.outlineVariant else colors.error),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(stringResource(R.string.cancel_transfer))
                    }
                }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(if (progress.isBusy) R.string.hide_transfer else R.string.close))
                }
            }
        }
    }
}
