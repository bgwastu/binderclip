package net.wastu.binderclip

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private var permissionRevision by mutableIntStateOf(0)
    private val qrScanner = registerForActivityResult(io.github.g00fy2.quickie.ScanCustomCode()) { result ->
        when (result) {
            is io.github.g00fy2.quickie.QRResult.QRSuccess -> {
                val uri = result.content.rawValue
                android.util.Log.i("BinderClip", "QR Scanned successfully: $uri")
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
        super.onCreate(savedInstanceState); DiagnosticLog.initialize(this); startService(BinderClipService.ACTION_START)
        intent?.dataString?.takeIf { it.startsWith("binderclip://") }?.let(::pair)
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
                val power = getSystemService(PowerManager::class.java)
                val batteryOptimizationIgnored =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || power.isIgnoringBatteryOptimizations(packageName)
                val backgroundRestricted =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && getSystemService(ActivityManager::class.java).isBackgroundRestricted
                val autoStartHelpNeeded = !getSharedPreferences("binderclip", MODE_PRIVATE).getBoolean(
                    "auto_start_help_seen",
                    false
                ) || backgroundRestricted
                DisposableEffect(Unit) {
                    startService(BinderClipService.ACTION_UI_VISIBLE, visible = true)
                    onDispose { startService(BinderClipService.ACTION_UI_VISIBLE, visible = false) }
                }
                BinderClipScreen(
                    state = state,
                    notificationsGranted = notificationsGranted,
                    bluetoothGranted = bluetoothGranted,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    autoStartHelpNeeded = autoStartHelpNeeded,
                    permissionRevision = revision,
                    onScan = ::scan,
                    onRequestNotifications = { if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onRequestBatteryOptimization = ::requestBatteryOptimization,
                    onOpenAppDetails = ::openAutoStartSettings,
                    onSend = { startService(BinderClipService.ACTION_SEND_CURRENT) },
                    onCopy = { startService(BinderClipService.ACTION_COPY_PENDING) },
                    onReconnect = { startService(BinderClipService.ACTION_SEARCH_RECONNECT) },
                    onToggleRoot = { enabled ->
                        startService(
                            BinderClipService.ACTION_TOGGLE_ROOT_AUTOMATION,
                            enabled = enabled
                        )
                    },
                    onToggleBtFallback = { enabled ->
                        startService(
                            BinderClipService.ACTION_SET_BT_FALLBACK,
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
                                // Permission already granted; radio is just off.
                                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            } else {
                                requestBluetooth.launch(perms)
                            }
                        } else {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    },
                    onDisableAccessibility = { startService(BinderClipService.ACTION_DISABLE_ACCESSIBILITY) },
                    onRequestShizuku = { ShizukuClipboardBridge.requestPermission(this@MainActivity) },
                    onEnableShizuku = {
                        startService(BinderClipService.ACTION_TOGGLE_SHIZUKU_AUTOMATION, enabled = true)
                    },
                    onToggleAutoApplyIncoming = { enabled ->
                        startService(BinderClipService.ACTION_SET_AUTO_APPLY_INCOMING, enabled = enabled)
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
        startService(BinderClipService.ACTION_REFRESH_CAPABILITIES)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); intent.dataString?.takeIf { it.startsWith("binderclip://") }
            ?.let(::pair)
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
        android.util.Log.i("BinderClip", "Pairing with URI: $uri")
        val macName = SyncProtocol.parsePairingUrl(uri)?.deviceName?.takeIf { it.isNotBlank() }
        Toast.makeText(this, macName ?: "Connecting…", Toast.LENGTH_SHORT).show()
        ContextCompat.startForegroundService(
            this,
            Intent(this, BinderClipService::class.java).setAction(BinderClipService.ACTION_PAIR)
                .putExtra(BinderClipService.EXTRA_URI, uri)
        )
    }

    private fun requestBatteryOptimization() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(request) }.getOrElse {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun openAutoStartSettings() {
        getSharedPreferences("binderclip", MODE_PRIVATE).edit().putBoolean("auto_start_help_seen", true).apply()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val candidates = mutableListOf<Intent>()
        when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                    )
                )
            }

            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> {
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                        )
                    )
                )
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.oppo.safe",
                            "com.oppo.safe.permission.startup.StartupAppListActivity"
                        )
                    )
                )
            }

            manufacturer.contains("vivo") -> {
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                        )
                    )
                )
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                        )
                    )
                )
            }

            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                        )
                    )
                )
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.optimize.process.ProtectActivity"
                        )
                    )
                )
            }
        }
        for (intent in candidates) {
            if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                runCatching { startActivity(intent); return }
            }
        }
        // Fallback: standard App Info page
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
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
private fun BinderClipScreen(
    state: AppState,
    notificationsGranted: Boolean,
    bluetoothGranted: Boolean,
    batteryOptimizationIgnored: Boolean,
    autoStartHelpNeeded: Boolean,
    permissionRevision: Int,
    onScan: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onSend: () -> Unit,
    onCopy: () -> Unit,
    onReconnect: () -> Unit,
    onToggleRoot: (Boolean) -> Unit,
    onToggleBtFallback: (Boolean) -> Unit,
    onRequestBluetooth: () -> Unit,
    onDisableAccessibility: () -> Unit,
    onRequestShizuku: () -> Unit,
    onEnableShizuku: () -> Unit,
    onToggleAutoApplyIncoming: (Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onUpdateDeviceName: (String?, String?) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    val diagnosticEvents by DiagnosticLog.events.collectAsState()
    val devices = buildList {
        // Exclude synthetic alternate-host entries (used only as reconnect
        // candidates) so they don't appear as duplicate devices in the list.
        state.peer?.let(::add)
        addAll(state.members.filter { !it.deviceId.contains('@') })
        if (state.peer != null && none { it.deviceId == state.localDeviceId }) add(
            RememberedPeer(
                state.localDeviceName.ifBlank { DeviceNames.android(context) },
                localIpAddress(context),
                39_421,
                state.localDeviceId,
                "Android",
                true
            )
        )
    }.distinctBy { it.deviceId }
        .map { device ->
            if (device.deviceId == state.localDeviceId) device.copy(
                name = state.localDeviceName.ifBlank { DeviceNames.android(context) },
                host = localIpAddress(context),
                platform = "Android"
            ) else device
        }
        .sortedWith(compareByDescending<RememberedPeer> { it.deviceId == state.localDeviceId }.thenBy { it.name.lowercase() })
    // Read so the composition updates immediately after Android's permission result.
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
        if (!state.rootAvailable && !state.backgroundAccessGranted && !state.accessibilityEnabled) add(
            PermissionNeed(
                context.getString(R.string.perm_bg_keepalive),
                context.getString(R.string.perm_bg_keepalive_desc),
                Icons.Outlined.AccessibilityNew,
                onOpenAccessibility
            )
        )
        if (state.btFallbackEnabled && !bluetoothGranted) add(
            PermissionNeed(
                context.getString(R.string.perm_bluetooth),
                context.getString(R.string.perm_bluetooth_desc),
                Icons.Outlined.Settings,
                onRequestBluetooth
            )
        )
        if (!batteryOptimizationIgnored) add(
            PermissionNeed(
                context.getString(R.string.perm_battery_opt),
                context.getString(R.string.perm_battery_opt_desc),
                Icons.Outlined.BatteryChargingFull,
                onRequestBatteryOptimization
            )
        )
        if (autoStartHelpNeeded) add(
            PermissionNeed(
                context.getString(R.string.perm_autostart),
                context.getString(R.string.perm_autostart_desc),
                Icons.Outlined.Settings,
                onOpenAppDetails,
                context.getString(R.string.open)
            )
        )
    }
    var sentOverlayMessage by remember { mutableStateOf<String?>(null) }
    var sentOverlayIsSuccess by remember { mutableStateOf(true) }

    LaunchedEffect(state.status) {
        if (state.status.startsWith("Sending") || state.status.startsWith("Offering") || state.status.startsWith("Opening link")) {
            sentOverlayMessage = state.status
            sentOverlayIsSuccess = false
        } else if (state.status == "Sent URL to peer" || state.status == "Image sent" || state.status.startsWith("Received") || state.status.startsWith("Opened URL")) {
            sentOverlayMessage = if (state.status == "Sent URL to peer") context.getString(R.string.url_sent_success) else if (state.status == "Image sent") context.getString(R.string.image_sent_success) else state.status
            sentOverlayIsSuccess = true
            kotlinx.coroutines.delay(2200)
            sentOverlayMessage = null
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_binder_clip),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("BinderClip", fontWeight = FontWeight.SemiBold)
                }
            },
            actions = {
                IconButton(onClick = { showLogs = true }) {
                    Icon(Icons.Outlined.Description, contentDescription = stringResource(R.string.show_logs))
                }
            },
        )
    }) { insets ->
        var refreshing by remember { mutableStateOf(false) }
        val refreshScope = rememberCoroutineScope()
        val pullRefreshState = rememberPullRefreshState(refreshing, {
            refreshing = true
            onRefresh()
            refreshScope.launch { kotlinx.coroutines.delay(1200); refreshing = false }
        })
        Box(modifier = Modifier.fillMaxSize().padding(insets)) {
            PullRefreshIndicator(
                refreshing = refreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .pullRefresh(pullRefreshState, refreshing),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    PairedDevicesHeader(phase = state.connectionPhase, onReconnect = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onReconnect()
                    })
                }
                if (devices.isEmpty()) item { EmptyPairedDevices() }
                else items(devices.size, key = { devices[it].deviceId }) { index ->
                    val device = devices[index]
                    DeviceRow(
                        device,
                        isCurrentDevice = device.deviceId == state.localDeviceId,
                        phase = state.connectionPhase,
                        transportType = state.transportType,
                        onClick = { selectedDeviceId = device.deviceId })
                }
                if (devices.isNotEmpty()) item { Spacer(Modifier.height(12.dp)) }
            item {
                FilledTonalButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onScan()
                }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(
                        Icons.Outlined.QrCodeScanner,
                        contentDescription = null
                    ); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.scan_qr_to_pair))
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
            }
            item {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSend()
                    },
                    enabled = devices.any { it.connected && it.deviceId != state.localDeviceId },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = null
                    ); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.send_current_clipboard))
                }
            }
            if (missingPermissions.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_permissions), topPadding = 16.dp) }
                items(missingPermissions.size, key = { missingPermissions[it].title }) { index ->
                    PermissionRow(missingPermissions[index])
                    if (index != missingPermissions.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                }
            }
            if (state.pendingText || state.pendingImage) item {
                ListItem(
                    headlineContent = { Text(if (state.pendingImage) stringResource(R.string.image_ready_to_copy) else stringResource(R.string.text_ready_to_copy)) },
                    trailingContent = { TextButton(onClick = onCopy) { Text(stringResource(R.string.copy)) } })
            }
            item { SectionTitle(stringResource(R.string.section_clipboard_automation), topPadding = 16.dp) }
            if (state.rootAvailable) {
                item {
                    PreferenceToggle(
                        title = stringResource(R.string.root_automation_title),
                        summary = if (state.automaticClipboardEnabled) stringResource(R.string.root_automation_granted) else stringResource(R.string.root_automation_prompt),
                        checked = state.automaticClipboardEnabled,
                        onChanged = onToggleRoot,
                    )
                }
            } else if (state.backgroundAccessGranted) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.bg_sync_active_title)) },
                        supportingContent = { Text(stringResource(R.string.bg_sync_active_desc)) },
                        leadingContent = {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    )
                }
            } else {
                if (state.shizukuAvailable) {
                    if (state.shizukuAuthorized) {
                        item {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.shizuku_enable_title)) },
                                supportingContent = { Text(stringResource(R.string.shizuku_enable_desc)) },
                                trailingContent = {
                                    Button(onClick = onEnableShizuku) { Text(stringResource(R.string.shizuku_apply_btn)) }
                                }
                            )
                        }
                    } else {
                        item {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.shizuku_auth_title)) },
                                supportingContent = { Text(stringResource(R.string.shizuku_auth_desc)) },
                                trailingContent = {
                                    Button(onClick = onRequestShizuku) { Text(stringResource(R.string.shizuku_auth_btn)) }
                                }
                            )
                        }
                    }
                } else if (state.shizukuInstalled) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.shizuku_installed_title)) },
                            supportingContent = { Text(stringResource(R.string.shizuku_installed_desc)) }
                        )
                    }
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.adb_setup_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.adb_setup_desc))
                        }
                    )
                }
            }
            item {
                PreferenceToggle(
                    title = stringResource(R.string.accessibility_keepalive_title),
                    summary = if (state.accessibilityEnabled) stringResource(R.string.accessibility_keepalive_desc_on) else stringResource(R.string.accessibility_keepalive_desc_off),
                    checked = state.accessibilityEnabled,
                    onChanged = { enabled -> if (enabled) onOpenAccessibility() else onDisableAccessibility() },
                )
            }
            item {
                Text(
                    stringResource(R.string.quick_settings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            item { SectionTitle(stringResource(R.string.section_settings), topPadding = 16.dp) }
            item {
                PreferenceToggle(
                    title = stringResource(R.string.auto_apply_incoming_title),
                    summary = if (state.autoApplyIncoming) stringResource(R.string.auto_apply_incoming_desc_on) else stringResource(R.string.auto_apply_incoming_desc_off),
                    checked = state.autoApplyIncoming,
                    onChanged = onToggleAutoApplyIncoming,
                )
            }
            item {
                PreferenceToggle(
                    title = stringResource(R.string.bt_fallback_title),
                    summary = when {
                        !state.btFallbackEnabled -> stringResource(R.string.bt_fallback_desc_off)
                        !state.bluetoothEnabled -> stringResource(R.string.bt_fallback_desc_disabled)
                        else -> stringResource(R.string.bt_fallback_desc_on)
                    },
                    checked = state.btFallbackEnabled,
                    onChanged = onToggleBtFallback,
                )
            }
            if (state.btFallbackEnabled && !state.bluetoothEnabled) item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.bt_is_off_title)) },
                    supportingContent = { Text(stringResource(R.string.bt_is_off_desc)) },
                    trailingContent = { TextButton(onClick = onRequestBluetooth) { Text(stringResource(R.string.turn_on)) } },
                )
            }
        }

            androidx.compose.animation.AnimatedVisibility(
                visible = sentOverlayMessage != null,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it / 2 }),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 2 }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            ) {
                sentOverlayMessage?.let { msg ->
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (sentOverlayIsSuccess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 6.dp,
                        tonalElevation = 4.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (sentOverlayIsSuccess) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (sentOverlayIsSuccess) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    }
                }
            }
        }
    val selectedDevice = devices.firstOrNull { it.deviceId == selectedDeviceId }
    var deviceToRename by remember { mutableStateOf<RememberedPeer?>(null) }
    var renameInput by remember { mutableStateOf("") }
    selectedDevice?.let { target ->
        val isCurrentDevice = target.deviceId == state.localDeviceId
        AlertDialog(
            onDismissRequest = { selectedDeviceId = null },
            title = { Text(target.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val statusText = if (target.connected) {
                        when (state.transportType) {
                            TransportType.BLUETOOTH -> stringResource(R.string.status_connected_bluetooth)
                            TransportType.MESH -> stringResource(R.string.status_connected_mesh)
                            TransportType.LAN -> stringResource(R.string.status_connected_lan)
                            TransportType.NONE -> stringResource(R.string.status_connected)
                        }
                    } else if (state.connectionPhase == ConnectionPhase.Connecting) stringResource(R.string.status_connecting)
                    else stringResource(R.string.status_reconnecting)
                    Text(statusText)
                    val ipText = if (target.connected && state.transportType == TransportType.BLUETOOTH) {
                        stringResource(R.string.transport_bt_label)
                    } else {
                        stringResource(R.string.ip_label, target.host.takeIf { it.isNotBlank() } ?: stringResource(R.string.ip_unavailable))
                    }
                    Text(ipText)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        renameInput = target.name
                        deviceToRename = target
                        selectedDeviceId = null
                    }) { Text(stringResource(R.string.rename)) }
                    TextButton(onClick = {
                        onRemove(target.deviceId); selectedDeviceId = null
                    }) { Text(if (isCurrentDevice) stringResource(R.string.unpair_this_device) else stringResource(R.string.unpair_device)) }
                }
            },
            dismissButton = { TextButton(onClick = { selectedDeviceId = null }) { Text(stringResource(R.string.close)) } },
        )
    }
    deviceToRename?.let { target ->
        AlertDialog(
            onDismissRequest = { deviceToRename = null },
            title = { Text(stringResource(R.string.device_rename_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.device_rename_prompt))
                    androidx.compose.material3.OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameInput.trim()
                        if (trimmed.isNotEmpty()) {
                            onUpdateDeviceName(target.deviceId, trimmed)
                        }
                        deviceToRename = null
                    },
                    enabled = renameInput.isNotBlank()
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { deviceToRename = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (showLogs) {
        var logQuery by remember { mutableStateOf("") }
        var selectedFilter by remember { mutableStateOf<DiagnosticLevel?>(null) }
        val filteredEvents = remember(diagnosticEvents, logQuery, selectedFilter) {
            diagnosticEvents.filter { event ->
                (selectedFilter == null || event.level == selectedFilter) &&
                        (logQuery.isBlank() || event.message.contains(logQuery, ignoreCase = true))
            }
        }
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text(stringResource(R.string.logs_title, filteredEvents.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.OutlinedTextField(
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.material3.FilterChip(
                            selected = selectedFilter == null,
                            onClick = { selectedFilter = null },
                            label = { Text(stringResource(R.string.filter_all)) }
                        )
                        androidx.compose.material3.FilterChip(
                            selected = selectedFilter == DiagnosticLevel.Info,
                            onClick = {
                                selectedFilter =
                                    if (selectedFilter == DiagnosticLevel.Info) null else DiagnosticLevel.Info
                            },
                            label = { Text(stringResource(R.string.filter_info)) }
                        )
                        androidx.compose.material3.FilterChip(
                            selected = selectedFilter == DiagnosticLevel.Warning,
                            onClick = {
                                selectedFilter =
                                    if (selectedFilter == DiagnosticLevel.Warning) null else DiagnosticLevel.Warning
                            },
                            label = { Text(stringResource(R.string.filter_warning)) }
                        )
                        androidx.compose.material3.FilterChip(
                            selected = selectedFilter == DiagnosticLevel.Error,
                            onClick = {
                                selectedFilter =
                                    if (selectedFilter == DiagnosticLevel.Error) null else DiagnosticLevel.Error
                            },
                            label = { Text(stringResource(R.string.filter_error)) }
                        )
                    }
                    if (filteredEvents.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (logQuery.isBlank()) stringResource(R.string.no_events_yet) else stringResource(R.string.no_matching_events),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredEvents.size, key = { filteredEvents[it].timestamp }) { index ->
                                val event = filteredEvents[filteredEvents.lastIndex - index]
                                Text(
                                    "${
                                        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.timestamp))
                                    } · ${event.message}",
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
            dismissButton = { TextButton(onClick = { DiagnosticLog.clear() }) { Text(stringResource(R.string.clear)) } },
        )
    }
}

private data class PermissionNeed(
    val title: String,
    val summary: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
    val actionLabel: String = "Allow"
)

@Composable
private fun PermissionRow(need: PermissionNeed) = ListItem(
    headlineContent = { Text(need.title) },
    supportingContent = { Text(need.summary) },
    leadingContent = { Icon(need.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
    trailingContent = { TextButton(onClick = need.onClick) { Text(need.actionLabel) } },
)

@Composable
private fun EmptyPairedDevices() =
    ListItem(headlineContent = { Text(stringResource(R.string.no_devices_paired)) }, supportingContent = { Text(stringResource(R.string.no_devices_paired_desc)) })

@Composable
private fun PairedDevicesHeader(phase: ConnectionPhase, onReconnect: () -> Unit) = Row(
    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
) {
    Text(stringResource(R.string.paired_devices), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (phase == ConnectionPhase.Connecting || phase == ConnectionPhase.Reconnecting) {
            IconButton(onClick = onReconnect, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.reconnect),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, topPadding: androidx.compose.ui.unit.Dp = 14.dp) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = topPadding, bottom = 2.dp)
)

@Composable
private fun DeviceRow(
    member: RememberedPeer,
    isCurrentDevice: Boolean,
    phase: ConnectionPhase,
    transportType: TransportType = TransportType.NONE,
    onClick: () -> Unit
) {
    val container = if (isCurrentDevice) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(MaterialTheme.shapes.medium).background(container)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).size(36.dp).clip(MaterialTheme.shapes.small)
                .background(if (isCurrentDevice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (member.platform == "macOS") Icons.Outlined.LaptopMac else Icons.Outlined.Android,
                contentDescription = if (member.platform == "macOS") "Mac" else "Android",
                tint = if (isCurrentDevice) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 48.dp).align(Alignment.CenterStart),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                member.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrentDevice) FontWeight.SemiBold else FontWeight.Normal
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(member.connected, 7.dp); Spacer(Modifier.width(8.dp))
                val statusText = when {
                    isCurrentDevice -> stringResource(R.string.this_device)
                    member.connected -> when (transportType) {
                        TransportType.BLUETOOTH -> stringResource(R.string.status_connected_bluetooth)
                        TransportType.MESH -> stringResource(R.string.status_connected_mesh)
                        TransportType.LAN -> stringResource(R.string.status_connected_lan)
                        TransportType.NONE -> stringResource(R.string.status_connected)
                    }
                    phase == ConnectionPhase.Connecting -> stringResource(R.string.status_connecting)
                    else -> stringResource(R.string.status_reconnecting)
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusDot(connected: Boolean, size: androidx.compose.ui.unit.Dp) = Box(
    Modifier.size(size).clip(MaterialTheme.shapes.extraLarge)
        .background(if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
)

@Composable
private fun PreferenceToggle(title: String, summary: String, checked: Boolean, onChanged: (Boolean) -> Unit) = ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(summary) },
    trailingContent = { Switch(checked = checked, onCheckedChange = onChanged) })

/** The current device's IP as assigned by the OS to the active network, which
 *  is correct regardless of VLAN, mesh VPN, mobile data, or any subnet. Falls
 *  back to enumerating non-loopback interfaces only if the system doesn't
 *  report an active network (e.g., in tests). */
private fun localIpAddress(context: android.content.Context): String {
    val active = runCatching {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return@runCatching ""
        val links = cm.getLinkProperties(network)?.linkAddresses ?: return@runCatching ""
        links.asSequence()
            .map { it.address }
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .map { it.hostAddress }
            .firstOrNull { !it.isNullOrBlank() }
    }.getOrDefault("")
    if (!active.isNullOrBlank()) return active

    // Fallback: enumerate non-loopback IPv4 interfaces.
    val addresses = runCatching {
        val result = mutableListOf<String>()
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
            if (networkInterface.isUp && !networkInterface.isLoopback) {
                networkInterface.inetAddresses.toList().forEach { address ->
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        val ip = address.hostAddress ?: ""
                        if (ip.isNotBlank() && !ip.startsWith("127.")) result.add(ip)
                    }
                }
            }
        }
        result
    }.getOrDefault(emptyList())
    return addresses.firstOrNull() ?: ""
}
