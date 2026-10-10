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

internal class WebSocketClient(
    private val store: DeviceStore,
    private val transferStore: FileTransferStore,
    private val deviceNameProvider: () -> String,
    private val onText: (String) -> Unit,
    private val onOpenUrl: (String) -> Unit,
    private val onImage: (ImagePayload) -> Unit,
    private val onFile: (ReceivedFile) -> Unit,
    private val transferMessage: (TransferProgress) -> String,
    private val onTransferStatus: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onPeerIdentity: (String, String) -> Unit,
    private val onRosterChanged: (List<RememberedPeer>) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onUnpaired: () -> Unit,
    private val onRemoteSleep: () -> Unit = {},
    private val onProtocolFailure: (() -> Unit)? = null,
    private val onSyncConnected: ((Boolean, ClipboardClockSample?) -> Unit)? = null,
    private val onSyncControl: ((JSONObject) -> Unit)? = null,
    private val onSyncText: ((String, ClipboardStamp?) -> Unit)? = null,
    private val onSyncImage: ((ImagePayload, ClipboardStamp?) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "BinderClipWS"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(SyncProtocol.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val transferExecutor = Executors.newSingleThreadScheduledExecutor()
    private val transferSessions = HashMap<WebSocket, ChunkedTransferSession>()
    @Volatile private var supportsChunkedTransfers = false
    @Volatile private var clipboardSync = false
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
    private val secureChannels = ConcurrentHashMap<WebSocket, SecureChannel>()
    private val sendLock = Any()
    private val raceAuthClaimed = AtomicBoolean(false)
    private val policy = ReconnectPolicy()
    @Volatile private var lastHeardMs = 0L
    // Epoch ms of the last time the WebSocket was actually connected (0 = never in this process).
    // Used by the Bluetooth fallback to detect a stalled racer even when the backoff is small.
    @Volatile private var lastConnectedMs = 0L
    private var heartbeatWatch: ScheduledFuture<*>? = null
    @Volatile private var interactive = true
    @Volatile private var pairingScan = false
    @Volatile private var reconnectSuppressed = false
    @Volatile private var remoteSleepUntilMs = 0L

    fun isConnected(): Boolean = isConnected.get()
    fun supportsClipboardSync(): Boolean = isConnected.get() && clipboardSync
    fun connectedEndpoint(): String? = activeEndpoint
    fun currentBackoffSeconds(): Long = policy.delaySeconds
    fun isConnecting(): Boolean = isConnecting.get()
    fun lastConnectedAtMs(): Long = lastConnectedMs

    /// While Bluetooth is carrying the session, do not let background triggers re-race the
    /// WebSocket (NSD rediscovery, network changes, UI visibility, etc.). User-initiated
    /// reconnect (force) still works.
    fun setReconnectSuppressed(suppressed: Boolean) {
        val changed = reconnectSuppressed != suppressed
        reconnectSuppressed = suppressed
        if (changed) executor.execute {
            if (suppressed) cancelPendingReconnect()
            else if (!isConnected.get() && !isConnecting.get()) scheduleReconnectBackoff()
        }
    }

    fun setInteractive(value: Boolean) {
        if (interactive == value) return
        interactive = value
        executor.execute {
            if (value) remoteSleepUntilMs = 0L
            sendPowerLocked()
            if (isConnected.get()) startHeartbeatWatch()
        }
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
        remoteSleepUntilMs = 0L
        requestConnect(force = true, resetBackoff = true)
    }

    fun requestConnectResettingBackoff() {
        requestConnect(force = false, resetBackoff = true)
    }

    private fun requestConnect(force: Boolean, resetBackoff: Boolean) {
        executor.execute {
            if ((reconnectSuppressed || android.os.SystemClock.elapsedRealtime() < remoteSleepUntilMs) && !force) return@execute
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
        closeTransferSessions()
        supportsChunkedTransfers = false; clipboardSync = false
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
                    Log.d(TAG, "Socket opened; waiting for encrypted handshake")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (myGeneration != generation) return
                    try {
                        require(text.length <= if (isConnected.get() && activeSocket === webSocket) 8 * SyncProtocol.MAXIMUM_TEXT_BYTES + 16_384 else 16_384)
                        val objectJson = JSONObject(text)
                        if (objectJson.optString("type") == "secure_hello" && !secureChannels.containsKey(webSocket)) {
                            require(objectJson.optInt("version") == SyncProtocol.VERSION)
                            val serverNonce = java.util.Base64.getDecoder().decode(objectJson.getString("nonce"))
                            val clientNonce = SecureChannel.randomNonce()
                            val channel = SecureChannel(psk, serverNonce, clientNonce, "ws")
                            secureChannels[webSocket] = channel
                            webSocket.send(JSONObject().put("type", "secure_reply")
                                .put("nonce", java.util.Base64.getEncoder().encodeToString(clientNonce))
                                .put("proof", java.util.Base64.getEncoder().encodeToString(SecureChannel.proof(psk, serverNonce, clientNonce, "ws"))).toString())
                            sendSecureText(webSocket, JSONObject().put("type", "auth").put("deviceId", localId)
                                .put("deviceName", localName).put("platform", "Android").put("version", SyncProtocol.VERSION)
                                .put(FileTransferProtocol.CAPABILITY, FileTransferProtocol.VERSION)
                                .put(ClipboardStamp.CAPABILITY, 1).put("clockProbe", android.os.SystemClock.elapsedRealtime())
                                .put("pairing", pairingScan).toString())
                        } else {
                            require(objectJson.optString("type") == "secure")
                            val channel = secureChannels[webSocket] ?: error("Missing encrypted session")
                            val packet = java.util.Base64.getDecoder().decode(objectJson.getString("data"))
                            val plaintext = channel.open(packet, 1).toString(Charsets.UTF_8)
                            handleTextMessage(webSocket, endpoint, plaintext, myGeneration)
                        }
                    } catch (_: Exception) {
                        onProtocolFailure?.invoke() ?: onFailure("Secure connection failed. Update both apps or pair again.")
                        webSocket.cancel()
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (myGeneration != generation) return
                    try {
                        val channel = secureChannels[webSocket] ?: error("Missing encrypted session")
                        require(isConnected.get() && activeSocket === webSocket)
                        handleBinaryMessage(webSocket, channel.open(bytes.toByteArray(), 2).toByteString())
                    } catch (_: Exception) { webSocket.cancel() }
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
        if (reconnectSuppressed) return
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
                supportsChunkedTransfers = json.optInt(FileTransferProtocol.CAPABILITY) == FileTransferProtocol.VERSION
                clipboardSync = json.optInt(ClipboardStamp.CAPABILITY) == 1
                if (supportsChunkedTransfers) transferExecutor.execute {
                    if (activeSocket !== webSocket) return@execute
                    transferSessions[webSocket] = ChunkedTransferSession(
                        remoteId(json), transferStore, transferExecutor,
                        sendText = { activeSocket === webSocket && sendSecureText(webSocket, it.toString()) },
                        sendBinary = { activeSocket === webSocket && sendSecureBinary(webSocket, it) },
                        disconnect = { webSocket.cancel() },
                        onImage = onImage, onFile = onFile, onStatus = { onTransferStatus(transferMessage(it)) },
                        onOrderedImage = { image, stamp ->
                            if (activeSocket === webSocket) {
                                if (onSyncImage != null) onSyncImage.invoke(image, stamp) else onImage(image)
                            }
                        },
                    ).also { it.pump() }
                }
                activeEndpoint = endpoint
                lastConnectedMs = android.os.SystemClock.elapsedRealtime()
                isConnecting.set(false)
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

                onSyncConnected?.invoke(clipboardSync, clipboardClockSample(json, android.os.SystemClock.elapsedRealtime()))
                onPeerIdentity(remoteId, remoteName)
                onRosterChanged(store.members)
                onStatus(remoteName.ifBlank { "Connected" })
                DiagnosticLog.info("Connected to $remoteName via $endpoint")
                sendPowerLocked()
                startHeartbeatWatch()
            }

            "clipboard" -> {
                val clipText = json.optString("text")
                Log.d(TAG, "Received clipboard text (${clipText.length} characters)")
                if (clipText.isNotEmpty()) {
                    val stamp = ClipboardStamp.parse(json)
                    require(!clipboardSync || stamp != null)
                    if (onSyncText != null) onSyncText.invoke(clipText, stamp) else onText(clipText)
                }
            }

            "clipboard_state", "clipboard_request" -> {
                require(isConnected.get() && activeSocket === webSocket && clipboardSync)
                onSyncControl?.invoke(json)
            }

            "openUrl" -> {
                val url = json.optString("url")
                Log.d(TAG, "Received browser link")
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
                sendSecureText(webSocket, """{"type":"pong","t":$t}""")
            }

            "pong" -> {}

            "power" -> if (json.optString("state") == "sleep") onRemoteSleep()

            "unpair" -> {
                applyRemoteUnpair()
            }

            "transfer_offer", "transfer_ack", "transfer_done", "transfer_cancel" -> {
                require(isConnected.get() && activeSocket === webSocket && supportsChunkedTransfers)
                transferExecutor.execute { if (activeSocket === webSocket) transferSessions[webSocket]?.handle(json) }
            }
        }
    }

    private fun handleBinaryMessage(webSocket: WebSocket, bytes: ByteString) {
        noteActivity()
        val plaintext = bytes.toByteArray()
        if (FileTransferProtocol.isChunk(plaintext)) {
            require(supportsChunkedTransfers && plaintext.size <= FileTransferProtocol.HEADER_BYTES + FileTransferProtocol.CHUNK_BYTES)
            transferExecutor.execute { if (activeSocket === webSocket) transferSessions[webSocket]?.handleChunk(plaintext) }
            return
        }
        Log.i(TAG, "handleBinaryMessage: received ${bytes.size} bytes")
        val unpacked = SyncProtocol.unpackImage(plaintext)
        if (unpacked == null) {
            Log.w(TAG, "handleBinaryMessage: unpackImage failed for ${bytes.size} bytes")
            return
        }
        val meta = unpacked.first
        val imageData = unpacked.second
        val imagePayload = ImagePayload(id = meta.id, mimeType = meta.mimeType, data = imageData)
        Log.i(TAG, "handleBinaryMessage: unpacked ${imagePayload.mimeType} (${imageData.size} bytes)")
        if (onSyncImage != null) onSyncImage.invoke(imagePayload, meta.clipboard) else onImage(imagePayload)
        onTransferStatus("Received image (${meta.mimeType})")
    }

    private fun handleSocketClosed(webSocket: WebSocket, endpoint: String, reason: String) {
        executor.execute {
            markActiveDeadIfNeeded(webSocket, endpoint, reason, resetBackoff = false)
        }
    }

    private fun markActiveDeadIfNeeded(webSocket: WebSocket, endpoint: String, reason: String, resetBackoff: Boolean) {
        candidateSockets.remove(endpoint)
        secureChannels.remove(webSocket)
        transferExecutor.execute { transferSessions.remove(webSocket)?.close() }
        if (webSocket == activeSocket) {
            Log.w(TAG, "Active connection closed: $reason")
            stopHeartbeatWatch()
            activeSocket = null
            activeEndpoint = null
            isConnected.set(false)
            supportsChunkedTransfers = false; clipboardSync = false
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

    private fun sendSecureText(socket: WebSocket, text: String): Boolean = synchronized(sendLock) {
        val channel = secureChannels[socket] ?: return false
        val packet = channel.seal(text.toByteArray(Charsets.UTF_8), 1)
        socket.send(JSONObject().put("type", "secure").put("data", java.util.Base64.getEncoder().encodeToString(packet)).toString())
            .also { if (!it) socket.cancel() }
    }

    private fun remoteId(json: JSONObject): String = json.getString("deviceId").also { require(it.isNotBlank()) }

    private fun sendSecureBinary(socket: WebSocket, plaintext: ByteArray): Boolean = synchronized(sendLock) {
        val channel = secureChannels[socket] ?: return false
        // Chunk acknowledgement bounds file traffic; leave ample room for text and heartbeats.
        if (socket.queueSize() + plaintext.size + 24 > 12L * 1024 * 1024) { socket.cancel(); return false }
        socket.send(channel.seal(plaintext, 2).toByteString()).also { if (!it) socket.cancel() }
    }

    fun resumeQueuedTransfers() {
        val socket = activeSocket ?: return
        if (!supportsChunkedTransfers) {
            if (transferStore.next(store.peer?.deviceId.orEmpty()) != null) onTransferStatus(transferMessage(TransferProgress(TransferProgress.State.UPDATE_REQUIRED)))
            return
        }
        transferExecutor.execute { transferSessions[socket]?.pump() }
    }

    private fun closeTransferSessions() {
        transferExecutor.execute { transferSessions.values.forEach { it.close() }; transferSessions.clear() }
    }

    fun sendClipboardControl(payload: JSONObject): Boolean {
        if (!supportsClipboardSync()) return false
        val socket = activeSocket ?: return false
        return sendSecureText(socket, payload.toString())
    }

    fun sendText(text: String, hash: String = SyncProtocol.sha256Hex(text), stamp: ClipboardStamp? = null): Boolean {
        val socket = activeSocket ?: return false
        val payload = JSONObject().apply {
            put("type", "clipboard")
            put("eventId", UUID.randomUUID().toString())
            put("originId", store.deviceId)
            put("text", text)
            put("hash", hash)
            put("timestamp", System.currentTimeMillis())
            if (stamp != null) stamp.putInto(this) else put("shared", 1)
        }
        return sendSecureText(socket, payload.toString())
    }

    fun sendRename(deviceId: String, name: String) {
        val socket = activeSocket ?: return
        val payload = JSONObject().apply {
            put("type", "rename")
            put("id", deviceId)
            put("name", name)
        }
        sendSecureText(socket, payload.toString())
    }

    fun sendOpenUrl(url: String, targetDeviceId: String? = null): Boolean {
        val socket = activeSocket ?: return false
        val payload = JSONObject().apply {
            put("type", "openUrl")
            put("eventId", UUID.randomUUID().toString())
            put("originId", store.deviceId)
            put("url", url)
            if (targetDeviceId != null) {
                put("targetDeviceId", targetDeviceId)
            }
        }
        return sendSecureText(socket, payload.toString())
    }

    fun sendImage(image: ImagePayload, stamp: ClipboardStamp? = null): Boolean {
        val socket = activeSocket ?: return false
        if (supportsChunkedTransfers) {
            val peerId = store.peer?.deviceId?.takeIf { it.isNotBlank() } ?: return false
            return runCatching {
                image.data.inputStream().use { transferStore.stage(peerId, "image", image.mimeType, "image", it, stamp) }
                resumeQueuedTransfers()
                true
            }.getOrElse { onTransferStatus(transferMessage(TransferProgress(TransferProgress.State.FAILED))); false }
        }
        // Older Macs cannot acknowledge chunks. Do not overflow OkHttp's 16 MiB send queue.
        if (image.data.size > 8 * 1024 * 1024) {
            onTransferStatus(transferMessage(TransferProgress(TransferProgress.State.UPDATE_REQUIRED)))
            return false
        }
        val packet = SyncProtocol.packImage(image, store.deviceId, stamp)
        val queued = sendSecureBinary(socket, packet)
        if (queued) onTransferStatus("Sent image (${image.mimeType})")
        return queued
    }

    fun shutdown() {
        close()
        transferExecutor.shutdownNow()
        executor.shutdownNow()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    fun close() {
        generation++
        pairingScan = false
        cancelPendingReconnect()
        stopHeartbeatWatch()
        closeCandidateSockets()
        closeTransferSessions()
        supportsChunkedTransfers = false; clipboardSync = false
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
        val socket = activeSocket
        activeSocket = null
        supportsChunkedTransfers = false; clipboardSync = false
        closeTransferSessions()
        store.unpair()
        cancelPendingReconnect()
        executor.execute {
            stopHeartbeatWatch()
            closeCandidateSockets()
            socket?.close(1000, "Unpaired")
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
        secureChannels.clear()
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
        if (reconnectSuppressed) return
        cancelPendingReconnect()
        if (reset) policy.resetBackoff()
        val delay = if (immediate) 0L else policy.nextBackoffSeconds(interactive)
        val sleepingDelay = ((remoteSleepUntilMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999) / 1000
        val jitter = if (delay > 1) java.util.concurrent.ThreadLocalRandom.current().nextLong(delay / 5 + 1) else 0L
        reconnectTask = executor.schedule({
            requestConnect(force = false, resetBackoff = false)
        }, maxOf(delay + jitter, sleepingDelay), TimeUnit.SECONDS)
    }

    fun deferReconnect(durationMs: Long) {
        remoteSleepUntilMs = android.os.SystemClock.elapsedRealtime() + durationMs
        executor.execute { scheduleReconnectBackoff() }
    }

    private fun noteActivity() {
        lastHeardMs = android.os.SystemClock.elapsedRealtime()
    }

    private fun startHeartbeatWatch() {
        stopHeartbeatWatch()
        noteActivity()
        heartbeatWatch = executor.scheduleWithFixedDelay({
            if (!isConnected.get()) return@scheduleWithFixedDelay
            if (SessionLiveness.isAlive(null, emptyList(), lastHeardMs, android.os.SystemClock.elapsedRealtime(), livenessBudgetMs())) {
                if (android.os.SystemClock.elapsedRealtime() - lastConnectedMs > 30_000) policy.resetBackoff()
                return@scheduleWithFixedDelay
            }
            val socket = activeSocket ?: return@scheduleWithFixedDelay
            Log.w(TAG, "Heartbeat timeout")
            socket.cancel()
            markActiveDeadIfNeeded(socket, activeEndpoint ?: "", "heartbeat timeout", resetBackoff = false)
        }, PowerPolicy.heartbeatMs(interactive), PowerPolicy.heartbeatMs(interactive), TimeUnit.MILLISECONDS)
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
        PowerPolicy.budgetMs(interactive)

    private fun sendPowerLocked() {
        val socket = activeSocket ?: return
        val state = if (interactive) "awake" else "sleep"
        sendSecureText(socket, """{"type":"power","state":"$state"}""")
    }
}
