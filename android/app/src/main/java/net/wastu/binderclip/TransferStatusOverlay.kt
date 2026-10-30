package net.wastu.binderclip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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

/** Neutral surface; only the leading icon carries state color. Close hides it, Cancel is explicit. */
@Composable
internal fun TransferStatusOverlay(
    progress: TransferProgress,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var cancelling by remember(progress.transferId) { mutableStateOf(false) }
    val iconTint = when {
        progress.isError -> colors.error
        progress.isSuccess -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    progress.fileName != null && !progress.isSuccess && !progress.isError ->
                        Icon(fileTypeIcon(progress.mimeType, progress.fileName), contentDescription = null,
                            tint = iconTint, modifier = Modifier.size(24.dp))
                    progress.isBusy ->
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    else -> Icon(when {
                        progress.isSuccess -> Icons.Rounded.CheckCircle
                        progress.isError -> Icons.Rounded.ErrorOutline
                        else -> Icons.Rounded.Schedule
                    }, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    progress.fileName?.let { name ->
                        Text(name, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(progress.message(LocalContext.current), style = MaterialTheme.typography.bodyMedium,
                        color = if (progress.fileName != null) colors.onSurfaceVariant else colors.onSurface)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close),
                        tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.padding(end = 12.dp, bottom = 4.dp)) {
                if (progress.state == TransferProgress.State.SENDING || progress.state == TransferProgress.State.RECEIVING) {
                    LinearProgressIndicator(progress = { progress.percent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth())
                } else if (progress.state == TransferProgress.State.OFFERING && progress.fileName != null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (progress.canCancel || progress.isBusy && progress.isOutgoing && progress.transferId != null) {
                    TextButton(onClick = { cancelling = true; onCancel() },
                        enabled = progress.canCancel && !cancelling,
                        modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.cancel_transfer), color = if (cancelling || !progress.canCancel) colors.outline else colors.error)
                    }
                }
            }
        }
    }
}
