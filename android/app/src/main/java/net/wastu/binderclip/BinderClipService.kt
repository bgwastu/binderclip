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
import java.util.concurrent.RejectedExecutionException
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
    val transferProgress: TransferProgress? = null,
    val transferRevision: Long = 0,
    val transferUpdatedAtMs: Long = 0,
    val members: List<RememberedPeer> = emptyList(),
    val rootAvailable: Boolean = false,
    val automaticClipboardEnabled: Boolean = false,
    val syncToastHidden: Boolean = true,
    val btFallbackEnabled: Boolean = false,
    val bluetoothActive: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val localDeviceId: String = "",
    val localDeviceName: String = "",
)

object AppRuntime {
    val state = kotlinx.coroutines.flow.MutableStateFlow(AppState())
}

/** The only Android background component: a resilient WebSocket sync foreground service. */
class BinderClipService : Service() {
    companion object {
        val ACTION_START = "${BuildConfig.APPLICATION_ID}.START"
        val ACTION_PAIR = "${BuildConfig.APPLICATION_ID}.PAIR"
        val ACTION_UNPAIR = "${BuildConfig.APPLICATION_ID}.UNPAIR"
        val ACTION_SEND_CURRENT = "${BuildConfig.APPLICATION_ID}.SEND_CURRENT"
        val ACTION_COPY_PENDING = "${BuildConfig.APPLICATION_ID}.COPY_PENDING"
        val ACTION_UI_VISIBLE = "${BuildConfig.APPLICATION_ID}.UI_VISIBLE"
        val ACTION_SET_SYNC_TOASTS = "${BuildConfig.APPLICATION_ID}.SET_SYNC_TOASTS"
        val ACTION_SET_BT_FALLBACK = "${BuildConfig.APPLICATION_ID}.SET_BT_FALLBACK"
        val ACTION_REFRESH_CAPABILITIES = "${BuildConfig.APPLICATION_ID}.REFRESH_CAPABILITIES"
        val ACTION_REMOVE_MEMBER = "${BuildConfig.APPLICATION_ID}.REMOVE_MEMBER"
        val ACTION_UPDATE_DEVICE_NAME = "${BuildConfig.APPLICATION_ID}.UPDATE_DEVICE_NAME"
        val ACTION_SEND_SHARED = "${BuildConfig.APPLICATION_ID}.SEND_SHARED"
        val ACTION_CANCEL_TRANSFER = "${BuildConfig.APPLICATION_ID}.CANCEL_TRANSFER"
        const val EXTRA_TRANSFER_ID = "transfer_id"
        val ACTION_SEARCH_RECONNECT = "${BuildConfig.APPLICATION_ID}.SEARCH_RECONNECT"
        const val EXTRA_MEMBER_ID = "member_id"
        const val EXTRA_TARGET_DEVICE_ID = "target_device_id"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_URI = "uri"
        private const val CHANNEL = "binderclip_sync"
        private const val URL_CHANNEL = "binderclip_urls"
        private const val FILE_CHANNEL = "binderclip_files"
        private const val NOTIFICATION_ID = 101
        /** Require the WebSocket to stay up this long before we tear down a working Bluetooth
         *  session, so a flaky tunnel cannot cause endless BT reconnect flapping. */
        private const val BT_TO_WS_GRACE_MS = 15_000L

        fun startFromBackground(context: Context) {
            DiagnosticLog.initialize(context)
            if (!(AppProfile.current.isDebug || RootClipboardBridge.isAvailable())) {
                DiagnosticLog.info("BinderClipService not started: root unavailable")
                return
            }
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
    private lateinit var clipboard: AppClipboard
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    @Volatile private var stopping = false
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
    private lateinit var clipboardOrdering: ClipboardOrdering
    private var currentClipboard: LocalClipboardContent? = null
    private var lastReadFingerprint: String? = null
    private var remoteClipboardEcho: String? = null
    private var remoteClipboardToken: Long? = null
    private var lastReadClipboardToken: Long? = null
    private var hasObservedClipboard = false
    private var wsClipboardReady = false
    private var btClipboardReady = false
    private var pendingImage: ImagePayload? = null
    private var transferStatus: String? = null
    private var transferProgress: TransferProgress? = null
    private var transferRevision = 0L
    private var transferUpdatedAtMs = 0L
    private var lastError: String? = null
    private var pairingHint: String? = null

    @Volatile
    private var rootAvailable = false

    @Volatile
    private var automaticClipboardEnabled = false

    private var backgroundPoll: ScheduledFuture<*>? = null
    private val pollInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var interactive = true
    private val clipboardReadCache = ClipboardReadCache<RootClipboardBridge.Clip>()
    @Volatile private var remoteSleepUntilMs = 0L

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (stopping) return
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    interactive = true
                    remoteSleepUntilMs = 0L
                    clipboardReadCache.invalidate()
                    bluetoothLink?.resetRetry()
                    client.setInteractive(true)
                    bluetoothLink?.setInteractive(true)
                    if (automaticClipboardEnabled) startBackgroundPolling()
                    if (store.groupKey != null && !client.isConnected()) requestConnectResettingBackoff("screen_on")
                }
                Intent.ACTION_SCREEN_OFF -> {
                    interactive = false
                    client.setInteractive(false)
                    bluetoothLink?.setInteractive(false)
                    stopBackgroundPolling()
                }
            }
        }
    }

    private fun runServiceWork(work: () -> Unit) {
        if (stopping) return
        try {
            executor.execute { if (!stopping) work() }
        } catch (error: RejectedExecutionException) {
            if (!stopping) throw error
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.initialize(this)
        store = DeviceStore(this)
        rootAvailable = (AppProfile.current.isDebug || RootClipboardBridge.isAvailable())
        if (!rootAvailable) {
            publishRootBlockedState()
            stopSelf()
            return
        }
        clipboard = AppClipboard(this)
        val bootId = runCatching { android.provider.Settings.Global.getInt(contentResolver, "boot_count").toString() }.getOrDefault("unknown")
        clipboardOrdering = ClipboardOrdering(store.deviceId, uptime = android.os.SystemClock::elapsedRealtime,
            bootId = bootId, checkpoint = store.clipboardCheckpoint())
        ImageClipboard.clearStale(this)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        createChannel()
        enterForeground("Starting BinderClip…")

        client = WebSocketClient(
            store = store,
            transferStore = FileTransferStore.forContext(this),
            deviceNameProvider = { DeviceNames.android(this) },
            onText = ::receiveText,
            onOpenUrl = ::receiveOpenUrl,
            onImage = ::receiveImage,
            onFile = ::receiveFile,
            onTransferStatus = ::updateTransferStatus,
            onStatus = ::updateStatus,
            onFailure = ::reportFailure,
            onPeerIdentity = { id, name ->
                runServiceWork {
                    if (automaticClipboardEnabled && interactive) startBackgroundPolling()
                    drainQueuedShares()
                }
                store.peer = store.peer?.copy(name = name, deviceId = id)
                refreshNsdDiscovery()
                publishState()
            },
            onRosterChanged = { publishState() },
            onDisconnected = {
                transferProgress?.takeIf { it.isBusy }?.let {
                    updateTransferStatus(it.copy(state = TransferProgress.State.PAUSED))
                }
                runServiceWork { wsClipboardReady = false }
                // If the WebSocket just dropped and we had handed the session to it after tearing
                // down Bluetooth, bring Bluetooth back immediately — the tunnel was flaky and BT is
                // the reliable fallback. Fastest re-arm wins over the 20s stall detector.
                if (store.groupKey != null && btTornDownForWsAtMs > 0L) {
                    btTornDownForWsAtMs = 0L
                    runServiceWork {
                        // The tunnel we switched to just died; re-arm Bluetooth immediately.
                        bluetoothLink?.takeIf { store.isBtFallbackEnabled() && !it.isConnected() && !it.isListening() }?.startListening()
                    }
                }
                refreshNsdDiscovery()
                publishState()
            },
            onRemoteSleep = ::peerSleeping,
            onProtocolFailure = { reportFailure(getString(R.string.secure_connection_failed)) },
            onSyncConnected = { supported, sample -> clipboardConnected(supported, sample, websocket = true) },
            onSyncControl = { json -> clipboardControl(json, websocket = true) },
            onSyncText = { text, stamp -> receiveOrderedClipboard(LocalClipboardContent.Text(text), stamp) },
            onSyncImage = { image, stamp -> receiveOrderedClipboard(LocalClipboardContent.Image(image), stamp) },
            onUnpaired = { runServiceWork {
                lastError = null
                pairingHint = null
                store.unpair()
                resetClipboardOrdering()
                stopNsdDiscovery()
                bluetoothLink?.stop()
                DiagnosticLog.info("Mac unpaired this phone")
                runServiceWork { RootClipboardBridge.syncKeepAlive(this, paired = false) }
                publishState()
            } },
        )
        val power = getSystemService(PowerManager::class.java)
        interactive = power?.isInteractive != false
        client.setInteractive(interactive)

        bluetoothLink = BluetoothLink(this, store, { DeviceNames.android(this) }, object : BluetoothLink.Callbacks {
            override fun onRemoteSleep() = peerSleeping()

            override fun onAuthenticated(remoteId: String, remoteName: String) {
                runServiceWork {
                    if (automaticClipboardEnabled && interactive) startBackgroundPolling()
                    drainQueuedShares()
                }
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
                runServiceWork {
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
                runServiceWork { btClipboardReady = false }
                if (store.groupKey != null) DiagnosticLog.info("Bluetooth link down ($reason)")
                client.setReconnectSuppressed(false)
                refreshNsdDiscovery()
                publishState()
            }

            override fun onSyncConnected(supported: Boolean, sample: ClipboardClockSample?) = clipboardConnected(supported, sample, websocket = false)
            override fun onSyncControl(json: org.json.JSONObject) = clipboardControl(json, websocket = false)
            override fun onSyncText(text: String, stamp: ClipboardStamp?) = receiveOrderedClipboard(LocalClipboardContent.Text(text), stamp)
            override fun onText(text: String) = receiveText(text)
            override fun onOpenUrl(url: String) = receiveOpenUrl(url)

            override fun onUnpair() {
                runServiceWork {
                    lastError = null
                    pairingHint = null
                    store.unpair()
                    resetClipboardOrdering()
                    stopNsdDiscovery()
                    bluetoothLink?.stop()
                    client.close()
                    DiagnosticLog.info("Mac unpaired this phone (bluetooth)")
                    runServiceWork { RootClipboardBridge.syncKeepAlive(this@BinderClipService, paired = false) }
                    publishState()
                }
            }
        })

        bluetoothLink?.setInteractive(interactive)

        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            if (stopping) return@OnPrimaryClipChangedListener
            clipboardReadCache.invalidate()
            if (interactive && (uiVisible || automaticClipboardEnabled)) {
                runServiceWork { if (!stopping) sendCurrentClipboard(userInitiated = false, fromCallback = true) }
            }
        }
        clipboard.addPrimaryClipChangedListener(clipboardListener!!)

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

        runServiceWork {
            applyRootClipboardAutomation()
            if (stopping) return@runServiceWork
            if (store.groupKey != null) {
                client.connect()
            }
            publishState()
        }
        publishState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::client.isInitialized) {
            rootAvailable = (AppProfile.current.isDebug || RootClipboardBridge.isAvailable())
            if (!rootAvailable) {
                publishRootBlockedState()
                return START_NOT_STICKY
            }
            ContextCompat.startForegroundService(
                this,
                Intent(this, BinderClipService::class.java).setAction(ACTION_START),
            )
            return START_NOT_STICKY
        }
        when (intent?.action ?: ACTION_START) {
            ACTION_PAIR -> intent?.getStringExtra(EXTRA_URI)?.let { uri ->
                runServiceWork {
                    runCatching {
                        val info = SyncProtocol.parsePairingUrl(uri) ?: error("Invalid BinderClip pairing code")
                        val nextKey = SyncProtocol.decodeBase64(info.psk) ?: error("Invalid pairing key")
                        if (store.peer?.deviceId != info.deviceId || store.groupKey?.contentEquals(nextKey) != true) {
                            store.unpair()
                            resetClipboardOrdering()
                        }
                        client.pair(uri)
                    }
                        .onSuccess { RootClipboardBridge.syncKeepAlive(this, store.groupKey != null) }
                        .onFailure { reportFailure(it.message ?: "Pairing failed") }
                }
            }

            ACTION_UNPAIR -> runServiceWork(::unpair)

            ACTION_SEND_CURRENT -> runServiceWork { sendCurrentClipboard(userInitiated = true) }

            ACTION_SEARCH_RECONNECT -> resetReconnectBackoffAndTrigger("user_reconnect")

            ACTION_SEND_SHARED -> {
                runServiceWork {
                    drainQueuedShares()
                    if (!client.isConnected()) {
                        FileTransferStore.forContext(this).next(store.peer?.deviceId.orEmpty())?.let {
                            updateTransferStatus(it.metadata.progress(TransferProgress.State.QUEUED))
                        }
                    }
                    client.resumeQueuedTransfers()
                    if (!client.isConnected() && !client.isConnecting()) {
                        client.forceReconnect()
                    }
                }
            }

            ACTION_CANCEL_TRANSFER -> intent?.getStringExtra(EXTRA_TRANSFER_ID)?.let { id ->
                runServiceWork { client.cancelTransfer(id) }
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

            ACTION_SET_SYNC_TOASTS -> runServiceWork {
                store.setSyncToastHidden(intent?.getBooleanExtra("hidden", false) ?: false)
                publishState()
            }

            ACTION_SET_BT_FALLBACK -> runServiceWork {
                store.setBtFallbackEnabled(intent?.getBooleanExtra("enabled", false) ?: false)
                if (!store.isBtFallbackEnabled()) bluetoothLink?.stop()
                evaluateBluetooth()
                publishState()
            }

            ACTION_REFRESH_CAPABILITIES -> runServiceWork {
                rootAvailable = (AppProfile.current.isDebug || RootClipboardBridge.isAvailable())
                if (stopping) return@runServiceWork
                if (!rootAvailable) {
                    automaticClipboardEnabled = false
                    stopBackgroundPolling()
                    client.close()
                    bluetoothLink?.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    publishState()
                    stopSelf()
                } else {
                    applyRootClipboardAutomation()
                    if (stopping) return@runServiceWork
                    if (store.groupKey != null && !client.isConnected()) client.connect()
                    publishState()
                }
            }

            ACTION_REMOVE_MEMBER -> intent?.getStringExtra(EXTRA_MEMBER_ID)?.let { id ->
                runServiceWork {
                    store.removeMember(id)
                    if (store.peer?.deviceId == id) {
                        store.peer = null
                        store.groupKey = null
                        store.peerCandidates = emptyList()
                        store.lastGoodEndpoint = null
                        store.saveClipboardCheckpoint(null)
                        resetClipboardOrdering()
                        client.close()
                    }
                    RootClipboardBridge.syncKeepAlive(this, store.groupKey != null)
                    publishState()
                }
            }

            ACTION_UPDATE_DEVICE_NAME -> {
                val id = intent?.getStringExtra(EXTRA_MEMBER_ID)
                val newName = intent?.getStringExtra(EXTRA_DEVICE_NAME)?.trim()
                runServiceWork {
                    if (id.isNullOrBlank() || newName.isNullOrBlank()) return@runServiceWork
                    store.applyRename(id, newName)
                    client.sendRename(id, newName)
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
        synchronized(this) {
            stopping = true
            stopBackgroundPolling()
            networkDebounceFuture?.cancel(false)
        }
        clipboardListener?.let { if (::clipboard.isInitialized) clipboard.removePrimaryClipChangedListener(it) }
        clipboardListener = null
        DiagnosticLog.flush()
        stopBackgroundPolling()
        if (::client.isInitialized) client.shutdown()
        bluetoothLink?.shutdown()
        if (::client.isInitialized) publishState(notify = false)
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
        resetClipboardOrdering()
        client.close()
        bluetoothLink?.stop()
        RootClipboardBridge.syncKeepAlive(this, paired = false)
        publishState()
    }

    private fun resetReconnectBackoffAndTrigger(reason: String) {
        runServiceWork {
            Log.d("BinderClip", "Triggering reconnect ($reason)")
            client.forceReconnect()
        }
    }

    private fun requestConnectResettingBackoff(reason: String) {
        runServiceWork {
            Log.d("BinderClip", "Requesting connect ($reason)")
            client.requestConnectResettingBackoff()
        }
    }

    private fun receiveText(text: String) = receiveOrderedClipboard(LocalClipboardContent.Text(text), null)

    private fun receiveOpenUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || !isWebUrl(trimmed)) {
            reportFailure("Could not open link")
            return
        }
        notifyOpenUrl(trimmed)
        syncToast("Received link")
    }

    private fun receiveImage(image: ImagePayload) = receiveOrderedClipboard(LocalClipboardContent.Image(image), null)

    private fun clipboardFingerprint(value: LocalClipboardContent): String = when (value) {
        is LocalClipboardContent.Text -> "text:" + SyncProtocol.sha256Hex(value.value)
        is LocalClipboardContent.Image -> "image:" + value.value.sha256
        LocalClipboardContent.Unsupported -> "empty:" + SyncProtocol.sha256Hex(ByteArray(0))
    }

    private fun saveClipboardVersion() { store.saveClipboardCheckpoint(clipboardOrdering.checkpoint()) }

    private fun resetClipboardOrdering() {
        transferProgress = null; transferStatus = null; transferRevision += 1
        clipboardOrdering.reset(); currentClipboard = null; lastReadFingerprint = null; hasObservedClipboard = false
        remoteClipboardEcho = null; remoteClipboardToken = null; lastReadClipboardToken = null
        wsClipboardReady = false; btClipboardReady = false
    }

    private fun clipboardToken(clip: ClipData?): Long? = clip?.let {
        if (AppProfile.current.isDebug) System.identityHashCode(it).toLong() else it.description.timestamp
    }

    private fun observeClipboard(value: LocalClipboardContent, force: Boolean = false, bootstrap: Boolean = false, publish: Boolean = true): ClipboardStamp? {
        if (value is LocalClipboardContent.Text && value.value.toByteArray(Charsets.UTF_8).size > SyncProtocol.MAXIMUM_TEXT_BYTES) return null
        val fingerprint = clipboardFingerprint(value)
        if (!force && lastReadFingerprint == fingerprint) return null
        lastReadFingerprint = fingerprint
        hasObservedClipboard = true
        currentClipboard = value
        val kind = fingerprint.substringBefore(':')
        val stamp = clipboardOrdering.observe(kind, fingerprint.substringAfter(':'), force, bootstrap) ?: return null
        saveClipboardVersion()
        if (publish && (!bootstrap || stamp.time > 0)) publishClipboard(stamp)
        return stamp
    }

    private fun captureClipboard(publish: Boolean = false) {
        if (!interactive || !(AppProfile.current.isDebug || uiVisible || automaticClipboardEnabled)) return
        val value = ClipboardClassifier.read(this, clipboard)
        // A denied/locked read is not a user clearing the clipboard.
        if (value == LocalClipboardContent.Unsupported && !AppProfile.current.isDebug && clipboard.primaryClip == null) return
        val token = clipboardToken(clipboard.primaryClip)
        val changed = hasObservedClipboard && token != null && lastReadClipboardToken != null && token != lastReadClipboardToken
        lastReadClipboardToken = token
        observeClipboard(value, force = changed, bootstrap = !hasObservedClipboard, publish = publish)
    }

    private fun sendCurrentClipboard(userInitiated: Boolean = false, fromCallback: Boolean = false) {
        val value = ClipboardClassifier.read(this, clipboard)
        val fingerprint = clipboardFingerprint(value)
        val token = clipboardToken(clipboard.primaryClip)
        if (!userInitiated && remoteClipboardEcho == fingerprint && token == remoteClipboardToken) {
            remoteClipboardEcho = null
            lastReadFingerprint = fingerprint
            lastReadClipboardToken = token
            hasObservedClipboard = true
            return
        }
        remoteClipboardEcho = null
        val changed = fromCallback && (token == null || token != lastReadClipboardToken)
        lastReadClipboardToken = token
        val stamp = observeClipboard(value, force = userInitiated || changed,
            bootstrap = !hasObservedClipboard && !fromCallback, publish = !userInitiated)
        if (userInitiated && stamp != null && value != LocalClipboardContent.Unsupported) {
            val sent = publishClipboard(stamp)
            if (value is LocalClipboardContent.Text) {
                updateTransferStatus(TransferProgress(
                    if (sent) TransferProgress.State.SENT else TransferProgress.State.PAUSED, "text"))
            } else if (!sent && value is LocalClipboardContent.Image) {
                updateTransferStatus(TransferProgress(TransferProgress.State.PAUSED, "image"))
            }
        } else if (userInitiated && value == LocalClipboardContent.Unsupported) toast("Clipboard content is unsupported")
    }

    private fun publishClipboard(stamp: ClipboardStamp, websocket: Boolean? = null): Boolean {
        val useWs = websocket ?: client.isConnected()
        if (useWs && !wsClipboardReady || !useWs && !btClipboardReady) return false
        val sent = when (val value = currentClipboard) {
            is LocalClipboardContent.Text -> if (clipboardFingerprint(value) != stamp.fingerprint) false
                else if (useWs) client.sendText(value.value, stamp.hash, stamp)
                else bluetoothLink?.takeIf { it.isConnected() }?.sendClipboard(value.value, stamp) == true
            is LocalClipboardContent.Image -> if (clipboardFingerprint(value) != stamp.fingerprint) false
                else if (useWs) client.sendImage(value.value, stamp)
                else {
                    if (lastDeferredImageHash != value.value.sha256) {
                        lastDeferredImageHash = value.value.sha256
                        notifyDeferredImage(value.value.mimeType)
                    }
                    sendClipboardControl(clipboardState(), websocket)
                    false
                }
            else -> sendClipboardControl(clipboardState(), websocket)
        }
        if (sent) { clipboardOrdering.markPublished(stamp.event); saveClipboardVersion() }
        return sent
    }

    private fun clipboardState(): org.json.JSONObject = org.json.JSONObject().put("type", "clipboard_state").also { json ->
        clipboardOrdering.head?.putInto(json)
        val available = currentClipboard?.let { clipboardFingerprint(it) == clipboardOrdering.head?.fingerprint } == true
        json.put("clipAvailable", if (available) 1 else 0)
    }

    private fun sendClipboardControl(json: org.json.JSONObject, websocket: Boolean? = null): Boolean {
        return if (websocket ?: client.supportsClipboardSync()) client.sendClipboardControl(json)
        else bluetoothLink?.sendClipboardControl(json) == true
    }

    private fun clipboardConnected(supported: Boolean, sample: ClipboardClockSample?, websocket: Boolean) = runServiceWork {
        sample?.let { clipboardOrdering.calibrate(it) }
        captureClipboard()
        saveClipboardVersion()
        if (websocket) wsClipboardReady = true else btClipboardReady = true
        if (supported) sendClipboardControl(clipboardState(), websocket)
    }

    private fun clipboardControl(json: org.json.JSONObject, websocket: Boolean) = runServiceWork {
        runCatching {
            captureClipboard()
            if (json.optString("type") == "clipboard_request") {
                val head = clipboardOrdering.head
                if (head != null && json.optString("requestEvent") == head.event && currentClipboard != null) {
                    publishClipboard(head, websocket)
                } else sendClipboardControl(clipboardState(), websocket)
                return@runCatching
            }
            val remote = ClipboardStamp.parse(json)
            val local = clipboardOrdering.head
            when {
                remote == null -> if (local != null && currentClipboard != null) publishClipboard(local, websocket)
                remote == local -> {
                    clipboardOrdering.receive(remote); saveClipboardVersion()
                    if ((currentClipboard == null || currentClipboard == LocalClipboardContent.Unsupported) && remote.kind != "empty" && json.optInt("clipAvailable", 1) == 1 && (remote.kind == "text" || websocket)) {
                        sendClipboardControl(org.json.JSONObject().put("type", "clipboard_request").put("requestEvent", remote.event), websocket)
                    }
                }
                local != null && local > remote -> publishClipboard(local, websocket)
                remote.kind == "empty" || remote.fingerprint == local?.fingerprint -> {
                    clipboardOrdering.receive(remote)
                    if (remote.kind == "empty") currentClipboard = null
                    saveClipboardVersion()
                    if (remote.kind != "empty" && (currentClipboard == null || currentClipboard == LocalClipboardContent.Unsupported) &&
                        json.optInt("clipAvailable", 1) == 1 && (remote.kind == "text" || websocket)) {
                        sendClipboardControl(org.json.JSONObject().put("type", "clipboard_request").put("requestEvent", remote.event), websocket)
                    }
                }
                json.optInt("clipAvailable", 1) == 0 -> Unit
                remote.kind == "text" || websocket -> sendClipboardControl(
                    org.json.JSONObject().put("type", "clipboard_request").put("requestEvent", remote.event), websocket)
            }
        }.onFailure { reportFailure(getString(R.string.secure_connection_failed)) }
    }

    private fun receiveOrderedClipboard(value: LocalClipboardContent, stamp: ClipboardStamp?) = runServiceWork {
        if (store.groupKey == null) return@runServiceWork
        captureClipboard()
        val fingerprint = clipboardFingerprint(value)
        if (stamp != null && stamp.fingerprint != fingerprint) { reportFailure(getString(R.string.secure_connection_failed)); return@runServiceWork }
        val missingPayload = currentClipboard == null || currentClipboard == LocalClipboardContent.Unsupported
        val changed = if (stamp == null) {
            clipboardOrdering.observe(fingerprint.substringBefore(':'), fingerprint.substringAfter(':'), force = true) != null
        } else clipboardOrdering.receive(stamp)
        if (stamp != null && clipboardOrdering.head != stamp) { sendClipboardControl(clipboardState()); return@runServiceWork }
        currentClipboard = value
        saveClipboardVersion()
        if (!changed && !missingPayload) return@runServiceWork
        lastReadFingerprint = fingerprint
        hasObservedClipboard = true
        when (value) {
            is LocalClipboardContent.Text -> {
                val applied = runCatching { applyText(value.value); true }.getOrDefault(false)
                if (applied) { syncToast("Received text"); store.pendingText = null }
                else { store.pendingText = value.value; notifyPending("New clipboard text received", value.value) }
            }
            is LocalClipboardContent.Image -> {
                val applied = runCatching { applyImage(value.value); true }.getOrDefault(false)
                if (applied) { syncToast("Received image (${value.value.mimeType})"); pendingImage = null }
                else { pendingImage = value.value; notifyPending("New image received", "Image (${value.value.mimeType})") }
            }
            else -> Unit
        }
        publishState()
    }

    private fun receiveFile(file: ReceivedFile) {
        if (stopping) return
        val open = PendingIntent.getActivity(this, file.metadata.id.hashCode(),
            Intent(this, ReceivedFilesActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, FILE_CHANNEL)
            .setSmallIcon(R.drawable.ic_binder_clip)
            .setContentTitle(getString(R.string.file_received))
            .setContentText(file.metadata.name)
            .setContentIntent(open)
            .addAction(0, getString(R.string.file_save), open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify("received-file", file.metadata.id.hashCode(), notification)
        publishState()
    }

    private fun drainQueuedShares() {
        if (store.groupKey == null) return
        while (true) {
            val share = store.nextQueuedShare() ?: return
            val text = share.text
            val trimmed = text.trim()
            val isLink = isWebUrl(trimmed)
            val sent = if (isLink) {
                if (client.isConnected()) client.sendOpenUrl(trimmed)
                else bluetoothLink?.takeIf { it.isConnected() }?.sendOpenUrl(trimmed) == true
            } else {
                if (client.isConnected()) client.sendText(text)
                else bluetoothLink?.takeIf { it.isConnected() }?.sendClipboard(text) == true
            }
            if (!sent) return
            if (!store.removeQueuedShare(share.id)) {
                reportFailure("Could not clear queued share")
                return
            }
            if (!isLink) {
                observeClipboard(LocalClipboardContent.Text(text), force = true, publish = false)
                applyText(text)
            }
            DiagnosticLog.info("Queued share sent (${if (isLink) "link" else "text"})")
            updateTransferStatus(TransferProgress(TransferProgress.State.SENT, if (isLink) "link" else "text"))
        }
    }

    private fun applyText(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("BinderClip", text))
        remoteClipboardEcho = "text:" + SyncProtocol.sha256Hex(text)
        remoteClipboardToken = clipboardToken(clipboard.primaryClip)
        lastReadClipboardToken = remoteClipboardToken
    }

    private fun applyImage(image: ImagePayload) {
        ImageClipboard.write(this, clipboard, image)
        remoteClipboardEcho = "image:" + image.sha256
        remoteClipboardToken = clipboardToken(clipboard.primaryClip)
        lastReadClipboardToken = remoteClipboardToken
    }

    private fun pollAllowed(): Boolean = !stopping && PowerPolicy.shouldPoll(
        automaticClipboardEnabled, interactive, store.groupKey != null,
    )

    private fun peerSleeping() {
        remoteSleepUntilMs = android.os.SystemClock.elapsedRealtime() + PowerPolicy.REMOTE_SLEEP_MS
        client.setReconnectSuppressed(false)
        client.deferReconnect(PowerPolicy.REMOTE_SLEEP_MS)
        bluetoothLink?.deferReconnect(PowerPolicy.REMOTE_SLEEP_MS)
    }

    @Synchronized private fun startBackgroundPolling() {
        if (stopping) return
        stopBackgroundPolling()
        if (!interactive || store.groupKey == null) return
        // The read executes on this scheduler, so fixed delay bounds in-flight polls to one.
        backgroundPoll = reconnectExecutor.scheduleWithFixedDelay({
            if (!pollAllowed() || !pollInFlight.compareAndSet(false, true)) return@scheduleWithFixedDelay
            try {
                EnergyCounters.add("clipboard.poll")
                val ticket = clipboardReadCache.generation()
                val primary = clipboard.primaryClip
                val token = clipboardToken(primary)
                val metadata = primary?.let { clip ->
                    val item = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    // Once/minute revalidation also catches providers mutating a stable URI.
                    val bucket = android.os.SystemClock.elapsedRealtime() / 60_000
                    "$bucket:${clip.description.timestamp}:${clip.description}:${item?.uri}:${item?.text}"
                } ?: run { pollInFlight.set(false); return@scheduleWithFixedDelay }
                val clip = clipboardReadCache.read(metadata) {
                    EnergyCounters.add("clipboard.read")
                    RootClipboardBridge.read(this, clipboard)
                }
                runServiceWork {
                    try {
                        if (!pollAllowed() || ticket != clipboardReadCache.generation()) return@runServiceWork
                        val value = when (clip) {
                            is RootClipboardBridge.Clip.Text -> LocalClipboardContent.Text(clip.value)
                            is RootClipboardBridge.Clip.Image -> LocalClipboardContent.Image(clip.value)
                            else -> null
                        }
                        if (value != null) {
                            val changed = hasObservedClipboard && token != null && lastReadClipboardToken != null && token != lastReadClipboardToken
                            lastReadClipboardToken = token
                            observeClipboard(value, force = changed, bootstrap = !hasObservedClipboard)
                        }
                    } finally { pollInFlight.set(false) }
                }
            } catch (_: Exception) { pollInFlight.set(false) }
        }, PowerPolicy.POLL_MS, PowerPolicy.POLL_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized private fun stopBackgroundPolling() {
        backgroundPoll?.cancel(false)
        backgroundPoll = null
    }

    private fun applyRootClipboardAutomation() {
        if (stopping) return
        rootAvailable = (AppProfile.current.isDebug || RootClipboardBridge.isAvailable())
        if (!rootAvailable) {
            automaticClipboardEnabled = false
            stopBackgroundPolling()
            return
        }
        RootClipboardBridge.syncKeepAlive(this, store.groupKey != null)
        val enabled = RootClipboardBridge.enableBackgroundAccess(this)
        if (stopping) return
        automaticClipboardEnabled = enabled
        store.setRootClipboardAutomationEnabled(automaticClipboardEnabled)
        if (automaticClipboardEnabled) startBackgroundPolling() else stopBackgroundPolling()
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

    @Synchronized private fun updateTransferStatus(progress: TransferProgress) {
        if (stopping) return
        transferProgress = progress
        transferStatus = progress.message(this)
        transferRevision += 1
        transferUpdatedAtMs = android.os.SystemClock.elapsedRealtime()
        publishState()
    }

    private fun reportFailure(message: String) {
        DiagnosticLog.warning("Failure: $message")
        lastError = message
        publishState()
    }

    private fun publishRootBlockedState() {
        AppRuntime.state.value = AppState(
            rootAvailable = false,
            localDeviceId = store.deviceId,
            localDeviceName = DeviceNames.android(this),
        )
    }

    private fun publishState(notify: Boolean = true) {
        if (stopping && notify) return
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
        if (notify) updateNotification(statusWithTransport)

        AppRuntime.state.value = AppState(
            status = statusText,
            connectionPhase = phase,
            transportType = transportType,
            peer = peer,
            pendingText = store.pendingText != null,
            pendingImage = pendingImage != null,
            transferStatus = transferStatus,
            transferProgress = transferProgress,
            transferRevision = transferRevision,
            transferUpdatedAtMs = transferUpdatedAtMs,
            members = members,
            rootAvailable = rootAvailable,
            automaticClipboardEnabled = automaticClipboardEnabled,
            syncToastHidden = store.isSyncToastHidden(),
            btFallbackEnabled = store.isBtFallbackEnabled(),
            bluetoothActive = bluetoothLink?.isConnected() == true,
            bluetoothEnabled = bluetoothLink?.adapterEnabled() == true,
            localDeviceId = store.deviceId,
            localDeviceName = DeviceNames.android(this),
        )
    }

    private fun isWebUrl(text: String): Boolean {
        return WebLinks.isWebUrl(text)
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
        val syncChannel = NotificationChannel(CHANNEL, getString(R.string.channel_clipboard_sync), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_clipboard_sync_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(syncChannel)

        val urlChannel = NotificationChannel(URL_CHANNEL, getString(R.string.channel_browser_links), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = getString(R.string.channel_browser_links_desc)
        }
        manager.createNotificationChannel(urlChannel)
        manager.createNotificationChannel(NotificationChannel(FILE_CHANNEL, getString(R.string.received_files), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun notification(statusText: String): android.app.Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_binder_clip)
            .setContentTitle(AppProfile.current.displayName)
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
            .addAction(R.drawable.ic_binder_clip, getString(R.string.notif_copy_to_clipboard), copyPending)
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
            .setContentTitle(getString(R.string.notif_open_link))
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
                if (service.serviceType.trimEnd('.') != AppProfile.current.bonjourType) return
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
        runCatching { nsdManager.discoverServices(AppProfile.current.bonjourType + ".", NsdManager.PROTOCOL_DNS_SD, listener) }
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
        if (stopping) return
        val link = bluetoothLink ?: return
        if (store.groupKey == null) {
            link.stop()
            return
        }
        if (android.os.SystemClock.elapsedRealtime() < remoteSleepUntilMs) return
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
            nowMs = android.os.SystemClock.elapsedRealtime(),
            lastWsConnectedMs = client.lastConnectedAtMs(),
        )

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
                } else if (link.canRetry() && link.requestEnable()) {
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
            if (wsStableSinceMs == 0L) wsStableSinceMs = android.os.SystemClock.elapsedRealtime()
            val stableForMs = android.os.SystemClock.elapsedRealtime() - wsStableSinceMs
            if (stableForMs >= BT_TO_WS_GRACE_MS) {
                DiagnosticLog.info("WebSocket stable ${stableForMs / 1000}s; tearing down Bluetooth")
                btTornDownForWsAtMs = System.currentTimeMillis()
                link.stop()
                publishState()
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
            .setContentTitle(getString(R.string.notif_bluetooth_fallback_title))
            .setContentText(getString(R.string.notif_bluetooth_fallback_desc))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(105, notif)
    }

    private fun notifyDeferredImage(mimeType: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_binder_clip)
            .setContentTitle(getString(R.string.notif_image_waiting_title))
            .setContentText(getString(R.string.notif_image_waiting_desc, mimeType))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(106, notif)
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

    @Synchronized private fun scheduleNetworkReconnect(reason: String) {
        if (stopping) return
        networkDebounceFuture?.cancel(false)
        networkDebounceFuture = reconnectExecutor.schedule({
            if (stopping) return@schedule
            val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkAvailable = connectivity.allNetworks.any { net ->
                val caps = connectivity.getNetworkCapabilities(net)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ||
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            evaluateBluetooth()
            if (PowerPolicy.shouldProbePreferredTransport(client.isConnected(), bluetoothLink?.isConnected() == true,
                    networkAvailable, reason == "network_available")) {
                DiagnosticLog.info("Network became available; probing WebSocket while retaining Bluetooth")
                runServiceWork { if (!client.isConnected()) client.forceReconnect() }
            } else if (!client.isConnected() && bluetoothLink?.isConnected() != true) {
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
