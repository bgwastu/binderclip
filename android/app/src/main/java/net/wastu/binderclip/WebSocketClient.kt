package net.wastu.binderclip

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WebSocketClient(
    private val store: DeviceStore,
    private val deviceNameProvider: () -> String,
    private val onText: (String) -> Unit,
    private val onOpenUrl: (String) -> Unit,
    private val onImage: (ImagePayload) -> Unit,
    private val onTransferStatus: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onPeerIdentity: (String, String) -> Unit,
    private val onRosterChanged: (List<RememberedPeer>) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onUnpaired: () -> Unit,
) {
    companion object {
        private const val TAG = "BinderClipWS"
    }

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(SyncProtocol.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val candidateSockets = ConcurrentHashMap<String, WebSocket>()
    @Volatile
    private var activeSocket: WebSocket? = null
    @Volatile
    private var activeEndpoint: String? = null
    private val isConnected = AtomicBoolean(false)
    private var reconnectTask: ScheduledFuture<*>? = null
    private val isConnecting = AtomicBoolean(false)
    @Volatile private var generation = 0
    private val openedWithoutAuth = AtomicInteger(0)
    private val reportedFailure = AtomicBoolean(false)
    private val raceAuthClaimed = AtomicBoolean(false)
    private val policy = ReconnectPolicy()
    @Volatile private var lastHeardMs = 0L
    private var heartbeatWatch: ScheduledFuture<*>? = null
    @Volatile private var interactive = true
    @Volatile private var pairingScan = false

    fun isConnected(): Boolean = isConnected.get()
    fun connectedEndpoint(): String? = activeEndpoint
    fun currentBackoffSeconds(): Long = policy.delaySeconds
    fun isConnecting(): Boolean = isConnecting.get()

    fun setInteractive(value: Boolean) {
        if (interactive == value) return
        interactive = value
        executor.execute { sendPowerLocked() }
    }

    fun pair(uriString: String) {
        val info = SyncProtocol.parsePairingUrl(uriString)
            ?: error("Invalid BinderClip pairing code")

        close()
        onStatus("Pairing with ${info.deviceName}…")

        val pskBytes = SyncProtocol.decodeBase64(info.psk)
            ?: error("Invalid pairing key")

        store.groupKey = pskBytes
        store.peerCandidates = info.endpoints
        if (store.lastGoodEndpoint !in info.endpoints) {
            store.lastGoodEndpoint = null
        }

        val parsed = info.endpoints.firstNotNullOfOrNull { SyncProtocol.parseEndpoint(it) }
        val host = parsed?.first ?: "unknown"
        val port = parsed?.second ?: SyncProtocol.DEFAULT_PORT

        store.peer = RememberedPeer(
            name = info.deviceName,
            host = host,
            port = port,
            deviceId = info.deviceId,
            platform = "macOS",
            connected = false
        )

        pairingScan = true
        requestConnect(force = true, resetBackoff = true)
    }

    fun connect() {
        requestConnect(force = false, resetBackoff = false)
    }

    fun forceReconnect() {
        requestConnect(force = true, resetBackoff = true)
    }

    fun requestConnectResettingBackoff() {
        requestConnect(force = false, resetBackoff = true)
    }

    private fun requestConnect(force: Boolean, resetBackoff: Boolean) {
        executor.execute {
            val psk = store.groupKey ?: return@execute
            val endpoints = getEndpoints()
            if (endpoints.isEmpty()) {
                onFailure("No Mac address to connect to. Scan a fresh QR from the Mac.")
                return@execute
            }
            if (!policy.shouldStartConnect(force, isConnected.get(), isConnecting.get())) return@execute
            if (resetBackoff) policy.resetBackoff()
            startRaceLocked(psk, endpoints)
        }
    }

    private fun startRaceLocked(psk: ByteArray, endpoints: List<String>) {
        generation += 1
        val myGeneration = generation

        cancelPendingReconnect()
        stopHeartbeatWatch()
        closeCandidateSockets()
        activeSocket?.cancel()
        activeSocket = null
        activeEndpoint = null
        isConnected.set(false)
        isConnecting.set(true)
        openedWithoutAuth.set(0)
        reportedFailure.set(false)
        raceAuthClaimed.set(false)
        onDisconnected()
        onStatus("Connecting…")

        val pskBase64 = SyncProtocol.urlSafeBase64(psk)
        val localId = store.deviceId
        val localName = deviceNameProvider()

        for (rawEndpoint in ConnectRace.combinations(endpoints)) {
            val parsed = SyncProtocol.parseEndpoint(rawEndpoint) ?: continue
            val endpoint = "${parsed.first}:${parsed.second}"
            val url = "ws://$endpoint/"
            val request = Request.Builder()
                .url(url)
                .build()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (myGeneration != generation) {
                        webSocket.cancel()
                        return
                    }
                    if (!raceAuthClaimed.compareAndSet(false, true)) {
                        Log.d(TAG, "Losing connect race at $endpoint")
                        candidateSockets.remove(endpoint)
                        webSocket.cancel()
                        return
                    }
                    for (ep in candidateSockets.keys.toList()) {
                        if (ep != endpoint) {
                            candidateSockets.remove(ep)?.cancel()
                        }
                    }
                    openedWithoutAuth.incrementAndGet()
                    Log.d(TAG, "Socket opened to $endpoint, sending auth")
                    val authJson = JSONObject().apply {
                        put("type", "auth")
                        put("token", pskBase64)
                        put("deviceId", localId)
                        put("deviceName", localName)
                        put("platform", "Android")
                        put("version", SyncProtocol.VERSION)
                        put("pairing", pairingScan)
                    }
                    webSocket.send(authJson.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (myGeneration != generation) return
                    handleTextMessage(webSocket, endpoint, text, myGeneration)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (myGeneration != generation) return
                    handleBinaryMessage(webSocket, bytes)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (myGeneration != generation) return
                    handleSocketClosed(webSocket, endpoint, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (myGeneration != generation) return
                    Log.w(TAG, "Connection to $endpoint failed: ${t.javaClass.name}: ${t.message}", t)
                    handleSocketClosed(webSocket, endpoint, t.message ?: "Connection failed")
                }
            }

            val ws = httpClient.newWebSocket(request, listener)
            candidateSockets[endpoint] = ws
        }

        executor.schedule({
            if (myGeneration != generation) return@schedule
            if (isConnected.get()) {
                isConnecting.set(false)
                return@schedule
            }
            if (!isConnecting.get()) return@schedule
            isConnecting.set(false)
            closeCandidateSockets()
            reportConnectFailure(endpoints)
            scheduleReconnectBackoff()
        }, SyncProtocol.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun reportConnectFailure(endpoints: List<String>) {
        if (!reportedFailure.compareAndSet(false, true)) return
        onStatus("Reconnecting…")
        if (openedWithoutAuth.get() > 0) {
            onFailure("Pairing key rejected. Scan a fresh QR from the Mac.")
            return
        }
        if (!policy.shouldAnnounceUnreachable()) return
        val hosts = endpoints.mapNotNull { SyncProtocol.parseEndpoint(it)?.first }.distinct()
        onFailure("Could not reach Mac at ${hosts.joinToString(", ")}. Same Wi-Fi?")
    }

    private fun handleTextMessage(webSocket: WebSocket, endpoint: String, text: String, myGeneration: Int) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        noteActivity()
        when (json.optString("type")) {
            "auth_ok" -> {
                if (myGeneration != generation) {
                    webSocket.cancel()
                    return
                }
                Log.i(TAG, "Authenticated successfully with $endpoint")
                if (!isConnected.compareAndSet(false, true)) {
                    webSocket.cancel()
                    return
                }
                activeSocket = webSocket
                activeEndpoint = endpoint
                isConnecting.set(false)
                policy.resetBackoff()
                reportedFailure.set(true)
                pairingScan = false

                for ((ep, sock) in candidateSockets) {
                    if (ep != endpoint) {
                        sock.cancel()
                    }
                }
                candidateSockets.clear()

                val remoteId = json.optString("deviceId")
                val remoteName = json.optString("deviceName", "Mac")
                val parsed = SyncProtocol.parseEndpoint(endpoint)
                val host = parsed?.first ?: "unknown"
                val port = parsed?.second ?: SyncProtocol.DEFAULT_PORT

                val updatedPeer = RememberedPeer(
                    name = remoteName,
                    host = host,
                    port = port,
                    deviceId = remoteId,
                    platform = "macOS",
                    connected = true
                )
                store.peer = updatedPeer
                store.upsertMembers(listOf(updatedPeer))
                store.lastGoodEndpoint = endpoint
                applyRemoteEndpoints(SyncProtocol.endpointsFromJson(json))

                onPeerIdentity(remoteId, remoteName)
                onRosterChanged(store.members)
                onStatus(remoteName.ifBlank { "Connected" })
                DiagnosticLog.info("Connected to $remoteName via $endpoint")
                sendPowerLocked()
                startHeartbeatWatch()
            }

            "clipboard" -> {
                val clipText = json.optString("text")
                Log.i(TAG, "Received clipboard text from server: $clipText")
                if (clipText.isNotEmpty()) {
                    onText(clipText)
                }
            }

            "openUrl" -> {
                val url = json.optString("url")
                Log.i(TAG, "Received openUrl from server: $url")
                if (url.isNotEmpty()) {
                    onOpenUrl(url)
                }
            }

            "endpoints" -> {
                applyRemoteEndpoints(SyncProtocol.endpointsFromJson(json))
            }

            "rename" -> {
                val id = json.optString("id")
                val name = json.optString("name")
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    store.applyRename(id, name)
                    onRosterChanged(store.members)
                }
            }

            "ping" -> {
                val t = json.optLong("t", System.currentTimeMillis())
                webSocket.send("""{"type":"pong","t":$t}""")
            }

            "pong" -> {}

            "unpair" -> {
                applyRemoteUnpair()
            }
        }
    }

    private fun handleBinaryMessage(webSocket: WebSocket, bytes: ByteString) {
        noteActivity()
        val unpacked = SyncProtocol.unpackImage(bytes.toByteArray()) ?: return
        val meta = unpacked.first
        val imageData = unpacked.second
        val imagePayload = ImagePayload(id = meta.id, mimeType = meta.mimeType, data = imageData)
        onImage(imagePayload)
        onTransferStatus("Received image (${meta.mimeType})")
    }

    private fun handleSocketClosed(webSocket: WebSocket, endpoint: String, reason: String) {
        executor.execute {
            markActiveDeadIfNeeded(webSocket, endpoint, reason, resetBackoff = false)
        }
    }

    private fun markActiveDeadIfNeeded(webSocket: WebSocket, endpoint: String, reason: String, resetBackoff: Boolean) {
        candidateSockets.remove(endpoint)
        if (webSocket == activeSocket) {
            Log.w(TAG, "Active connection closed: $reason")
            stopHeartbeatWatch()
            activeSocket = null
            activeEndpoint = null
            isConnected.set(false)
            isConnecting.set(false)

            store.peer = store.peer?.copy(connected = false)
            store.updateMemberConnectionState(store.peer?.deviceId.orEmpty(), false)
            onRosterChanged(store.members)
            onDisconnected()
            onStatus("Reconnecting…")
            scheduleReconnectBackoff(reset = resetBackoff, immediate = resetBackoff)
        } else if (!isConnected.get() && candidateSockets.isEmpty() && isConnecting.get()) {
            isConnecting.set(false)
            reportConnectFailure(getEndpoints())
            scheduleReconnectBackoff(reset = resetBackoff)
        }
    }

    fun sendText(text: String, hash: String = SyncProtocol.sha256Hex(text)) {
        val socket = activeSocket ?: return
        val payload = JSONObject().apply {
            put("type", "clipboard")
            put("eventId", UUID.randomUUID().toString())
            put("originId", store.deviceId)
            put("text", text)
            put("hash", hash)
            put("timestamp", System.currentTimeMillis())
        }
        socket.send(payload.toString())
    }

    fun sendRename(deviceId: String, name: String) {
        val socket = activeSocket ?: return
        val payload = JSONObject().apply {
            put("type", "rename")
            put("id", deviceId)
            put("name", name)
        }
        socket.send(payload.toString())
    }

    fun sendOpenUrl(url: String, targetDeviceId: String? = null) {
        val socket = activeSocket ?: return
        val payload = JSONObject().apply {
            put("type", "openUrl")
            put("eventId", UUID.randomUUID().toString())
            put("originId", store.deviceId)
            put("url", url)
            if (targetDeviceId != null) {
                put("targetDeviceId", targetDeviceId)
            }
        }
        socket.send(payload.toString())
    }

    fun sendImage(image: ImagePayload) {
        val socket = activeSocket ?: return
        val packet = SyncProtocol.packImage(image, store.deviceId)
        socket.send(packet.toByteString())
        onTransferStatus("Sent image (${image.mimeType})")
    }

    fun close() {
        generation++
        pairingScan = false
        cancelPendingReconnect()
        stopHeartbeatWatch()
        closeCandidateSockets()
        activeSocket?.close(1000, "Closed by user")
        activeSocket = null
        activeEndpoint = null
        isConnected.set(false)
        isConnecting.set(false)
        policy.resetBackoff()
    }

    private fun applyRemoteUnpair() {
        generation++
        pairingScan = false
        store.unpair()
        cancelPendingReconnect()
        executor.execute {
            stopHeartbeatWatch()
            closeCandidateSockets()
            activeSocket?.close(1000, "Unpaired")
            activeSocket = null
            activeEndpoint = null
            isConnected.set(false)
            isConnecting.set(false)
            policy.resetBackoff()
            onDisconnected()
            onStatus("Not paired")
            onUnpaired()
        }
    }

    private fun closeCandidateSockets() {
        for ((_, sock) in candidateSockets) {
            sock.cancel()
        }
        candidateSockets.clear()
    }

    private fun cancelPendingReconnect() {
        reconnectTask?.cancel(false)
        reconnectTask = null
    }

    private fun scheduleReconnectBackoff(reset: Boolean = false, immediate: Boolean = false) {
        if (store.groupKey == null) return
        cancelPendingReconnect()
        if (reset) policy.resetBackoff()
        val delay = if (immediate) 0L else policy.nextBackoffSeconds()
        reconnectTask = executor.schedule({
            requestConnect(force = false, resetBackoff = false)
        }, delay, TimeUnit.SECONDS)
    }

    private fun noteActivity() {
        lastHeardMs = System.currentTimeMillis()
    }

    private fun startHeartbeatWatch() {
        stopHeartbeatWatch()
        noteActivity()
        heartbeatWatch = executor.scheduleWithFixedDelay({
            if (!isConnected.get()) return@scheduleWithFixedDelay
            if (SessionLiveness.isAlive(null, emptyList(), lastHeardMs, System.currentTimeMillis(), livenessBudgetMs())) {
                return@scheduleWithFixedDelay
            }
            val socket = activeSocket ?: return@scheduleWithFixedDelay
            Log.w(TAG, "Heartbeat timeout")
            socket.cancel()
            markActiveDeadIfNeeded(socket, activeEndpoint ?: "", "heartbeat timeout", resetBackoff = true)
        }, SyncProtocol.HEARTBEAT_INTERVAL_MS, SyncProtocol.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeatWatch() {
        heartbeatWatch?.cancel(false)
        heartbeatWatch = null
    }

    private fun applyRemoteEndpoints(incoming: List<String>) {
        if (incoming.isEmpty()) return
        store.peerCandidates = SyncProtocol.mergeAdvertisedEndpoints(store.peerCandidates, incoming)
        DiagnosticLog.info("Updated Mac endpoints: ${store.peerCandidates.joinToString()}")
    }

    private fun getEndpoints(): List<String> {
        val peer = store.peer
        val remembered = if (peer != null && peer.host.isNotBlank() && peer.host != "unknown") {
            "${peer.host}:${peer.port}"
        } else {
            null
        }
        return SyncProtocol.orderedConnectEndpoints(store.lastGoodEndpoint, store.peerCandidates, remembered)
    }

    private fun livenessBudgetMs(): Long =
        if (interactive) SyncProtocol.HEARTBEAT_BUDGET_MS else SyncProtocol.HEARTBEAT_SLEEP_BUDGET_MS

    private fun sendPowerLocked() {
        val socket = activeSocket ?: return
        val state = if (interactive) "awake" else "sleep"
        socket.send("""{"type":"power","state":"$state"}""")
    }
}
