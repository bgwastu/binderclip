package net.wastu.binderclip

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class Screen(@param:StringRes val label: Int) {
    Home(R.string.nav_clipboard),
    Transfers(R.string.transfers_title),
    Settings(R.string.section_settings),
}

private val screenPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    state: AppState,
    visibleTransfer: TransferProgress?,
    onDismissTransfer: (TransferProgress) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onScan: () -> Unit,
    onSend: () -> Unit,
    onSendFile: () -> Unit,
    onCopy: () -> Unit,
    onCopyShared: () -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onManage: (RememberedPeer) -> Unit,
    onOpenSetup: () -> Unit,
) {
    val activity by ActivityLog.events.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()
    val device = state.peer

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            onRefresh()
            refreshScope.launch { delay(1200); refreshing = false }
        },
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            contentPadding = screenPadding,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "device") {
                ConnectionCard(device, state, onScan, onSend, onSendFile, onReconnect) {
                    device?.let(onManage)
                }
            }
            if (state.pendingSharedText || state.pendingText || state.pendingImage) {
                item(key = "incoming") {
                    val count = (if (state.pendingSharedText) 1 else 0) + (if (state.pendingText || state.pendingImage) 1 else 0)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (state.pendingSharedText) CopyRow(R.string.shared_text_ready, 0, count, onCopyShared)
                        if (state.pendingText || state.pendingImage) {
                            val label = if (state.pendingImage) R.string.image_ready_to_copy else R.string.text_ready_to_copy
                            CopyRow(label, count - 1, count, onCopy)
                        }
                    }
                }
            }
            visibleTransfer?.let { progress ->
                item(key = "transfer") {
                    TransferStatusOverlay(
                        progress = progress,
                        onDismiss = { onDismissTransfer(progress) },
                        onCancel = { progress.transferId?.let(onCancelTransfer) },
                    )
                }
            }
            if (device != null) {
                item(key = "activity") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.activity_title))
                        if (activity.isEmpty()) {
                            SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(0, 1), colors = groupedListColors()) {
                                Text(stringResource(R.string.activity_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                activity.forEachIndexed { index, event -> ActivityRow(event, index, activity.size) }
                            }
                        }
                    }
                }
                item(key = "capture") { CaptureRow(state, onOpenSetup, 0, 1) }
            }
            if (AppProfile.current.isDebug) item(key = "debug") { DebugClipboardPanel() }
        }
    }
}

@Composable
private fun ConnectionCard(
    device: RememberedPeer?,
    state: AppState,
    onScan: () -> Unit,
    onSend: () -> Unit,
    onSendFile: () -> Unit,
    onReconnect: () -> Unit,
    onManage: () -> Unit,
) {
    val connected = device?.connected == true
    val connecting = state.connectionPhase == ConnectionPhase.Connecting || state.connectionPhase == ConnectionPhase.Reconnecting
    val viaBluetooth = state.transportType == TransportType.BLUETOOTH
    val status = when {
        connected -> when (state.transportType) {
            TransportType.LAN -> R.string.status_connected_lan
            TransportType.MESH -> R.string.status_connected_mesh
            TransportType.BLUETOOTH -> R.string.status_connected_bluetooth
            TransportType.NONE -> R.string.status_connected
        }
        state.connectionPhase == ConnectionPhase.Connecting -> R.string.status_connecting
        state.connectionPhase == ConnectionPhase.Reconnecting -> R.string.status_reconnecting
        else -> R.string.offline
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = Ink,
        contentColor = OnInk,
        border = BorderStroke(1.dp, Color(0xFF607894)),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    modifier = Modifier.size(54.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = Color(0xFF496689),
                    contentColor = OnInk,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        when {
                            device == null -> Icon(painterResource(R.drawable.ic_binder_clip), null, Modifier.size(25.dp))
                            device.platform == "macOS" -> Icon(Icons.Rounded.LaptopMac, null, Modifier.size(28.dp))
                            else -> Icon(Icons.Rounded.Android, null, Modifier.size(28.dp))
                        }
                    }
                }
                Text(
                    text = device?.name ?: stringResource(R.string.no_devices_paired),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (device != null) IconButton(onClick = onManage) {
                    Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.device_details))
                }
            }

            if (device == null) {
                Text(stringResource(R.string.no_devices_paired_desc), style = MaterialTheme.typography.bodyLarge)
                CardButton(R.string.scan_qr_to_pair, Icons.Rounded.QrCodeScanner, onScan)
            } else {
                Surface(
                    shape = CircleShape,
                    color = if (connected) Color(0xFFD1E9DD) else Color(0xFF425F82),
                    contentColor = if (connected) Color(0xFF1C3B31) else OnInk,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (connected) Icon(Icons.Rounded.Check, null, Modifier.size(15.dp))
                        Text(stringResource(status), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (connected) {
                        CardButton(R.string.send_current_clipboard, Icons.Rounded.ContentPaste, onSend)
                        FilledTonalButton(
                            onClick = onSendFile,
                            shapes = ButtonDefaults.shapes(),
                            enabled = !viaBluetooth,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0xFF4A688A),
                                contentColor = OnInk,
                                disabledContainerColor = Color(0xFF445F80),
                                disabledContentColor = Color(0xFFCEDBEA),
                            ),
                        ) { ActionLabel(Icons.Rounded.FolderOpen, R.string.send_file) }
                        if (viaBluetooth) {
                            Text(stringResource(R.string.file_connection_required), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        CardButton(R.string.reconnect, Icons.Rounded.Refresh, onReconnect, enabled = !connecting)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardButton(@StringRes label: Int, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = OnInk,
            contentColor = Ink,
            disabledContainerColor = Color(0xFF445F80),
            disabledContentColor = Color(0xFFCEDBEA),
        ),
    ) { ActionLabel(icon, label) }
}

@Composable
internal fun ActionLabel(icon: ImageVector, @StringRes label: Int) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text(stringResource(label))
}

@Composable
private fun CopyRow(@StringRes label: Int, index: Int, count: Int, onCopy: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
        leadingContent = { Icon(Icons.Rounded.ContentPaste, null, tint = colors.onPrimaryContainer) },
        trailingContent = { FilledTonalButton(onClick = onCopy) { Text(stringResource(R.string.copy)) } },
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) { Text(stringResource(label)) }
}

@Composable
private fun CaptureRow(state: AppState, onClick: () -> Unit, index: Int, count: Int) {
    val summary = when {
        state.rootAvailable && state.automaticClipboardEnabled -> R.string.capture_row_root
        state.shizukuCaptureActive -> R.string.capture_row_shizuku
        state.shizukuCapturePaused -> R.string.capture_row_paused
        else -> R.string.capture_row_off
    }
    SettingsAction(Icons.Rounded.ContentPaste, R.string.capture_row_title, onClick, index, count, summary)
}

@Composable
private fun ActivityRow(event: ActivityEvent, index: Int, count: Int) {
    val (label, icon) = when (event.kind) {
        ActivityEvent.Kind.Text -> R.string.transfer_kind_text to Icons.Rounded.ContentPaste
        ActivityEvent.Kind.Link -> R.string.transfer_kind_link to Icons.Rounded.Link
        ActivityEvent.Kind.Image -> R.string.transfer_kind_image to Icons.Rounded.Image
        ActivityEvent.Kind.File -> R.string.transfer_kind_file to Icons.Rounded.FolderOpen
    }
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = groupedListColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = {
            val now = System.currentTimeMillis()
            Text(
                if (now - event.atMs < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.activity_just_now)
                else DateUtils.getRelativeTimeSpanString(event.atMs, now, DateUtils.MINUTE_IN_MILLIS).toString(),
            )
        },
    ) { Text(stringResource(if (event.outgoing) R.string.transfer_sent else R.string.transfer_received, stringResource(label))) }
}

@Composable
internal fun SettingsScreen(
    state: AppState,
    bluetoothGranted: Boolean,
    missingPermissions: List<PermissionNeed>,
    onToggleBtFallback: (Boolean) -> Unit,
    onToggleAutostart: (Boolean) -> Unit,
    onAutoApplyClipboard: (Boolean) -> Unit,
    onRequestBluetooth: () -> Unit,
    onOpenSetup: () -> Unit,
    onScan: () -> Unit,
    onShowLogs: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            contentPadding = screenPadding,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SettingsGroup(R.string.nav_clipboard) {
                    val paired = state.peer != null
                    PreferenceToggle(
                        title = R.string.clipboard_auto_apply,
                        summary = if (state.autoApplyClipboard) R.string.clipboard_auto_apply_on else R.string.clipboard_auto_apply_off,
                        icon = Icons.Rounded.ContentPaste,
                        checked = state.autoApplyClipboard,
                        index = 0,
                        count = if (paired) 2 else 1,
                        onChanged = onAutoApplyClipboard,
                    )
                    if (paired) CaptureRow(state, onOpenSetup, 1, 2)
                }
            }
            if (missingPermissions.isNotEmpty()) item {
                SettingsGroup(R.string.section_permissions) {
                    missingPermissions.forEachIndexed { index, need ->
                        SegmentedListItem(
                            shapes = ListItemDefaults.segmentedShapes(index, missingPermissions.size),
                            colors = groupedListColors(),
                            leadingContent = { Icon(need.icon, null) },
                            supportingContent = { Text(stringResource(need.summary)) },
                            trailingContent = {
                                TextButton(onClick = need.onClick) { Text(stringResource(R.string.allow)) }
                            },
                        ) { Text(stringResource(need.title)) }
                    }
                }
            }
            item {
                SettingsGroup(R.string.connections_title) {
                    val bluetoothOff = state.btFallbackEnabled && !state.bluetoothEnabled
                    val count = if (bluetoothOff) 3 else 2
                    PreferenceToggle(
                        title = R.string.bt_fallback_title,
                        summary = when {
                            !bluetoothGranted -> R.string.bt_fallback_desc_needs_permission
                            !state.btFallbackEnabled -> R.string.bt_fallback_desc_off
                            !state.bluetoothEnabled -> R.string.bt_fallback_desc_disabled
                            else -> R.string.bt_fallback_desc_on
                        },
                        icon = Icons.Rounded.Bluetooth,
                        checked = state.btFallbackEnabled,
                        enabled = bluetoothGranted,
                        index = 0,
                        count = count,
                        onChanged = onToggleBtFallback,
                    )
                    if (bluetoothOff) SettingsAction(Icons.Rounded.Bluetooth, R.string.turn_on, onRequestBluetooth, 1, count, R.string.bt_is_off_desc)
                    PreferenceToggle(
                        title = R.string.perm_autostart,
                        summary = R.string.perm_autostart_desc,
                        icon = Icons.Rounded.PowerSettingsNew,
                        checked = state.autostartEnabled,
                        index = count - 1,
                        count = count,
                        onChanged = onToggleAutostart,
                    )
                }
            }
            item {
                val paired = state.peer != null
                val count = if (paired) 3 else 2
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (paired) SettingsAction(Icons.Rounded.QrCodeScanner, R.string.scan_qr_to_pair, onScan, 0, count)
                    SettingsAction(Icons.Rounded.Description, R.string.show_logs, onShowLogs, count - 2, count)
                    SettingsAction(Icons.Rounded.SystemUpdate, R.string.update_check, onCheckUpdates, count - 1, count)
                }
            }
            item {
                Text(
                    "${AppProfile.current.displayName} ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SettingsGroup(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(title))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
    }
}

@Composable
private fun PreferenceToggle(
    @StringRes title: Int,
    @StringRes summary: Int,
    icon: ImageVector,
    checked: Boolean,
    index: Int,
    count: Int,
    enabled: Boolean = true,
    onChanged: (Boolean) -> Unit,
) = SegmentedListItem(
    shapes = ListItemDefaults.segmentedShapes(index, count),
    colors = groupedListColors(),
    modifier = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChanged),
    enabled = enabled,
    leadingContent = { Icon(icon, null) },
    supportingContent = { Text(stringResource(summary)) },
    trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
) { Text(stringResource(title)) }

@Composable
private fun SettingsAction(
    icon: ImageVector,
    @StringRes label: Int,
    onClick: () -> Unit,
    index: Int,
    count: Int,
    @StringRes summary: Int? = null,
) = SegmentedListItem(
    onClick = onClick,
    shapes = ListItemDefaults.segmentedShapes(index, count),
    colors = groupedListColors(),
    leadingContent = { Icon(icon, null) },
    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
    supportingContent = summary?.let { { Text(stringResource(it)) } },
) { Text(stringResource(label)) }
