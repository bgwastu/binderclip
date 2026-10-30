package net.wastu.binderclip

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class Screen { Home, Setup, Settings }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
internal fun HomeScreen(
    state: AppState,
    devices: List<RememberedPeer>,
    missingPermissions: List<PermissionNeed>,
    visibleTransfer: TransferProgress?,
    onDismissTransfer: (TransferProgress) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onScan: () -> Unit,
    onSend: () -> Unit,
    onSendFile: () -> Unit,
    onCopy: () -> Unit,
    onRefresh: () -> Unit,
    onManage: (RememberedPeer) -> Unit,
    onOpenSetup: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val activity by ActivityLog.events.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_binder_clip),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(AppProfile.current.displayName, fontWeight = FontWeight.SemiBold)
                }
            },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.section_settings))
                }
            },
        )
    }) { insets ->
        var refreshing by remember { mutableStateOf(false) }
        val refreshScope = rememberCoroutineScope()
        val pullRefreshState = rememberPullRefreshState(refreshing, {
            refreshing = true
            onRefresh()
            refreshScope.launch { delay(1200); refreshing = false }
        })
        Box(Modifier.fillMaxSize().padding(insets)) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .consumeWindowInsets(insets)
                    .pullRefresh(pullRefreshState, refreshing),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (AppProfile.current.isDebug) item { DebugClipboardPanel() }

                val device = devices.firstOrNull()
                if (device == null) {
                    item {
                        EmptyPairedDevices(onScan = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onScan()
                        })
                    }
                } else {
                    item(key = "hero") {
                        HeroCard(
                            device = device,
                            state = state,
                            onSendClipboard = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSend()
                            },
                            onSendFile = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSendFile()
                            },
                            onManage = { onManage(device) },
                        )
                    }
                    item(key = "capture") { CaptureRow(state, onOpenSetup) }
                }

                if (state.pendingText || state.pendingImage) {
                    item {
                        ListItem(
                            headlineContent = {
                                Text(if (state.pendingImage) stringResource(R.string.image_ready_to_copy) else stringResource(R.string.text_ready_to_copy))
                            },
                            trailingContent = { TextButton(onClick = onCopy) { Text(stringResource(R.string.copy)) } },
                        )
                    }
                }

                if (missingPermissions.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.section_permissions)) }
                    items(missingPermissions, key = { it.title }) { PermissionRow(it) }
                }

                if (device != null) {
                    item { SectionTitle(stringResource(R.string.activity_title), topPadding = 0.dp) }
                    if (activity.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.activity_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    } else {
                        item {
                            Card(
                                Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            ) {
                                Column {
                                    activity.forEachIndexed { index, event ->
                                        ActivityRow(event)
                                        if (index != activity.lastIndex) HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            PullRefreshIndicator(refreshing = refreshing, state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))

            visibleTransfer?.let { progress ->
                TransferStatusOverlay(
                    progress = progress,
                    onDismiss = { onDismissTransfer(progress) },
                    onCancel = { progress.transferId?.let(onCancelTransfer) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .widthIn(max = 560.dp),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    device: RememberedPeer,
    state: AppState,
    onSendClipboard: () -> Unit,
    onSendFile: () -> Unit,
    onManage: () -> Unit,
) {
    val connected = device.connected
    val transport = state.transportType
    val statusText = when {
        connected && transport == TransportType.LAN -> stringResource(R.string.status_connected_lan)
        connected && transport == TransportType.MESH -> stringResource(R.string.status_connected_mesh)
        connected && transport == TransportType.BLUETOOTH -> stringResource(R.string.status_connected_bluetooth)
        connected -> stringResource(R.string.status_connected)
        state.connectionPhase == ConnectionPhase.Connecting -> stringResource(R.string.status_connecting)
        state.connectionPhase == ConnectionPhase.Reconnecting -> stringResource(R.string.status_reconnecting)
        else -> stringResource(R.string.offline)
    }
    val dot = when {
        connected -> Color(0xFF2E7D32)
        state.connectionPhase == ConnectionPhase.Connecting || state.connectionPhase == ConnectionPhase.Reconnecting -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (device.platform == "macOS") Icons.Outlined.LaptopMac else Icons.Outlined.Android,
                            contentDescription = null,
                            tint = if (connected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                IconButton(onClick = onManage) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.device_details), tint = LocalContentColor.current)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSendClipboard, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send_current_clipboard), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(
                    onClick = onSendFile,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    enabled = connected && transport != TransportType.BLUETOOTH,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send_file), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (connected && transport == TransportType.BLUETOOTH) {
                    Text(
                        stringResource(R.string.file_connection_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureRow(state: AppState, onClick: () -> Unit) {
    val summary = when {
        state.rootAvailable && state.automaticClipboardEnabled -> R.string.capture_row_root
        state.shizukuCaptureActive -> R.string.capture_row_shizuku
        state.shizukuCapturePaused -> R.string.capture_row_paused
        else -> R.string.capture_row_off
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Outlined.ContentPaste, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(stringResource(R.string.capture_row_title)) },
            supportingContent = { Text(stringResource(summary)) },
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        )
    }
}

@Composable
private fun ActivityRow(event: ActivityEvent) {
    val kind = stringResource(
        when (event.kind) {
            ActivityEvent.Kind.Text -> R.string.transfer_kind_text
            ActivityEvent.Kind.Link -> R.string.transfer_kind_link
            ActivityEvent.Kind.Image -> R.string.transfer_kind_image
            ActivityEvent.Kind.File -> R.string.transfer_kind_file
        }
    )
    val icon = when (event.kind) {
        ActivityEvent.Kind.Text -> Icons.Outlined.ContentPaste
        ActivityEvent.Kind.Link -> Icons.Outlined.Link
        ActivityEvent.Kind.Image -> Icons.Outlined.Image
        ActivityEvent.Kind.File -> Icons.Outlined.FolderOpen
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(stringResource(if (event.outgoing) R.string.transfer_sent else R.string.transfer_received, kind)) },
        supportingContent = {
            Text(DateUtils.getRelativeTimeSpanString(event.atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: AppState,
    bluetoothGranted: Boolean,
    missingPermissions: List<PermissionNeed>,
    onBack: () -> Unit,
    onToggleBtFallback: (Boolean) -> Unit,
    onToggleAutostart: (Boolean) -> Unit,
    onRequestBluetooth: () -> Unit,
    onScan: () -> Unit,
    onShowLogs: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.section_settings)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
        )
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            LazyColumn(
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 600.dp).fillMaxSize().consumeWindowInsets(insets),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 32.dp),
            ) {
                item {
                    PreferenceToggle(
                        title = stringResource(R.string.bt_fallback_title),
                        summary = when {
                            !bluetoothGranted -> stringResource(R.string.bt_fallback_desc_needs_permission)
                            !state.btFallbackEnabled -> stringResource(R.string.bt_fallback_desc_off)
                            !state.bluetoothEnabled -> stringResource(R.string.bt_fallback_desc_disabled)
                            else -> stringResource(R.string.bt_fallback_desc_on)
                        },
                        checked = state.btFallbackEnabled,
                        enabled = bluetoothGranted,
                        onChanged = onToggleBtFallback,
                    )
                }
                if (state.btFallbackEnabled && !state.bluetoothEnabled) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.bt_is_off_title)) },
                            supportingContent = { Text(stringResource(R.string.bt_is_off_desc)) },
                            trailingContent = { TextButton(onClick = onRequestBluetooth) { Text(stringResource(R.string.turn_on)) } },
                        )
                    }
                }
                item {
                    PreferenceToggle(
                        title = stringResource(R.string.perm_autostart),
                        summary = stringResource(R.string.perm_autostart_desc),
                        checked = state.autostartEnabled,
                        onChanged = onToggleAutostart,
                    )
                }
                if (missingPermissions.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.section_permissions)) }
                    items(missingPermissions, key = { it.title }) { PermissionRow(it) }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                if (state.peer != null) {
                    item { ActionRow(Icons.Outlined.QrCodeScanner, R.string.scan_qr_to_pair, onScan) }
                }
                item { ActionRow(Icons.Outlined.Description, R.string.show_logs, onShowLogs) }
                item { ActionRow(Icons.Outlined.SystemUpdate, R.string.update_check, onCheckUpdates) }
                item {
                    Text(
                        "${AppProfile.current.displayName} ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) = ListItem(
    modifier = Modifier.clickable(onClick = onClick),
    leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
    headlineContent = { Text(stringResource(label)) },
)
