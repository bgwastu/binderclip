package net.wastu.binderclip

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tier policy for the Bluetooth fallback: Wi-Fi/mesh always wins, Bluetooth engages only after the
 * WebSocket racer has exhausted its candidates (backoff reached the unreachable threshold) or when
 * no network transport exists at all.
 */
object BtPolicy {
    const val FALLBACK_THRESHOLD_SECONDS = 8L

    enum class Decision { IDLE, LISTEN_BT, KEEP_WIFI, PROMPT_ENABLE_BT, NEEDS_PERMISSION }

    fun decide(
        paired: Boolean,
        fallbackEnabled: Boolean,
        wsConnected: Boolean,
        backoffSeconds: Long,
        networkAvailable: Boolean,
        btAdapterOn: Boolean,
        permissionGranted: Boolean,
    ): Decision = when {
        !paired || !fallbackEnabled || wsConnected -> Decision.IDLE
        !permissionGranted -> Decision.NEEDS_PERMISSION
        !btAdapterOn -> Decision.PROMPT_ENABLE_BT
        !networkAvailable || backoffSeconds >= FALLBACK_THRESHOLD_SECONDS -> Decision.LISTEN_BT
        else -> Decision.IDLE
    }
}

/**
 * Bluetooth Classic RFCOMM fallback link. The phone HOSTS the server socket (Android owns SDP
 * publishing); the Mac dials in opportunistically while no session exists. Wire format is the
 * shared CBOR annex: [u32be length][BtCbor map]. Session semantics (PSK auth first frame,
 * ping/pong heartbeat, twin-session replacement) mirror the WebSocket path.
 */
class BluetoothLink(
    context: Context,
    private val store: DeviceStore,
    private val deviceNameProvider: () -> String,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onAuthenticated(remoteId: String, remoteName: String)
        fun onLinkDown(reason: String)
        fun onText(text: String)
        fun onOpenUrl(url: String)
        fun onUnpair()
    }

    private val appContext = context.applicationContext
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "binderclip-bt").apply { isDaemon = true }
    }
    private val listening = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var lastHeardMs = 0L
    @Volatile private var interactive = true
    private var heartbeat: ScheduledFuture<*>? = null
    private val frameReader = BtFrameIo.Reader()
    private val writeLock = Any()

    private fun adapter(): BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED

    fun adapterEnabled(): Boolean = adapter()?.isEnabled == true

    fun isConnected(): Boolean = connected.get()

    fun isListening(): Boolean = listening.get()

    /** Idempotently ensures the accept loop runs; safe to call from every evaluation pass. */
    fun startListening(): Boolean {
        if (!hasPermission() || !adapterEnabled() || store.groupKey == null) return false
        synchronized(this) {
            if (listening.get() && serverSocket != null) return true
            // Only one live SDP record/server socket may exist; close any stale one first so the
            // peer never resolves an old channel number.
            val stale = serverSocket
            serverSocket = null
            if (stale != null) runCatching { stale.close() }
            listening.set(true)
        }
        var server: BluetoothServerSocket? = null
        executor.execute {
            try {
                server = adapter()?.listenUsingRfcommWithServiceRecord(
                    "BinderClip", UUID.fromString(SyncProtocol.BT_SERVICE_UUID)
                ) ?: run { synchronized(this) { listening.set(false) }; return@execute }
                synchronized(this) { serverSocket = server }
                while (listening.get()) {
                    val accepted = try {
                        server!!.accept()
                    } catch (_: IOException) {
                        break
                    } catch (_: SecurityException) {
                        break
                    }
                    if (!listening.get()) {
                        runCatching { accepted.close() }
                        break
                    }
                    handleAccepted(accepted)
                }
            } catch (_: SecurityException) {
            } catch (_: IOException) {
            } finally {
                synchronized(this) {
                    listening.set(false)
                    if (serverSocket === server) {
                        runCatching { serverSocket?.close() }
                        serverSocket = null
                    }
                }
            }
        }
        return true
    }

    fun stop() {
        synchronized(this) {
            listening.set(false)
            connected.set(false)
            stopHeartbeat()
            runCatching { socket?.close() }
            runCatching { serverSocket?.close() }
            socket = null
            serverSocket = null
            input = null
            output = null
            frameReader.reset()
        }
    }

    fun setInteractive(value: Boolean) {
        interactive = value
    }

    fun sendClipboard(text: String): Boolean = sendFrame(listOf(
        "type" to "clipboard",
        "text" to text,
    ))

    fun sendOpenUrl(url: String): Boolean = sendFrame(listOf(
        "type" to "openUrl",
        "url" to url,
    ))

    fun sendPowerState(state: String): Boolean = sendFrame(listOf(
        "type" to "power",
        "state" to state,
    ))

    private fun sendFrame(fields: List<Pair<String, Any>>): Boolean {
        if (!connected.get()) return false
        val out = output ?: return false
        return try {
            val payload = BtCbor.encode(fields)
            synchronized(writeLock) { out.write(BtFrameIo.frame(payload)); out.flush() }
            true
        } catch (_: IOException) {
            drop("write failed")
            false
        }
    }

    private fun sendAuth(deviceId: String) {
        val pskBytes = store.groupKey ?: throw IllegalStateException("not paired")
        sendFrameUnlocked(listOf(
            "type" to "auth",
            "psk" to SyncProtocol.urlSafeBase64(pskBytes),
            "deviceId" to deviceId,
            "deviceName" to deviceNameProvider(),
            "version" to SyncProtocol.VERSION,
        ))
    }

    /** Auth must go out before [connected] flips true, bypassing the connected guard once. */
    private fun sendFrameUnlocked(fields: List<Pair<String, Any>>) {
        val out = output ?: return
        try {
            synchronized(writeLock) { out.write(BtFrameIo.frame(BtCbor.encode(fields))); out.flush() }
        } catch (_: IOException) {
            drop("auth write failed")
        }
    }

    private fun handleAccepted(accepted: BluetoothSocket) {
        if (connected.get()) {
            runCatching { accepted.close() }
            return
        }
        synchronized(this) {
            if (socket != null) {
                // A connection is already in progress; reject the duplicate the Mac opens when it
                // multiplexes more than one RFCOMM channel onto our single listener.
                runCatching { accepted.close() }
                return
            }
            socket = accepted
        }
        input = accepted.inputStream
        output = accepted.outputStream
        lastHeardMs = System.currentTimeMillis()
        val remoteId = store.deviceId
        sendAuth(remoteId)

        val authenticated = java.util.concurrent.CountDownLatch(1)
        val authResult = arrayOfNulls<Pair<String, String>>(1)
        val readThread = Thread({
            readLoop(onAuthOk = { id, name ->
                authResult[0] = id to name
                authenticated.countDown()
            })
        }, "binderclip-bt-read").apply { isDaemon = true }
        readThread.start()

        if (!authenticated.await(SyncProtocol.AUTH_DEADLINE_MS + 2_000L, TimeUnit.MILLISECONDS)) {
            drop("auth timeout")
            return
        }
        val (id, name) = authResult[0]!!
        connected.set(true)
        callbacks.onAuthenticated(id, name)
        startHeartbeat(id)
    }

    private fun readLoop(onAuthOk: (String, String) -> Unit) {
        val stream = input ?: return
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val count = stream.read(buffer)
                if (count <= 0) break
                noteActivity()
                for (payload in frameReader.feed(buffer.copyOf(count))) {
                    handleFrame(payload, onAuthOk)
                }
            }
            drop("remote closed")
        } catch (_: IOException) {
            drop("read failed")
        }
    }

    private fun handleFrame(payload: ByteArray, onAuthOk: (String, String) -> Unit) {
        val fields = try {
            BtCbor.decode(payload)
        } catch (_: Exception) {
            return
        }
        val type = fields.firstOrNull { it.first == "type" }?.second as? String ?: return
        fun text(key: String) = fields.firstOrNull { it.first == key }?.second as? String
        fun uint(key: String) = fields.firstOrNull { it.first == key }?.second as? Long
        when (type) {
            "auth_ok" -> {
                val id = text("deviceId") ?: ""
                val name = text("deviceName") ?: "Mac"
                onAuthOk(id, name)
            }
            "clipboard" -> text("text")?.let(callbacks::onText)
            "openUrl" -> text("url")?.let(callbacks::onOpenUrl)
            "unpair" -> callbacks.onUnpair()
            "ping" -> sendFrame(listOf("type" to "pong", "t" to (uint("t") ?: 0L)))
            "pong" -> Unit
            "power" -> Unit
        }
    }

    private fun noteActivity() {
        lastHeardMs = System.currentTimeMillis()
    }

    private fun startHeartbeat(remoteId: String) {
        stopHeartbeat()
        heartbeat = executor.scheduleWithFixedDelay({
            if (!connected.get()) return@scheduleWithFixedDelay
            val budget = if (interactive) SyncProtocol.HEARTBEAT_BUDGET_MS else SyncProtocol.HEARTBEAT_SLEEP_BUDGET_MS
            if (System.currentTimeMillis() - lastHeardMs > budget) {
                drop("heartbeat timeout")
                return@scheduleWithFixedDelay
            }
            sendFrame(listOf("type" to "ping", "t" to System.currentTimeMillis()))
        }, SyncProtocol.HEARTBEAT_INTERVAL_MS, SyncProtocol.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel(false)
        heartbeat = null
    }

    private fun drop(reason: String) {
        val wasConnected = connected.getAndSet(false)
        val hadSocket = socket != null
        stopHeartbeat()
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        frameReader.reset()
        if (wasConnected || hadSocket) callbacks.onLinkDown(reason)
    }
}
