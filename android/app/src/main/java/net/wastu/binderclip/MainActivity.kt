package net.wastu.binderclip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.wastu.binderclip.shizuku.ShizukuAccess

class MainActivity : AppCompatActivity() {
    private var permissionRevision by mutableIntStateOf(0)
    private val chooseFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) startActivity(Intent(this, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putExtra("share_as_file", true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
    private val qrScanner = registerForActivityResult(io.github.g00fy2.quickie.ScanCustomCode()) { result ->
        when (result) {
            is io.github.g00fy2.quickie.QRResult.QRSuccess -> {
                val uri = result.content.rawValue
                android.util.Log.i("BinderClip", "Pairing QR scanned successfully")
                if (!uri.isNullOrBlank()) {
                    pair(uri)
                }
            }
            is io.github.g00fy2.quickie.QRResult.QRUserCanceled -> {
                android.util.Log.d("BinderClip", "QR Scan cancelled by user")
            }
            is io.github.g00fy2.quickie.QRResult.QRMissingPermission -> {
                android.util.Log.w("BinderClip", "Camera permission missing for scanner")
                requestCamera.launch(Manifest.permission.CAMERA)
            }
            is io.github.g00fy2.quickie.QRResult.QRError -> {
                android.util.Log.e("BinderClip", "QR Scan error", result.exception)
                Toast.makeText(this, "Scan error: ${result.exception.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRevision += 1
        if (granted) scan()
    }
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision += 1 }
    private val requestBluetooth =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRevision += 1 }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        check(packageName == AppProfile.current.applicationId)
        DiagnosticLog.initialize(this)
        ShizukuAccess.initialize(this)
        startService(BinderClipService.ACTION_START)
        intent?.dataString?.takeIf { it.startsWith(AppProfile.current.pairingScheme + "://") }?.let(::pair)
        setContent {
            BinderClipTheme {
                val state by AppRuntime.state.collectAsState()
                val revision = permissionRevision
                val notificationsGranted =
                    Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                val bluetoothGranted = if (Build.VERSION.SDK_INT >= 31) {
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
                var showBluetoothGuide by remember { mutableStateOf(false) }
                DisposableEffect(Unit) {
                    startService(BinderClipService.ACTION_UI_VISIBLE, visible = true)
                    onDispose { startService(BinderClipService.ACTION_UI_VISIBLE, visible = false) }
                }
                BinderClipScreen(
                    state = state,
                    notificationsGranted = notificationsGranted,
                    bluetoothGranted = bluetoothGranted,
                    permissionRevision = revision,
                    onScan = ::scan,
                    onRequestNotifications = { if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    onSend = { startService(BinderClipService.ACTION_SEND_CURRENT) },
                    onSendFile = { chooseFiles.launch(arrayOf("*/*")) },
                    onCancelTransfer = { id ->
                        ContextCompat.startForegroundService(this, Intent(this, BinderClipService::class.java)
                            .setAction(BinderClipService.ACTION_CANCEL_TRANSFER)
                            .putExtra(BinderClipService.EXTRA_TRANSFER_ID, id))
                    },
                    onCopy = { startService(BinderClipService.ACTION_COPY_PENDING) },
                    onReconnect = { startService(BinderClipService.ACTION_SEARCH_RECONNECT) },
                    onToggleBtFallback = { enabled ->
                        startService(
                            BinderClipService.ACTION_SET_BT_FALLBACK,
                            enabled = enabled
                        )
                    },
                    onToggleAutostart = { enabled ->
                        startService(
                            BinderClipService.ACTION_SET_AUTOSTART,
                            enabled = enabled
                        )
                    },
                    onRequestBluetooth = {
                        if (Build.VERSION.SDK_INT >= 31) {
                            val perms = arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN
                            )
                            val anyRationale = perms.any { shouldShowRequestPermissionRationale(it) }
                            if (anyRationale) {
                                showBluetoothGuide = true
                            } else if (bluetoothGranted) {
                                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            } else {
                                requestBluetooth.launch(perms)
                            }
                        } else {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    },
                    onRemove = { id -> startService(BinderClipService.ACTION_REMOVE_MEMBER, memberId = id) },
                    onUpdateDeviceName = { memberId, name ->
                        startService(
                            BinderClipService.ACTION_UPDATE_DEVICE_NAME,
                            memberId = memberId,
                            deviceName = name
                        )
                    },
                    onRefresh = { startService(BinderClipService.ACTION_SEARCH_RECONNECT) },
                )
                if (showBluetoothGuide) {
                    AlertDialog(
                        onDismissRequest = { showBluetoothGuide = false },
                        title = { Text(stringResource(R.string.bt_guide_dialog_title)) },
                        text = {
                            Text(stringResource(R.string.bt_guide_dialog_text))
                        },
                        confirmButton = {
                            Button(onClick = {
                                showBluetoothGuide = false
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                                startActivity(intent)
                            }) {
                                Text(stringResource(R.string.open_settings))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBluetoothGuide = false }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionRevision += 1
        ShizukuAccess.refresh()
        startService(BinderClipService.ACTION_REFRESH_CAPABILITIES)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.takeIf { it.startsWith(AppProfile.current.pairingScheme + "://") }?.let(::pair)
    }

    private fun scan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            qrScanner.launch(
                io.github.g00fy2.quickie.config.ScannerConfig.build {
                    setBarcodeFormats(listOf(io.github.g00fy2.quickie.config.BarcodeFormat.FORMAT_QR_CODE))
                    setShowTorchToggle(true)
                    setShowCloseButton(true)
                    setKeepScreenOn(true)
                }
            )
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun pair(uri: String) {
        android.util.Log.i("BinderClip", "Pairing invitation received")
        val macName = SyncProtocol.parsePairingUrl(uri)?.deviceName?.takeIf { it.isNotBlank() }
        Toast.makeText(this, macName ?: "Connecting…", Toast.LENGTH_SHORT).show()
        ContextCompat.startForegroundService(
            this,
            Intent(this, BinderClipService::class.java).setAction(BinderClipService.ACTION_PAIR)
                .putExtra(BinderClipService.EXTRA_URI, uri)
        )
    }

    private fun startService(
        action: String,
        visible: Boolean? = null,
        enabled: Boolean? = null,
        hidden: Boolean? = null,
        memberId: String? = null,
        deviceName: String? = null,
    ) {
        ContextCompat.startForegroundService(this, Intent(this, BinderClipService::class.java).setAction(action).also {
            if (visible != null) it.putExtra("visible", visible)
            if (enabled != null) it.putExtra("enabled", enabled)
            if (hidden != null) it.putExtra("hidden", hidden)
            if (memberId != null) it.putExtra(BinderClipService.EXTRA_MEMBER_ID, memberId)
            if (deviceName != null) it.putExtra(BinderClipService.EXTRA_DEVICE_NAME, deviceName)
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
internal fun BinderClipScreen(
    state: AppState,
    notificationsGranted: Boolean,
    bluetoothGranted: Boolean,
    permissionRevision: Int,
    onScan: () -> Unit,
    onRequestNotifications: () -> Unit,
    onSend: () -> Unit,
    onSendFile: () -> Unit,
    onCancelTransfer: (String) -> Unit,
    onCopy: () -> Unit,
    onReconnect: () -> Unit,
    onToggleBtFallback: (Boolean) -> Unit,
    onToggleAutostart: (Boolean) -> Unit = {},
    onRequestBluetooth: () -> Unit,
    onRemove: (String) -> Unit,
    onUpdateDeviceName: (String?, String?) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedDevice by remember { mutableStateOf<RememberedPeer?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    val diagnosticEvents by DiagnosticLog.events.collectAsState()
    val devices = listOfNotNull(state.peer)
    permissionRevision.hashCode()

    val missingPermissions = buildList {
        if (!notificationsGranted) add(
            PermissionNeed(
                context.getString(R.string.perm_notifications),
                context.getString(R.string.perm_notifications_desc),
                Icons.Outlined.Notifications,
                onRequestNotifications
            )
        )
        if (!bluetoothGranted) add(
            PermissionNeed(
                context.getString(R.string.perm_bluetooth),
                context.getString(R.string.perm_bluetooth_desc),
                Icons.Outlined.Settings,
                onRequestBluetooth
            )
        )
    }

    LaunchedEffect(bluetoothGranted, state.btFallbackEnabled) {
        if (state.btFallbackEnabled && !bluetoothGranted) onToggleBtFallback(false)
    }
    var visibleTransfer by remember { mutableStateOf<TransferProgress?>(null) }
    var hiddenTransferId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.transferRevision, hiddenTransferId) {
        if (state.transferProgress?.transferId != null && state.transferProgress.transferId == hiddenTransferId) {
            visibleTransfer = null
            return@LaunchedEffect
        }
        if (state.transferProgress?.isSuccess == true) {
            val remaining = 3000 - (android.os.SystemClock.elapsedRealtime() - state.transferUpdatedAtMs)
            visibleTransfer = state.transferProgress.takeIf { remaining > 0 }
            if (remaining > 0) delay(remaining)
            visibleTransfer = null
        } else visibleTransfer = state.transferProgress
    }
    val updater = remember { AppUpdater(context) }
    var availableUpdate by remember { mutableStateOf<AppRelease?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadPercent by remember { mutableIntStateOf(0) }
    val updateScope = rememberCoroutineScope()

    fun checkUpdates(manual: Boolean = false) {
        updateScope.launch {
            val release = updater.checkForUpdate()
            if (release != null) {
                availableUpdate = release
            } else if (manual) {
                Toast.makeText(context, context.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME), Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        checkUpdates(manual = false)
    }

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
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.more_options)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.received_files)) },
                            leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                context.startActivity(Intent(context, ReceivedFilesActivity::class.java))
                            }
                        )
                        if (state.peer != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scan_qr_to_pair)) },
                                leadingIcon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onScan()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.show_logs)) },
                            leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                showLogs = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.update_check)) },
                            leadingIcon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                checkUpdates(manual = true)
                            }
                        )
                    }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
        ) {
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

                if (devices.isEmpty()) {
                    item {
                        EmptyPairedDevices(onScan = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onScan()
                        })
                    }
                } else {
                    items(devices.size, key = { devices[it].deviceId }) { index ->
                        val device = devices[index]
                        DeviceCard(
                            device = device,
                            phase = state.connectionPhase,
                            transportType = state.transportType,
                            onSendFile = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSendFile()
                            },
                            onSendClipboard = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSend()
                            },
                            onManage = { selectedDevice = device }
                        )
                    }
                }

                val isBackgroundActive = state.shizukuCaptureActive || (state.rootAvailable && state.automaticClipboardEnabled)
                if (!isBackgroundActive && state.peer != null) {
                    item {
                        ClipboardCaptureSetup(state)
                    }
                }

                // Pending clipboard ready to copy
                if (state.pendingText || state.pendingImage) {
                    item {
                        ListItem(
                            headlineContent = {
                                Text(if (state.pendingImage) stringResource(R.string.image_ready_to_copy) else stringResource(R.string.text_ready_to_copy))
                            },
                            trailingContent = {
                                TextButton(onClick = onCopy) { Text(stringResource(R.string.copy)) }
                            },
                        )
                    }
                }

                // Permissions
                if (missingPermissions.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.section_permissions)) }
                    items(missingPermissions.size, key = { missingPermissions[it].title }) { index ->
                        PermissionRow(missingPermissions[index])
                        if (index != missingPermissions.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                    }
                }

                // Settings: Bluetooth fallback and Autostart
                item { SectionTitle(stringResource(R.string.section_settings)) }
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
                item {
                    PreferenceToggle(
                        title = stringResource(R.string.perm_autostart),
                        summary = stringResource(R.string.perm_autostart_desc),
                        checked = state.autostartEnabled,
                        enabled = true,
                        onChanged = onToggleAutostart,
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
            }

            PullRefreshIndicator(
                refreshing = refreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            visibleTransfer?.takeUnless { it.transferId != null && it.transferId == hiddenTransferId }?.let { progress ->
                TransferStatusOverlay(
                    progress = progress,
                    onDismiss = {
                        if (progress.isBusy || progress.canCancel) hiddenTransferId = progress.transferId
                        visibleTransfer = null
                    },
                    onCancel = { progress.transferId?.let(onCancelTransfer) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .widthIn(max = 560.dp),
                )
            }
        }
    }

    // ModalBottomSheet for paired device options (eliminates nested alert dialogs)
    selectedDevice?.let { target ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedDevice = null },
            sheetState = sheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            DeviceDetailsBottomSheet(
                device = target,
                state = state,
                onRename = { newName ->
                    onUpdateDeviceName(target.deviceId, newName)
                    selectedDevice = null
                },
                onUnpair = {
                    onRemove(target.deviceId)
                    selectedDevice = null
                },
            )
        }
    }

    if (showLogs) {
        var logQuery by remember { mutableStateOf("") }
        var selectedFilter by remember { mutableStateOf<DiagnosticLevel?>(null) }
        var confirmClearLogs by remember { mutableStateOf(false) }
        val filteredEvents = remember(diagnosticEvents, logQuery, selectedFilter) {
            diagnosticEvents.filter { event ->
                (selectedFilter == null || event.level == selectedFilter) &&
                        (logQuery.isBlank() || event.message.contains(logQuery, ignoreCase = true))
            }
        }
        val displayedEvents = remember(filteredEvents) { filteredEvents.takeLast(500).asReversed() }
        val logScroll = rememberScrollState()
        if (confirmClearLogs) {
            AlertDialog(
                onDismissRequest = { confirmClearLogs = false },
                title = { Text(stringResource(R.string.clear_logs_confirm_title)) },
                text = { Text(stringResource(R.string.clear_logs_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        DiagnosticLog.clear()
                        confirmClearLogs = false
                    }) { Text(stringResource(R.string.clear)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearLogs = false }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.logs_title, filteredEvents.size))
                    IconButton(onClick = { confirmClearLogs = true }) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.clear),
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = logQuery,
                        onValueChange = { logQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.logs_search_placeholder), style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = "Search") },
                        trailingIcon = {
                            if (logQuery.isNotEmpty()) {
                                IconButton(onClick = { logQuery = "" }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilterChip(
                            selected = selectedFilter == null,
                            onClick = { selectedFilter = null },
                            label = { Text(stringResource(R.string.filter_all)) },
                        )
                        FilterChip(
                            selected = selectedFilter == DiagnosticLevel.Info,
                            onClick = {
                                selectedFilter =
                                    if (selectedFilter == DiagnosticLevel.Info) null else DiagnosticLevel.Info
                            },
                            label = { Text(stringResource(R.string.filter_info)) },
                        )
                        FilterChip(
                            selected = selectedFilter == DiagnosticLevel.Warning,
                            onClick = {
                                selectedFilter =
                                    if (selectedFilter == DiagnosticLevel.Warning) null else DiagnosticLevel.Warning
                            },
                            label = { Text(stringResource(R.string.filter_warning)) },
                        )
                        FilterChip(
                            selected = selectedFilter == DiagnosticLevel.Error,
                            onClick = {
                                selectedFilter =
                                    if (selectedFilter == DiagnosticLevel.Error) null else DiagnosticLevel.Error
                            },
                            label = { Text(stringResource(R.string.filter_error)) },
                        )
                    }
                    if (filteredEvents.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (logQuery.isBlank()) stringResource(R.string.no_events_yet) else stringResource(R.string.no_matching_events),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp)
                                .verticalScroll(logScroll),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            displayedEvents.forEach { event ->
                                Text(
                                    "${
                                        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.timestamp))
                                    } - ${event.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (event.level) {
                                        DiagnosticLevel.Error -> MaterialTheme.colorScheme.error
                                        DiagnosticLevel.Warning -> MaterialTheme.colorScheme.onSurfaceVariant
                                        DiagnosticLevel.Info -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLogs = false }) { Text(stringResource(R.string.close)) } },
        )
    }
    availableUpdate?.let { release ->
        AlertDialog(
            onDismissRequest = {
                if (!isDownloadingUpdate) availableUpdate = null
            },
            icon = {
                Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            title = {
                Text(stringResource(R.string.update_available))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isDownloadingUpdate) {
                        Text(stringResource(R.string.update_downloading, downloadPercent))
                        LinearProgressIndicator(
                            progress = { downloadPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(stringResource(R.string.update_message, release.versionName))
                        release.releaseNotes?.let { notes ->
                            Text(
                                notes,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!isDownloadingUpdate) {
                    TextButton(onClick = {
                        updateScope.launch {
                            if (!updater.canRequestPackageInstalls()) {
                                updater.requestInstallPermissionIntent()?.let { context.startActivity(it) }
                                return@launch
                            }
                            isDownloadingUpdate = true
                            val file = updater.downloadApk(release) { percent ->
                                downloadPercent = percent
                            }
                            isDownloadingUpdate = false
                            if (file != null && file.exists()) {
                                availableUpdate = null
                                updater.installApk(file)
                            } else {
                                Toast.makeText(context, context.getString(R.string.update_error), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Text(stringResource(R.string.update_button))
                    }
                }
            },
            dismissButton = {
                if (!isDownloadingUpdate) {
                    TextButton(onClick = { availableUpdate = null }) {
                        Text(stringResource(R.string.update_later))
                    }
                }
            },
        )
    }
}

@Composable
private fun DeviceCard(
    device: RememberedPeer,
    phase: ConnectionPhase,
    transportType: TransportType,
    onSendFile: () -> Unit,
    onSendClipboard: () -> Unit,
    onManage: () -> Unit,
) {
    val isConnected = device.connected
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                if (device.platform == "macOS") Icons.Outlined.LaptopMac else Icons.Outlined.Android,
                                contentDescription = null,
                                tint = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isConnected) Color(0xFF2E7D32)
                                        else if (phase == ConnectionPhase.Connecting || phase == ConnectionPhase.Reconnecting) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                            )
                            val statusText = when {
                                isConnected && transportType == TransportType.LAN -> stringResource(R.string.status_connected) + " (LAN)"
                                isConnected && transportType == TransportType.MESH -> stringResource(R.string.status_connected) + " (Mesh)"
                                isConnected && transportType == TransportType.BLUETOOTH -> stringResource(R.string.status_connected) + " (Bluetooth)"
                                isConnected -> stringResource(R.string.status_connected)
                                phase == ConnectionPhase.Connecting -> stringResource(R.string.status_connecting)
                                phase == ConnectionPhase.Reconnecting -> stringResource(R.string.status_reconnecting)
                                else -> stringResource(R.string.offline)
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                IconButton(onClick = onManage) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.device_details),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Compact Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onSendFile,
                    enabled = isConnected && transportType != TransportType.BLUETOOTH,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send_file), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(
                    onClick = onSendClipboard,
                    enabled = isConnected,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send_current_clipboard), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (isConnected && transportType == TransportType.BLUETOOTH) {
                Text(
                    stringResource(R.string.file_connection_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DeviceDetailsBottomSheet(
    device: RememberedPeer,
    state: AppState,
    onRename: (String) -> Unit,
    onUnpair: () -> Unit,
) {
    var renameInput by remember { mutableStateOf(device.name) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Device Title Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        if (device.platform == "macOS") Icons.Outlined.LaptopMac else Icons.Outlined.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (device.connected) stringResource(R.string.status_connected) else stringResource(R.string.offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        // Connection Details block
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.device_details),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            val transportLabel = if (device.connected) {
                when (state.transportType) {
                    TransportType.BLUETOOTH -> stringResource(R.string.status_connected_bluetooth)
                    TransportType.MESH -> stringResource(R.string.status_connected_mesh)
                    TransportType.LAN -> stringResource(R.string.status_connected_lan)
                    TransportType.NONE -> stringResource(R.string.status_connected)
                }
            } else stringResource(R.string.offline)
            Text(
                transportLabel,
                style = MaterialTheme.typography.bodyMedium,
            )
            val ipText = if (device.connected && state.transportType == TransportType.BLUETOOTH) {
                stringResource(R.string.transport_bt_label)
            } else {
                stringResource(R.string.ip_label, device.host.takeIf { it.isNotBlank() } ?: stringResource(R.string.ip_unavailable))
            }
            Text(
                ipText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        // Inline Rename
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = renameInput,
                onValueChange = { renameInput = it },
                label = { Text(stringResource(R.string.device_rename_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (renameInput.isNotBlank() && renameInput.trim() != device.name) {
                Button(
                    onClick = { onRename(renameInput.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }

        // Direct Unpair action
        OutlinedButton(
            onClick = onUnpair,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.unpair_device))
        }
    }
}

private data class PermissionNeed(
    val title: String,
    val summary: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
    val actionLabel: String = "Allow",
)

@Composable
private fun PermissionRow(need: PermissionNeed) = ListItem(
    headlineContent = { Text(need.title) },
    supportingContent = { Text(need.summary) },
    leadingContent = { Icon(need.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
    trailingContent = { TextButton(onClick = need.onClick) { Text(need.actionLabel) } },
)

@Composable
private fun EmptyPairedDevices(onScan: () -> Unit) = Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.no_devices_paired)) },
        supportingContent = { Text(stringResource(R.string.no_devices_paired_desc)) },
    )
    OutlinedButton(
        onClick = onScan,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.scan_qr_to_pair))
    }
}

@Composable
private fun SectionTitle(text: String, topPadding: androidx.compose.ui.unit.Dp = 14.dp) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = topPadding, bottom = 2.dp),
)

@Composable
private fun PreferenceToggle(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChanged: (Boolean) -> Unit,
) = ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(summary) },
    trailingContent = { Switch(checked = checked, onCheckedChange = onChanged, enabled = enabled) },
)
