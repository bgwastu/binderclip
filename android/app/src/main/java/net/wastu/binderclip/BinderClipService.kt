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

data class AppState(
    val status: String = "Not paired",
    val connectionPhase: ConnectionPhase = ConnectionPhase.NotPaired,
    val peer: RememberedPeer? = null,
    val pendingText: Boolean = false,
    val pendingImage: Boolean = false,
    val transferStatus: String? = null,
    val members: List<RememberedPeer> = emptyList(),
    val rootAvailable: Boolean = false,
    val automaticClipboardEnabled: Boolean = false,
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
    private var rootPoll: ScheduledFuture<*>? = null
    private var rootFingerprint: String? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    client.setInteractive(true)
                    if (automaticClipboardEnabled) startRootPolling()
                    if (store.groupKey != null && !client.isConnected()) requestConnectResettingBackoff("screen_on")
                }
                Intent.ACTION_SCREEN_OFF -> {
                    client.setInteractive(false)
                    stopRootPolling()
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
                refreshNsdDiscovery()
                publishState()
            },
            onUnpaired = {
                lastError = null
                pairingHint = null
                store.unpair()
                stopNsdDiscovery()
                DiagnosticLog.info("Mac unpaired this phone")
                executor.execute { RootClipboardBridge.syncKeepAlive(this, paired = false) }
                publishState()
            },
        )
        val power = getSystemService(PowerManager::class.java)
        client.setInteractive(power?.isInteractive != false)

        clipboard.addPrimaryClipChangedListener {
            if (uiVisible) executor.execute(::sendCurrentClipboard)
        }
        AccessibilityClipboardBridge.onClipboard = { payload ->
            executor.execute { sendAccessibilityClipboard(payload) }
        }
        AccessibilityClipboardBridge.onAvailabilityChanged = { executor.execute(::publishState) }

        registerNetworkCallback()
        refreshNsdDiscovery()

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
            if (store.groupKey != null) {
                client.connect()
            }
            RootClipboardBridge.syncKeepAlive(this, store.groupKey != null)
            if (automaticClipboardEnabled) startRootPolling()
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
                    when (val shared = SharedPayloadCache.value.also { SharedPayloadCache.value = null }) {
                        is SharedPayload.Image -> sendSharedImage(shared.value)
                        is SharedPayload.Text -> {
                            val trimmed = shared.value.trim()
                            val isUrl = isWebUrl(trimmed)
                            if (!isUrl) applyText(shared.value)
                            if (isUrl) {
                                client.sendOpenUrl(trimmed, targetDeviceId)
                            } else {
                                client.sendText(shared.value)
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
                    toast("Copied text")
                }
                pendingImage?.let { image ->
                    applyImage(image)
                    pendingImage = null
                    toast("Copied image")
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
                if (automaticClipboardEnabled) startRootPolling() else {
                    stopRootPolling()
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

            ACTION_REFRESH_CAPABILITIES -> executor.execute {
                rootAvailable = RootClipboardBridge.isAvailable()
                if ((!rootAvailable || !RootClipboardBridge.hasBackgroundAccess(this)) && automaticClipboardEnabled) {
                    automaticClipboardEnabled = false
                    store.setRootClipboardAutomationEnabled(false)
                    stopRootPolling()
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
        stopRootPolling()
        client.close()
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

        if (uiVisible || automaticClipboardEnabled) {
            applyText(text)
            toast("Received text")
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
        toast("Received link")
    }

    private fun receiveImage(image: ImagePayload) {
        if (image.sha256 == lastSeenHash) return
        lastSeenHash = image.sha256

        if (uiVisible || automaticClipboardEnabled) {
            applyImage(image)
            toast("Received image (${image.mimeType})")
        } else {
            pendingImage = image
            notifyPending("New image received", "Image (${image.mimeType})")
        }
        publishState()
    }

    private fun sendCurrentClipboard(userInitiated: Boolean = false) {
        when (val payload = ClipboardClassifier.read(this, clipboard)) {
            is LocalClipboardContent.Text -> {
                val hash = SyncProtocol.sha256Hex(payload.value)
                if (hash == lastSeenHash) return
                lastSeenHash = hash
                client.sendText(payload.value, hash)
                if (userInitiated) toast("Sent text")
            }
            is LocalClipboardContent.Image -> {
                if (payload.value.sha256 == lastSeenHash) return
                lastSeenHash = payload.value.sha256
                client.sendImage(payload.value)
                if (userInitiated) toast("Sent image")
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
                client.sendText(payload.value, hash)
            }
            is AccessibilityClipboard.Image -> {
                if (payload.value.sha256 == lastSeenHash) return
                lastSeenHash = payload.value.sha256
                client.sendImage(payload.value)
            }
        }
    }

    private fun sendSharedImage(image: ImagePayload) {
        lastSeenHash = image.sha256
        client.sendImage(image)
        toast("Sent image")
    }

    private fun applyText(text: String) {
        lastSeenHash = SyncProtocol.sha256Hex(text)
        clipboard.setPrimaryClip(ClipData.newPlainText("BinderClip", text))
    }

    private fun applyImage(image: ImagePayload) {
        lastSeenHash = image.sha256
        ImageClipboard.write(this, clipboard, image)
    }

    private fun startRootPolling() {
        stopRootPolling()
        rootPoll = reconnectExecutor.scheduleWithFixedDelay({
            executor.execute {
                if (!automaticClipboardEnabled) return@execute
                when (val clip = RootClipboardBridge.read(this, clipboard)) {
                    is RootClipboardBridge.Clip.Text -> {
                        val hash = SyncProtocol.sha256Hex(clip.value)
                        if (hash != rootFingerprint && hash != lastSeenHash) {
                            rootFingerprint = hash
                            lastSeenHash = hash
                            client.sendText(clip.value, hash)
                        }
                    }
                    is RootClipboardBridge.Clip.Image -> {
                        if (clip.value.sha256 != rootFingerprint && clip.value.sha256 != lastSeenHash) {
                            rootFingerprint = clip.value.sha256
                            lastSeenHash = clip.value.sha256
                            client.sendImage(clip.value)
                        }
                    }
                    else -> {}
                }
            }
        }, 500, 500, TimeUnit.MILLISECONDS)
    }

    private fun stopRootPolling() {
        rootPoll?.cancel(false)
        rootPoll = null
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
        val liveConnected = client.isConnected()
        val phase = ConnectionStatus.phase(paired, liveConnected, client.isConnecting())
        val peer = store.peer?.copy(connected = liveConnected)
        val members = store.members.map { member ->
            if (peer != null && member.deviceId == peer.deviceId) member.copy(connected = liveConnected)
            else member.copy(connected = false)
        }
        val statusText = when {
            pairingHint != null && phase != ConnectionPhase.Connected -> pairingHint!!
            phase == ConnectionPhase.Reconnecting && !lastError.isNullOrBlank() -> lastError!!
            else -> ConnectionStatus.label(phase, peer?.name)
        }
        updateNotification(statusText)

        AppRuntime.state.value = AppState(
            status = statusText,
            connectionPhase = phase,
            peer = peer,
            pendingText = store.pendingText != null,
            pendingImage = pendingImage != null,
            transferStatus = transferStatus,
            members = members,
            rootAvailable = rootAvailable,
            automaticClipboardEnabled = automaticClipboardEnabled,
            accessibilityEnabled = AccessibilityClipboardBridge.isEnabled(this),
            localDeviceId = store.deviceId,
            localDeviceName = DeviceNames.android(this),
        )
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
        val merged = SyncProtocol.mergeAdvertisedEndpoints(store.peerCandidates, listOf(endpoint))
        if (merged != store.peerCandidates) {
            store.peerCandidates = merged
            nsdLog("Discovered paired Mac at $endpoint")
        }
        if (!client.isConnected()) {
            client.requestConnectResettingBackoff()
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
                scheduleNetworkReconnect("network_available")
            }

            override fun onLost(network: Network) {
                scheduleNetworkReconnect("network_lost")
            }
        }
        networkCallback = cb
        connectivity.registerNetworkCallback(request, cb)
    }

    private fun scheduleNetworkReconnect(reason: String) {
        networkDebounceFuture?.cancel(false)
        networkDebounceFuture = reconnectExecutor.schedule({
            if (!client.isConnected()) {
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
