package net.wastu.binderclip

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class TransportType {
    LAN,
    MESH,
    BLUETOOTH,
    NONE
}

data class AppState(
    val status: String = "Not paired",
    val connectionPhase: ConnectionPhase = ConnectionPhase.NotPaired,
    val transportType: TransportType = TransportType.NONE,
    val peer: RememberedPeer? = null,
    val pendingText: Boolean = false,
    val pendingImage: Boolean = false,
    val transferStatus: String? = null,
    val members: List<RememberedPeer> = emptyList(),
    val rootAvailable: Boolean = false,
    val automaticClipboardEnabled: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuAuthorized: Boolean = false,
    val shizukuAutomationEnabled: Boolean = false,
    val imeEnabled: Boolean = false,
    val imeSelected: Boolean = false,
    val autoApplyIncoming: Boolean = true,
    val syncToastHidden: Boolean = false,
    val btFallbackEnabled: Boolean = false,
    val bluetoothActive: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val localDeviceId: String = "",
    val localDeviceName: String = "",
)

object AppRuntime {
    val state = kotlinx.coroutines.flow.MutableStateFlow(AppState())
}

/** The only Android background component: a resilient WebSocket sync foreground service. */
class BinderClipService : Service() {
    companion object {
        const val ACTION_START = "net.wastu.binderclip.START"
        const val ACTION_PAIR = "net.wastu.binderclip.PAIR"
        const val ACTION_UNPAIR = "net.wastu.binderclip.UNPAIR"
        const val ACTION_SEND_CURRENT = "net.wastu.binderclip.SEND_CURRENT"
        const val ACTION_COPY_PENDING = "net.wastu.binderclip.COPY_PENDING"
        const val ACTION_UI_VISIBLE = "net.wastu.binderclip.UI_VISIBLE"
        const val ACTION_TOGGLE_ROOT_AUTOMATION = "net.wastu.binderclip.TOGGLE_ROOT_AUTOMATION"
        const val ACTION_TOGGLE_SHIZUKU_AUTOMATION = "net.wastu.binderclip.TOGGLE_SHIZUKU_AUTOMATION"
        const val ACTION_SET_AUTO_APPLY_INCOMING = "net.wastu.binderclip.SET_AUTO_APPLY_INCOMING"
        const val ACTION_SET_SYNC_TOASTS = "net.wastu.binderclip.SET_SYNC_TOASTS"
        const val ACTION_SET_BT_FALLBACK = "net.wastu.binderclip.SET_BT_FALLBACK"
        const val ACTION_REFRESH_CAPABILITIES = "net.wastu.binderclip.REFRESH_CAPABILITIES"
        const val ACTION_DISABLE_ACCESSIBILITY = "net.wastu.binderclip.DISABLE_ACCESSIBILITY"
        const val ACTION_REMOVE_MEMBER = "net.wastu.binderclip.REMOVE_MEMBER"
        const val ACTION_UPDATE_DEVICE_NAME = "net.wastu.binderclip.UPDATE_DEVICE_NAME"
        const val ACTION_SEND_SHARED = "net.wastu.binderclip.SEND_SHARED"
        const val ACTION_SEARCH_RECONNECT = "net.wastu.binderclip.SEARCH_RECONNECT"
        const val EXTRA_MEMBER_ID = "member_id"
        const val EXTRA_TARGET_DEVICE_ID = "target_device_id"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_URI = "uri"
        private const val CHANNEL = "binderclip_sync"
        private const val URL_CHANNEL = "binderclip_urls"
        private const val NOTIFICATION_ID = 101
        /** Require the WebSocket to stay up this long before we tear down a working Bluetooth
         *  session, so a flaky tunnel cannot cause endless BT reconnect flapping. */
        private const val BT_TO_WS_GRACE_MS = 15_000L

        fun startFromBackground(context: Context) {
            DiagnosticLog.initialize(context)
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BinderClipService::class.java).setAction(ACTION_START),
                )
            }.onFailure {
                DiagnosticLog.warning("Could not start BinderClipService: ${it.message}")
            }
        }

        fun startIfPaired(context: Context) {
            if (DeviceStore(context).groupKey == null) return
            startFromBackground(context)
        }
    }

    private lateinit var store: DeviceStore
    private lateinit var client: WebSocketClient
    private lateinit var clipboard: ClipboardManager
    private lateinit var nsdManager: NsdManager
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var networkDebounceFuture: ScheduledFuture<*>? = null
    private var bluetoothLink: BluetoothLink? = null
    private var bluetoothEvaluator: ScheduledFuture<*>? = null
    @Volatile
    private var networkAvailable = false
    private var lastBtEnablePromptMs = 0L
    private var lastDeferredImageHash: String? = null
    /** Epoch ms when the WebSocket first became connected on top of an active Bluetooth session. */
    private var wsStableSinceMs = 0L
    /** True while a Bluetooth session is live, so the WS probe only runs once per BT session. */
    private var wsProbedOnThisBtSession = false
    /** Set when we tear down Bluetooth to hand off to a (stable) WebSocket. If that WebSocket
     *  later drops, re-arm Bluetooth immediately instead of waiting for the 20s stall detector. */
    private var btTornDownForWsAtMs = 0L

    @Volatile
    private var uiVisible = false
    private var lastSeenHash: String? = null
    private var pendingImage: ImagePayload? = null
    private var transferStatus: String? = null
    private var lastError: String? = null
    private var pairingHint: String? = null

    @Volatile
    private var rootAvailable = false

    @Volatile
    private var automaticClipboardEnabled = false

    @Volatile
    private var shizukuAutomationEnabled = false

    private var backgroundPoll: ScheduledFuture<*>? = null
    private var backgroundFingerprint: String? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    client.setInteractive(true)
                    bluetoothLink?.setInteractive(true)
                    if (automaticClipboardEnabled || shizukuAutomationEnabled) startBackgroundPolling()
                    if (store.groupKey != null && !client.isConnected()) requestConnectResettingBackoff("screen_on")
                }
                Intent.ACTION_SCREEN_OFF -> {
                    client.setInteractive(false)
                    bluetoothLink?.setInteractive(false)
                    stopBackgroundPolling()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.initialize(this)
        store = DeviceStore(this)
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ImageClipboard.clearStale(this)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        createChannel()
        enterForeground("Starting BinderClip…")

        client = WebSocketClient(
            store = store,
            deviceNameProvider = { DeviceNames.android(this) },
            onText = ::receiveText,
            onOpenUrl = ::receiveOpenUrl,
            onImage = ::receiveImage,
            onTransferStatus = ::updateTransferStatus,
            onStatus = ::updateStatus,
            onFailure = ::reportFailure,
            onPeerIdentity = { id, name ->
                store.peer = store.peer?.copy(name = name, deviceId = id)
                refreshNsdDiscovery()
                publishState()
            },
            onRosterChanged = { publishState() },
            onDisconnected = {
                // If the WebSocket just dropped and we had handed the session to it after tearing
                // down Bluetooth, bring Bluetooth back immediately — the tunnel was flaky and BT is
                // the reliable fallback. Fastest re-arm wins over the 20s stall detector.
                if (store.groupKey != null && btTornDownForWsAtMs > 0L) {
                    btTornDownForWsAtMs = 0L
                    executor.execute {
                        // The tunnel we switched to just died; re-arm Bluetooth immediately.
                        bluetoothLink?.takeIf { store.isBtFallbackEnabled() && !it.isConnected() && !it.isListening() }?.startListening()
                    }
                }
                refreshNsdDiscovery()
                publishState()
            },
            onUnpaired = {
                lastError = null
                pairingHint = null
                store.unpair()
                stopNsdDiscovery()
                bluetoothLink?.stop()
                DiagnosticLog.info("Mac unpaired this phone")
                executor.execute { RootClipboardBridge.syncKeepAlive(this, paired = false) }
                publishState()
            },
        )
        val power = getSystemService(PowerManager::class.java)
        client.setInteractive(power?.isInteractive != false)

        bluetoothLink = BluetoothLink(this, store, { DeviceNames.android(this) }, object : BluetoothLink.Callbacks {
            override fun onAuthenticated(remoteId: String, remoteName: String) {
                store.peer = (store.peer ?: RememberedPeer("Mac", "bluetooth", 0, remoteId, "macOS", false))
                    .copy(name = remoteName.ifBlank { store.peer?.name ?: "Mac" }, deviceId = remoteId, connected = true)
                // Bluetooth is live; stop the WebSocket racer from fighting it while we probe for
                // a better path. New BT session -> allow one WS probe.
                wsStableSinceMs = 0L
                wsProbedOnThisBtSession = false
                btTornDownForWsAtMs = 0L
                client.setReconnectSuppressed(true)
                publishState()
            }

            override fun onEndpoints(endpoints: List<String>) {
                executor.execute {
                    val merged = SyncProtocol.mergeAdvertisedEndpoints(store.peerCandidates, endpoints)
                    if (merged != store.peerCandidates) {
                        store.peerCandidates = merged
                        DiagnosticLog.info("Mac endpoints from Bluetooth: ${merged.joinToString()}")
                    }
                    // BLE handed us the live mesh/LAN endpoints. Probe the faster WebSocket path
                    // once per BT session (mesh-first) so a flaky tunnel doesn't tear down and
                    // re-establish Bluetooth in an endless loop. Bluetooth stays as fallback.
                    if (!client.isConnected() && !wsProbedOnThisBtSession) {
                        wsProbedOnThisBtSession = true
                        client.forceReconnect()
                    }
                }
            }

            override fun onLinkDown(reason: String) {
                if (store.groupKey != null) DiagnosticLog.info("Bluetooth link down ($reason)")
                client.setReconnectSuppressed(false)
                refreshNsdDiscovery()
                publishState()
            }

            override fun onText(text: String) = receiveText(text)
            override fun onOpenUrl(url: String) = receiveOpenUrl(url)

            override fun onUnpair() {
                executor.execute {
                    lastError = null
                    pairingHint = null
                    store.unpair()
                    stopNsdDiscovery()
                    bluetoothLink?.stop()
                    client.close()
                    DiagnosticLog.info("Mac unpaired this phone (bluetooth)")
                    executor.execute { RootClipboardBridge.syncKeepAlive(this@BinderClipService, paired = false) }
                    publishState()
                }
            }
        })

        clipboard.addPrimaryClipChangedListener {
            if (uiVisible) executor.execute(::sendCurrentClipboard)
        }
        AccessibilityClipboardBridge.onClipboard = { payload ->
            executor.execute { sendAccessibilityClipboard(payload) }
        }
        AccessibilityClipboardBridge.onAvailabilityChanged = { executor.execute(::publishState) }
        ImeBridge.onAvailabilityChanged = { executor.execute(::publishState) }
        ShizukuClipboardBridge.onAvailabilityChanged = {
            executor.execute {
                val hasPerm = ShizukuClipboardBridge.hasPermission()
                shizukuAutomationEnabled = hasPerm && store.isShizukuClipboardAutomationEnabled()
                if (shizukuAutomationEnabled && hasPerm) {
                    ShizukuClipboardBridge.enablePrivileges(this@BinderClipService)
                }
                if (automaticClipboardEnabled || shizukuAutomationEnabled) {
                    startBackgroundPolling()
                } else {
                    stopBackgroundPolling()
                }
                publishState()
            }
        }

        registerNetworkCallback()
        updateNetworkFlag()
        refreshNsdDiscovery()
        bluetoothEvaluator = reconnectExecutor.scheduleWithFixedDelay(::evaluateBluetooth, 5_000, 5_000, TimeUnit.MILLISECONDS)

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, screenFilter)

        executor.execute {
            rootAvailable = RootClipboardBridge.isAvailable()
            automaticClipboardEnabled = rootAvailable && store.isRootClipboardAutomationEnabled() &&
                    RootClipboardBridge.enableBackgroundAccess(this)
            shizukuAutomationEnabled = ShizukuClipboardBridge.hasPermission() && store.isShizukuClipboardAutomationEnabled()
            if (shizukuAutomationEnabled) {
                ShizukuClipboardBridge.enablePrivileges(this)
            }
            if (store.groupKey != null) {
                client.connect()
            }
            RootClipboardBridge.syncKeepAlive(this, store.groupKey != null)
            if (automaticClipboardEnabled || shizukuAutomationEnabled) startBackgroundPolling()
            publishState()
        }
        publishState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_PAIR -> intent?.getStringExtra(EXTRA_URI)?.let { uri ->
                executor.execute {
                    runCatching { client.pair(uri) }
                        .onSuccess { RootClipboardBridge.syncKeepAlive(this, store.groupKey != null) }
                        .onFailure { reportFailure(it.message ?: "Pairing failed") }
                }
            }

            ACTION_UNPAIR -> executor.execute(::unpair)

            ACTION_SEND_CURRENT -> executor.execute { sendCurrentClipboard(userInitiated = true) }

            ACTION_SEARCH_RECONNECT -> resetReconnectBackoffAndTrigger("user_reconnect")

            ACTION_SEND_SHARED -> {
                val targetDeviceId = intent?.getStringExtra(EXTRA_TARGET_DEVICE_ID)
                executor.execute {
                    val shared = SharedPayloadCache.value.also { SharedPayloadCache.value = null }
                    Log.i("BinderClip", "ACTION_SEND_SHARED: shared=$shared, isConnected=${client.isConnected()}")
                    when (shared) {
                        is SharedPayload.Image -> sendSharedImage(shared.value)
                        is SharedPayload.Text -> {
                            val trimmed = shared.value.trim()
                            val isUrl = isWebUrl(trimmed)
                            if (!isUrl) applyText(shared.value)
                            if (isUrl) {
                                if (client.isConnected()) client.sendOpenUrl(trimmed, targetDeviceId)
                                else bluetoothLink?.takeIf { it.isConnected() }?.sendOpenUrl(trimmed)
                            } else {
                                if (client.isConnected()) client.sendText(shared.value)
                                else bluetoothLink?.takeIf { it.isConnected() }?.sendClipboard(shared.value)
                            }
                        }
                        null -> reportFailure("Nothing to share")
                    }
                }
            }

            ACTION_COPY_PENDING -> {
                store.pendingText?.let { text ->
                    applyText(text)
                    store.pendingText = null
                    syncToast("Copied text")
                }
                pendingImage?.let { image ->
                    applyImage(image)
                    pendingImage = null
                    syncToast("Copied image")
                }
                publishState()
            }

            ACTION_UI_VISIBLE -> {
                uiVisible = intent?.getBooleanExtra("visible", false) ?: false
                if (uiVisible && !client.isConnected() && store.groupKey != null) {
                    requestConnectResettingBackoff("ui_visible")
                }
            }

            ACTION_TOGGLE_ROOT_AUTOMATION -> executor.execute {
                val enabled = intent?.getBooleanExtra("enabled", false) ?: false
                rootAvailable = RootClipboardBridge.isAvailable()
                automaticClipboardEnabled = enabled && rootAvailable && RootClipboardBridge.enableBackgroundAccess(this)
                store.setRootClipboardAutomationEnabled(automaticClipboardEnabled)
                if (automaticClipboardEnabled || shizukuAutomationEnabled) startBackgroundPolling() else {
                    stopBackgroundPolling()
                    RootClipboardBridge.revokeBackgroundAccess(this)
                }
                RootClipboardBridge.syncKeepAlive(this, store.groupKey != null)
                publishState()
                toast(
                    when {
                        automaticClipboardEnabled -> "Automatic sync on"
                        enabled -> "Allow root access, then try again"
                        else -> "Automatic sync off"
                    }
                )
            }

            ACTION_TOGGLE_SHIZUKU_AUTOMATION -> executor.execute {
                val enabled = intent?.getBooleanExtra("enabled", false) ?: false
                val hasPerm = ShizukuClipboardBridge.hasPermission()
                if (enabled && hasPerm) {
                    ShizukuClipboardBridge.enablePrivileges(this)
                    shizukuAutomationEnabled = true
                    store.setShizukuClipboardAutomationEnabled(true)
                    startBackgroundPolling()
                    toast("Shizuku automation enabled")
                } else if (!enabled) {
                    shizukuAutomationEnabled = false
                    store.setShizukuClipboardAutomationEnabled(false)
                    if (!automaticClipboardEnabled) stopBackgroundPolling()
                    toast("Shizuku automation disabled")
                } else {
                    toast("Authorize BinderClip in Shizuku first")
                }
                publishState()
            }

            ACTION_SET_AUTO_APPLY_INCOMING -> executor.execute {
                store.setAutoApplyIncomingEnabled(intent?.getBooleanExtra("enabled", true) ?: true)
                publishState()
            }

            ACTION_SET_SYNC_TOASTS -> executor.execute {
                store.setSyncToastHidden(intent?.getBooleanExtra("hidden", false) ?: false)
                publishState()
            }

            ACTION_SET_BT_FALLBACK -> executor.execute {
                store.setBtFallbackEnabled(intent?.getBooleanExtra("enabled", false) ?: false)
                if (!store.isBtFallbackEnabled()) bluetoothLink?.stop()
                evaluateBluetooth()
                publishState()
            }

            ACTION_REFRESH_CAPABILITIES -> executor.execute {
                rootAvailable = RootClipboardBridge.isAvailable()
                if ((!rootAvailable || !RootClipboardBridge.hasBackgroundAccess(this)) && automaticClipboardEnabled) {
                    automaticClipboardEnabled = false
                    store.setRootClipboardAutomationEnabled(false)
                }
                val hasShizuku = ShizukuClipboardBridge.hasPermission()
                if (!hasShizuku && shizukuAutomationEnabled) {
                    shizukuAutomationEnabled = false
                    store.setShizukuClipboardAutomationEnabled(false)
                } else if (hasShizuku && store.isShizukuClipboardAutomationEnabled()) {
                    shizukuAutomationEnabled = true
                }
                if (automaticClipboardEnabled || shizukuAutomationEnabled) {
                    startBackgroundPolling()
                } else {
                    stopBackgroundPolling()
                }
                RootClipboardBridge.syncKeepAlive(this, store.groupKey != null)
                publishState()
            }

            ACTION_REMOVE_MEMBER -> intent?.getStringExtra(EXTRA_MEMBER_ID)?.let { id ->
                executor.execute {
                    store.removeMember(id)
                    if (store.peer?.deviceId == id) {
                        store.peer = null
                        store.groupKey = null
                        store.peerCandidates = emptyList()
                        store.lastGoodEndpoint = null
                        client.close()
                    }
                    RootClipboardBridge.syncKeepAlive(this, store.groupKey != null)
                    publishState()
                }
            }

            ACTION_UPDATE_DEVICE_NAME -> {
                val id = intent?.getStringExtra(EXTRA_MEMBER_ID)
                val newName = intent?.getStringExtra(EXTRA_DEVICE_NAME)?.trim()
                executor.execute {
                    if (id.isNullOrBlank() || newName.isNullOrBlank()) return@execute
                    store.applyRename(id, newName)
                    client.sendRename(id, newName)
                    publishState()
                }
            }

            ACTION_DISABLE_ACCESSIBILITY -> {
                executor.execute {
                    AccessibilityClipboardBridge.disable()
                    publishState()
                }
            }

            ACTION_START -> if (store.groupKey != null && !client.isConnected()) {
                requestConnectResettingBackoff("action_start")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopBackgroundPolling()
        client.close()
        bluetoothLink?.stop()
        bluetoothEvaluator?.cancel(false)
        stopNsdDiscovery()
        unregisterNetworkCallback()
        runCatching { unregisterReceiver(screenStateReceiver) }
        executor.shutdownNow()
        reconnectExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun unpair() {
        lastError = null
        pairingHint = null
        store.unpair()
        client.close()
        bluetoothLink?.stop()
        RootClipboardBridge.syncKeepAlive(this, paired = false)
        publishState()
    }

    private fun resetReconnectBackoffAndTrigger(reason: String) {
        executor.execute {
            Log.d("BinderClip", "Triggering reconnect ($reason)")
            client.forceReconnect()
        }
    }

    private fun requestConnectResettingBackoff(reason: String) {
        executor.execute {
            Log.d("BinderClip", "Requesting connect ($reason)")
            client.requestConnectResettingBackoff()
        }
    }

    private fun receiveText(text: String) {
        val hash = SyncProtocol.sha256Hex(text)
        if (hash == lastSeenHash) return
        lastSeenHash = hash

        val autoApply = store.isAutoApplyIncomingEnabled()
        if (autoApply || uiVisible || automaticClipboardEnabled) {
            val applied = runCatching { applyText(text); true }.getOrDefault(false)
            if (applied) {
                syncToast("Received text")
                store.pendingText = null
            } else {
                store.pendingText = text
                notifyPending("New clipboard text received", text)
            }
        } else {
            store.pendingText = text
            notifyPending("New clipboard text received", text)
        }
        publishState()
    }

    private fun receiveOpenUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || !isWebUrl(trimmed)) {
            reportFailure("Could not open link")
            return
        }
        notifyOpenUrl(trimmed)
        syncToast("Received link")
    }

    private fun receiveImage(image: ImagePayload) {
        if (image.sha256 == lastSeenHash) return
        lastSeenHash = image.sha256

        val autoApply = store.isAutoApplyIncomingEnabled()
        if (autoApply || uiVisible || automaticClipboardEnabled) {
            val applied = runCatching { applyImage(image); true }.getOrDefault(false)
            if (applied) {
                syncToast("Received image (${image.mimeType})")
                pendingImage = null
            } else {
                pendingImage = image
                notifyPending("New image received", "Image (${image.mimeType})")
            }
        } else {
            pendingImage = image
            notifyPending("New image received", "Image (${image.mimeType})")
        }
        publishState()
    }

    private fun sendCurrentClipboard(userInitiated: Boolean = false) {
        val payload = ClipboardClassifier.read(this, clipboard)
        Log.i("BinderClip", "sendCurrentClipboard: userInitiated=$userInitiated, payload=$payload, isConnected=${client.isConnected()}")
        when (payload) {
            is LocalClipboardContent.Text -> {
                val hash = SyncProtocol.sha256Hex(payload.value)
                if (hash == lastSeenHash && !userInitiated) return
                lastSeenHash = hash
                if (client.isConnected()) client.sendText(payload.value, hash)
                else bluetoothLink?.takeIf { it.isConnected() }?.sendClipboard(payload.value)
                if (userInitiated) syncToast("Sent text")
            }
            is LocalClipboardContent.Image -> {
                if (payload.value.sha256 == lastSeenHash && !userInitiated) return
                lastSeenHash = payload.value.sha256
                dispatchImage(payload.value, userInitiated)
            }
            is LocalClipboardContent.Unsupported -> {
                if (userInitiated) toast("Clipboard content is unsupported")
            }
        }
    }

    private fun sendAccessibilityClipboard(payload: AccessibilityClipboard) {
        when (payload) {
            is AccessibilityClipboard.Text -> {
                val hash = SyncProtocol.sha256Hex(payload.value)
                if (hash == lastSeenHash) return
                lastSeenHash = hash
                if (client.isConnected()) client.sendText(payload.value, hash)
                else bluetoothLink?.takeIf { it.isConnected() }?.sendClipboard(payload.value)
            }
            is AccessibilityClipboard.Image -> {
                if (payload.value.sha256 == lastSeenHash) return
                lastSeenHash = payload.value.sha256
                dispatchImage(payload.value, userInitiated = false)
            }
        }
    }

    private fun sendSharedImage(image: ImagePayload) {
        lastSeenHash = image.sha256
        dispatchImage(image, userInitiated = true)
    }

    private fun applyText(text: String) {
        lastSeenHash = SyncProtocol.sha256Hex(text)
        clipboard.setPrimaryClip(ClipData.newPlainText("BinderClip", text))
    }

    private fun applyImage(image: ImagePayload) {
        lastSeenHash = image.sha256
        ImageClipboard.write(this, clipboard, image)
    }

    private fun startBackgroundPolling() {
        stopBackgroundPolling()
        backgroundPoll = reconnectExecutor.scheduleWithFixedDelay({
            executor.execute {
                if (!automaticClipboardEnabled && !shizukuAutomationEnabled) return@execute
                val clip = when {
                    automaticClipboardEnabled && rootAvailable -> RootClipboardBridge.read(this, clipboard)
                    shizukuAutomationEnabled && ShizukuClipboardBridge.hasPermission() -> ShizukuClipboardBridge.read(this)
                    else -> null
                }
                when (clip) {
                    is RootClipboardBridge.Clip.Text -> {
                        val hash = SyncProtocol.sha256Hex(clip.value)
                        if (hash != backgroundFingerprint && hash != lastSeenHash) {
                            backgroundFingerprint = hash
                            lastSeenHash = hash
                            client.sendText(clip.value, hash)
                        }
                    }
                    is RootClipboardBridge.Clip.Image -> {
                        if (clip.value.sha256 != backgroundFingerprint && clip.value.sha256 != lastSeenHash) {
                            backgroundFingerprint = clip.value.sha256
                            lastSeenHash = clip.value.sha256
                            client.sendImage(clip.value)
                        }
                    }
                    else -> {}
                }
            }
        }, 500, 500, TimeUnit.MILLISECONDS)
    }

    private fun stopBackgroundPolling() {
        backgroundPoll?.cancel(false)
        backgroundPoll = null
    }

    private fun updateStatus(status: String) {
        when {
            status.startsWith("Pairing") -> pairingHint = status
            client.isConnected() -> {
                pairingHint = null
                lastError = null
            }
            status == "Connecting…" || status == "Not paired" -> lastError = null
        }
        publishState()
    }

    private fun updateTransferStatus(status: String) {
        transferStatus = status
        publishState()
    }

    private fun reportFailure(message: String) {
        DiagnosticLog.warning("Failure: $message")
        lastError = message
        publishState()
    }

    private fun publishState() {
        val paired = store.groupKey != null
        val liveConnected = client.isConnected() || bluetoothLink?.isConnected() == true
        // If any transport is live, we are connected — never let a still-racing WebSocket
        // downgrade the phase to "Connecting" when Bluetooth already carries the session.
        val phase = if (liveConnected) ConnectionPhase.Connected
        else ConnectionStatus.phase(paired, liveConnected, client.isConnecting())
        val peer = store.peer?.copy(connected = liveConnected)
        val members = store.members.map { member ->
            if (peer != null && member.deviceId == peer.deviceId) member.copy(connected = liveConnected)
            else member.copy(connected = false)
        }
        val transportType = when {
            client.isConnected() -> {
                val ep = client.connectedEndpoint() ?: store.peer?.host ?: ""
                val host = SyncProtocol.parseEndpoint(ep)?.first ?: ep
                if (host.startsWith("100.")) TransportType.MESH else TransportType.LAN
            }
            bluetoothLink?.isConnected() == true -> TransportType.BLUETOOTH
            else -> TransportType.NONE
        }
        val statusText = when {
            pairingHint != null && phase != ConnectionPhase.Connected -> pairingHint!!
            phase == ConnectionPhase.Reconnecting && !lastError.isNullOrBlank() -> lastError!!
            else -> ConnectionStatus.label(phase, peer?.name)
        }
        val statusWithTransport = when (transportType) {
            TransportType.BLUETOOTH -> "$statusText · Bluetooth"
            TransportType.MESH -> "$statusText · Mesh"
            else -> statusText
        }
        updateNotification(statusWithTransport)

        AppRuntime.state.value = AppState(
            status = statusText,
            connectionPhase = phase,
            transportType = transportType,
            peer = peer,
            pendingText = store.pendingText != null,
            pendingImage = pendingImage != null,
            transferStatus = transferStatus,
            members = members,
            rootAvailable = rootAvailable,
            automaticClipboardEnabled = automaticClipboardEnabled,
            shizukuInstalled = ShizukuClipboardBridge.isInstalled(this),
            shizukuAvailable = ShizukuClipboardBridge.isAvailable(),
            shizukuAuthorized = ShizukuClipboardBridge.hasPermission(),
            shizukuAutomationEnabled = shizukuAutomationEnabled,
            imeEnabled = ImeBridge.isEnabled(this),
            imeSelected = ImeBridge.isSelected(this),
            autoApplyIncoming = store.isAutoApplyIncomingEnabled(),
            syncToastHidden = store.isSyncToastHidden(),
            btFallbackEnabled = store.isBtFallbackEnabled(),
            bluetoothActive = bluetoothLink?.isConnected() == true,
            bluetoothEnabled = bluetoothLink?.adapterEnabled() == true,
            accessibilityEnabled = AccessibilityClipboardBridge.isEnabled(this),
            localDeviceId = store.deviceId,
            localDeviceName = DeviceNames.android(this),
        )
        BinderClipTileService.requestUpdate(this)
    }

    private fun isWebUrl(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun toast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** Clipboard copy/receive/send feedback; suppressed by the Hide Sync Toasts setting. */
    private fun syncToast(msg: String) {
        if (!store.isSyncToastHidden()) toast(msg)
    }

    // MARK: - Notifications

    private fun enterForeground(statusText: String) {
        val notification = notification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val syncChannel = NotificationChannel(CHANNEL, "Clipboard Sync", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Maintains persistent connection with paired Mac"
            setShowBadge(false)
        }
        manager.createNotificationChannel(syncChannel)

        val urlChannel = NotificationChannel(URL_CHANNEL, "Browser Links", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Notifications when web links are received"
        }
        manager.createNotificationChannel(urlChannel)
    }

    private fun notification(statusText: String): android.app.Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_binder_clip)
            .setContentTitle("BinderClip")
            .setContentText(statusText)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notifyPending(title: String, body: String) {
        val copyIntent = Intent(this, BinderClipService::class.java).setAction(ACTION_COPY_PENDING)
        val copyPending = PendingIntent.getService(this, 1, copyIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_binder_clip)
            .setContentTitle(title)
            .setContentText(body)
            .addAction(R.drawable.ic_binder_clip, "Copy to Clipboard", copyPending)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(102, notif)
    }

    private fun notifyOpenUrl(url: String) {
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openPending = PendingIntent.getActivity(
            this,
            url.hashCode(),
            viewIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, URL_CHANNEL)
            .setSmallIcon(R.drawable.ic_binder_clip)
            .setContentTitle("Open link")
            .setContentText(url)
            .setStyle(NotificationCompat.BigTextStyle().bigText(url))
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(103, notif)
    }

    // MARK: - mDNS Discovery

    private fun nsdLog(message: String) {
        Log.i("BinderClipNSD", message)
        DiagnosticLog.info(message)
    }

    private fun startNsdDiscovery() {
        stopNsdDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("_binderclip._tcp")) return
                runCatching {
                    if (Build.VERSION.SDK_INT >= 34) {
                        val infoCallback = object : NsdManager.ServiceInfoCallback {
                            override fun onServiceUpdated(info: NsdServiceInfo) {
                                runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                                handleResolvedMac(info)
                            }
                            override fun onServiceLost() {}
                            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                            override fun onServiceInfoCallbackUnregistered() {}
                        }
                        nsdManager.registerServiceInfoCallback(service, mainExecutor, infoCallback)
                    } else {
                        val resolveListener = object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                handleResolvedMac(info)
                            }
                        }
                        @Suppress("DEPRECATION")
                        nsdManager.resolveService(service, resolveListener)
                    }
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                DiagnosticLog.info("NSD discovery failed to start (error $errorCode)")
                nsdDiscoveryListener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdDiscoveryListener = null
            }
        }
        nsdDiscoveryListener = listener
        runCatching { nsdManager.discoverServices("_binderclip._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { nsdLog("NSD discovery started") }
    }

    private fun stopNsdDiscovery() {
        nsdDiscoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
            nsdDiscoveryListener = null
        }
    }

    /** Discovery is only useful while disconnected; pause it while connected to save battery. */
    private fun refreshNsdDiscovery() {
        if (store.groupKey == null || client.isConnected()) {
            if (nsdDiscoveryListener != null) nsdLog("NSD paused (paired and connected)")
            stopNsdDiscovery()
        } else if (nsdDiscoveryListener == null) {
            startNsdDiscovery()
        }
    }

    /** Bare "host:port" candidates must stay IPv4 (see parseEndpoint); loopback is never advertised. */
    private fun resolvedIPv4(info: NsdServiceInfo): String? {
        val hosts = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.mapNotNull { it.hostAddress }
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host?.hostAddress)
        }
        return hosts.firstOrNull { it.contains('.') && !it.startsWith("127.") }
    }

    private fun handleResolvedMac(info: NsdServiceInfo) {
        if (store.groupKey == null) return
        val host = resolvedIPv4(info) ?: return
        val advertisedId = SyncProtocol.nsdTxt(info.attributes, SyncProtocol.MDNS_TXT_ID)
        if (!SyncProtocol.shouldAcceptDiscoveredMac(store.peer?.deviceId, advertisedId)) {
            nsdLog("Ignoring BinderClip Mac at $host (device id mismatch)")
            return
        }
        val endpoint = "$host:${info.port}"
        val wasKnown = endpoint in store.peerCandidates
        val merged = SyncProtocol.mergeAdvertisedEndpoints(store.peerCandidates, listOf(endpoint))
        if (merged != store.peerCandidates) {
            store.peerCandidates = merged
            nsdLog("Discovered paired Mac at $endpoint")
        }
        if (!client.isConnected() && bluetoothLink?.isConnected() != true) {
            // Only a *new* endpoint warrants resetting the backoff; repeated re-discoveries of an
            // already-known Mac would keep backoff pinned at 1s and starve the Bluetooth fallback.
            // Known endpoints are already handled by the racer's own reconnect schedule.
            if (!wasKnown) {
                client.requestConnectResettingBackoff()
            }
        }
    }

    // MARK: - Bluetooth fallback

    private fun updateNetworkFlag() {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkAvailable = connectivity.allNetworks.any { net ->
            val caps = connectivity.getNetworkCapabilities(net)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun evaluateBluetooth() {
        val link = bluetoothLink ?: return
        if (store.groupKey == null) {
            link.stop()
            return
        }
        val btConnected = link.isConnected()
        val decision = BtPolicy.decide(
            paired = true,
            fallbackEnabled = store.isBtFallbackEnabled(),
            wsConnected = client.isConnected(),
            btConnected = btConnected,
            backoffSeconds = client.currentBackoffSeconds(),
            networkAvailable = networkAvailable,
            btAdapterOn = link.adapterEnabled(),
            permissionGranted = link.hasPermission(),
            nowMs = System.currentTimeMillis(),
            lastWsConnectedMs = client.lastConnectedAtMs(),
        )
        android.util.Log.d("BinderClipBLE", "evaluateBluetooth decision: $decision (btConnected=$btConnected, wsConn=${client.isConnected()}, backoff=${client.currentBackoffSeconds()})")
        when (decision) {
            BtPolicy.Decision.LISTEN_BT -> {
                if (!btConnected && !link.isListening()) {
                    android.util.Log.i("BinderClipBLE", "Triggering link.startListening()")
                    val started = link.startListening()
                    if (!started) {
                        // startListening can fail (permission/adapter race); retry next cycle.
                        android.util.Log.w("BinderClipBLE", "startListening() did not start, will retry")
                    }
                }
            }
            BtPolicy.Decision.PROMPT_ENABLE_BT -> {
                // The fallback is active (paired, off-network/unreachable) but the radio is off.
                // Try to enable it silently first; fall back to a Settings shortcut notification.
                if (link.adapterEnabled()) {
                    // Nothing to do — adapter just came on.
                } else if (link.requestEnable()) {
                    android.util.Log.i("BinderClipBLE", "Requested Bluetooth radio enable for fallback")
                } else {
                    promptBtEnable()
                }
            }
            else -> Unit
        }
        // While Bluetooth carries the session, quiet the WebSocket racer so it stops cycling
        // through LAN/mesh and never fights the BT link. Un-suppress once BT drops.
        client.setReconnectSuppressed(btConnected && !client.isConnected())
        // Promote to WebSocket only once it has been stable for a grace period. Without this, a
        // flaky tunnel (café WARP) that connects then drops would tear BT down every cycle and
        // flap forever.
        val wsStable = client.isConnected()
        if (wsStable && btConnected) {
            if (wsStableSinceMs == 0L) wsStableSinceMs = System.currentTimeMillis()
            val stableForMs = System.currentTimeMillis() - wsStableSinceMs
            if (stableForMs >= BT_TO_WS_GRACE_MS) {
                DiagnosticLog.info("WebSocket stable ${stableForMs / 1000}s; tearing down Bluetooth")
                btTornDownForWsAtMs = System.currentTimeMillis()
                link.stop()
            }
        } else {
            wsStableSinceMs = 0L
        }
        // Only tear down a scanning Bluetooth link if we are not relying on it as the live
        // transport (i.e. WS is connected AND the grace period elapsed). A mere WS probe that
        // connects briefly must not kill the fallback.
        if (client.isConnected() && !btConnected && link.isListening()) {
            link.stop()
        }
    }

    private fun promptBtEnable() {
        val now = System.currentTimeMillis()
        if (now - lastBtEnablePromptMs < 10 * 60_000L) return
        lastBtEnablePromptMs = now
        val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        val pending = PendingIntent.getActivity(this, 104, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_binder_clip)
            .setContentTitle("Bluetooth fallback ready")
            .setContentText("Enable Bluetooth to keep syncing without Wi-Fi")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(105, notif)
    }

    private fun notifyDeferredImage(mimeType: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_binder_clip)
            .setContentTitle("Image waiting for Wi-Fi")
            .setContentText("Images ($mimeType) need Wi-Fi or mesh; text still syncs over Bluetooth")
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(106, notif)
    }

    /** Wi-Fi/mesh first; Bluetooth carries text/links only and defers images. */
    private fun dispatchImage(image: ImagePayload, userInitiated: Boolean) {
        if (client.isConnected()) {
            client.sendImage(image)
            if (userInitiated) syncToast("Sent image")
        } else if (bluetoothLink?.isConnected() == true) {
            if (userInitiated) syncToast("Images need Wi-Fi or mesh")
            else if (lastDeferredImageHash != image.sha256) {
                lastDeferredImageHash = image.sha256
                notifyDeferredImage(image.mimeType)
            }
        } else {
            client.sendImage(image)
            if (userInitiated) syncToast("Sent image")
        }
    }

    // MARK: - Network Monitor

    private fun registerNetworkCallback() {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkAvailable = true
                scheduleNetworkReconnect("network_available")
            }

            override fun onLost(network: Network) {
                scheduleNetworkReconnect("network_lost")
            }
        }
        networkCallback = cb
        connectivity.registerNetworkCallback(request, cb)
        networkAvailable = connectivity.allNetworks.any { net ->
            val caps = connectivity.getNetworkCapabilities(net)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun scheduleNetworkReconnect(reason: String) {
        networkDebounceFuture?.cancel(false)
        networkDebounceFuture = reconnectExecutor.schedule({
            val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkAvailable = connectivity.allNetworks.any { net ->
                val caps = connectivity.getNetworkCapabilities(net)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ||
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            evaluateBluetooth()
            if (!client.isConnected() && bluetoothLink?.isConnected() != true) {
                requestConnectResettingBackoff(reason)
            }
        }, 2, TimeUnit.SECONDS)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { connectivity.unregisterNetworkCallback(it) }
            networkCallback = null
        }
    }
}
