package net.wastu.binderclip

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    /// Arm Bluetooth when the WebSocket has been down this long even if the backoff timer is still
    /// small. NSD re-discovery, heartbeat deaths, and UI toggles all reset the racer's backoff to
    /// 1s, so the backoff alone is an unreliable signal for "Wi-Fi is genuinely dead".
    const val FALLBACK_AFTER_STALL_MS = 20_000L

    enum class Decision { IDLE, LISTEN_BT, KEEP_WIFI, PROMPT_ENABLE_BT, NEEDS_PERMISSION }

    fun decide(
        paired: Boolean,
        fallbackEnabled: Boolean,
        wsConnected: Boolean,
        btConnected: Boolean,
        backoffSeconds: Long,
        networkAvailable: Boolean,
        btAdapterOn: Boolean,
        permissionGranted: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        lastWsConnectedMs: Long = 0L,
    ): Decision = when {
        !paired || !fallbackEnabled || wsConnected || btConnected -> Decision.IDLE
        !permissionGranted -> Decision.NEEDS_PERMISSION
        !btAdapterOn -> Decision.PROMPT_ENABLE_BT
        !networkAvailable -> Decision.LISTEN_BT
        // Wi-Fi exists but the racer keeps failing (stalled or backoff exhausted): arm Bluetooth too
        // so a usable (e.g. mesh-tailscale) path can still carry text instead of hanging forever.
        backoffSeconds >= FALLBACK_THRESHOLD_SECONDS ||
            nowMs - lastWsConnectedMs >= FALLBACK_AFTER_STALL_MS -> Decision.LISTEN_BT
        else -> Decision.IDLE
    }
}

/**
 * Modern BLE Central client for BinderClip.
 * Connects to the macOS BLE Peripheral (advertising SyncProtocol.BT_SERVICE_UUID),
 * opens an L2CAP Channel (with GATT fallback), authenticates via PSK, and syncs clipboard.
 */
class BluetoothLink(
    context: Context,
    private val store: DeviceStore,
    private val deviceNameProvider: () -> String,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onAuthenticated(remoteId: String, remoteName: String)
        /** The Mac echoed its live endpoints inside the BT auth_ok — use them to bootstrap a
         *  faster WebSocket path (mesh/LAN) without scanning a QR. */
        fun onEndpoints(endpoints: List<String>)
        fun onLinkDown(reason: String)
        fun onText(text: String)
        fun onOpenUrl(url: String)
        fun onRemoteSleep() {}
        fun onUnpair()
    }

    private val appContext = context.applicationContext
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "binderclip-ble").apply { isDaemon = true }
    }
    private val scanning = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var notifyChar: BluetoothGattCharacteristic? = null
    @Volatile private var isL2capActive = false

    @Volatile private var lastHeardMs = 0L
    @Volatile private var lastAuthenticatedFrameMs = 0L
    @Volatile private var interactive = true
    private var heartbeat: ScheduledFuture<*>? = null
    private val frameReader = BtFrameIo.Reader()
    private val writeLock = Any()
    private val retry = ScanRetryPolicy()
    @Volatile private var attemptGeneration = 0L
    @Volatile private var deferredUntilMs = 0L
    private var attemptTimeout: ScheduledFuture<*>? = null
    private var scanTimeout: ScheduledFuture<*>? = null
    @Volatile private var pendingSocket: BluetoothSocket? = null
    @Volatile private var secure: SecureChannel? = null
    private var activeScanCallback: ScanCallback? = null
    private val gattWrites = java.util.ArrayDeque<ByteArray>()
    private var gattWriteInFlight = false
    @Volatile private var mtu = 23
    private val notificationGate = GattHandshakeGate()
    private var incomingGattBytes = 0
    private var gattBulkSending = false
    private var requestedPriority = -1
    private var scanStartedMs = 0L

    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val serviceUuid = UUID.fromString(SyncProtocol.BT_SERVICE_UUID)
    private val psmCharUuid = UUID.fromString(SyncProtocol.BT_PSM_CHAR_UUID)
    private val writeCharUuid = UUID.fromString(SyncProtocol.BT_WRITE_CHAR_UUID)
    private val notifyCharUuid = UUID.fromString(SyncProtocol.BT_NOTIFY_CHAR_UUID)

    private fun adapter(): BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun adapterEnabled(): Boolean = try { adapter()?.isEnabled == true } catch (_: SecurityException) { false }

    /** Try to switch the radio on when the fallback is active. Best effort; returns
     *  false when permission is missing or the OEM blocks silent enable, so the
     *  caller can fall back to guiding the user. */
    @Suppress("DEPRECATION")
    fun requestEnable(): Boolean {
        if (!AppProfile.current.canEnableBluetoothRadio) return false
        val adapter = adapter() ?: return false
        retry.failed(android.os.SystemClock.elapsedRealtime())
        return try {
            adapter.enable()
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun isConnected(): Boolean = connected.get()

    fun isListening(): Boolean = scanning.get() || connecting.get()

    /** Alias for startConnecting, matching the existing service evaluator contract. */
    fun startListening(): Boolean = startConnecting()

    fun canRetry(): Boolean = android.os.SystemClock.elapsedRealtime().let { it >= deferredUntilMs && retry.canAttempt(it) }

    fun resetRetry() { retry.reset(); deferredUntilMs = 0L }

    fun deferReconnect(durationMs: Long) {
        deferredUntilMs = android.os.SystemClock.elapsedRealtime() + durationMs
        stop()
    }

    fun startConnecting(): Boolean {
        if (!canRetry()) return false
        if (!hasPermission() || !adapterEnabled() || store.groupKey == null) return false
        if (connected.get() || connecting.get() || scanning.get()) return true

        val scanner = try {
            adapter()?.bluetoothLeScanner
        } catch (_: SecurityException) {
            return false
        } ?: return false

        if (!scanning.compareAndSet(false, true)) return true
        val myAttempt = ++attemptGeneration
        val callback = scanCallback(myAttempt)
        activeScanCallback = callback
        scanStartedMs = android.os.SystemClock.elapsedRealtime()
        EnergyCounters.add("ble.scan.started")
        DiagnosticLog.info("Starting BLE scan for service $serviceUuid")
        android.util.Log.i("BinderClipBLE", "Starting BLE scan for service $serviceUuid")
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (interactive) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        executor.execute {
            try {
                if (myAttempt != attemptGeneration || !scanning.get()) return@execute
                scanner.startScan(listOf(filter), settings, callback)
            } catch (e: SecurityException) {
                DiagnosticLog.error("BLE scan security exception: ${e.message}")
                drop("scan failed")
            } catch (e: Exception) {
                DiagnosticLog.error("BLE scan exception: ${e.message}")
                drop("scan failed")
            }
        }
        // If no peripheral is found within a full scan window, release the scanning latch so the
        // evaluator can retry with a fresh scan (some radios drop scans silently).
        scanTimeout?.cancel(false)
        scanTimeout = executor.schedule({
            synchronized(writeLock) {
                if (myAttempt == attemptGeneration && scanning.get() && !connecting.get() && !connected.get()) {
                    DiagnosticLog.warning("BLE scan found nothing; re-arming")
                    drop("scan timeout")
                }
            }
        }, PowerPolicy.SCAN_WINDOW_MS, TimeUnit.MILLISECONDS)
        return true
    }

    private fun scanCallback(generation: Long) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (generation != attemptGeneration || !scanning.get()) return
            val device = result?.device ?: return
            DiagnosticLog.info("Found BinderClip BLE peripheral: ${device.address}")
            android.util.Log.i("BinderClipBLE", "Found BinderClip BLE peripheral: ${device.address} (${result.scanRecord?.deviceName})")
            stopScan()
            connectDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            if (generation != attemptGeneration) return
            DiagnosticLog.warning("BLE scan failed with error code $errorCode")
            android.util.Log.w("BinderClipBLE", "BLE scan failed: $errorCode")
            drop("scan failed ($errorCode)")
        }
    }

    private fun stopScan() {
        scanTimeout?.cancel(false)
        scanTimeout = null
        if (scanning.getAndSet(false)) {
            EnergyCounters.add("ble.scan.ms", android.os.SystemClock.elapsedRealtime() - scanStartedMs)
            try {
                adapter()?.bluetoothLeScanner?.stopScan(activeScanCallback)
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
    }

    private fun connectDevice(device: BluetoothDevice) {
        if (connected.get() || connecting.get()) return
        if (!connecting.compareAndSet(false, true)) return
        val myAttempt = attemptGeneration
        EnergyCounters.add("ble.gatt.attempt")
        DiagnosticLog.info("Connecting to BLE GATT ${device.address}...")
        android.util.Log.i("BinderClipBLE", "Connecting to BLE GATT ${device.address}")
        executor.execute {
            if (myAttempt != attemptGeneration) return@execute
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val handle = device.connectGatt(appContext, false, gattCallback(myAttempt), BluetoothDevice.TRANSPORT_LE)
                    synchronized(writeLock) {
                        if (myAttempt != attemptGeneration || (!connecting.get() && !connected.get())) handle?.close()
                        else gatt = handle
                    }
                } else {
                    val handle = device.connectGatt(appContext, false, gattCallback(myAttempt))
                    synchronized(writeLock) {
                        if (myAttempt != attemptGeneration || (!connecting.get() && !connected.get())) handle?.close()
                        else gatt = handle
                    }
                }
            } catch (e: SecurityException) {
                DiagnosticLog.error("GATT connect security error: ${e.message}")
                drop("GATT connect failed")
            } catch (e: Exception) {
                DiagnosticLog.error("GATT connect error: ${e.message}")
                drop("GATT connect failed")
            }
        }
        // If connectGatt never reports a state (some radios drop the callback), release the
        // connecting latch so the evaluator can re-arm the scan instead of hanging forever.
        attemptTimeout?.cancel(false)
        attemptTimeout = executor.schedule({
            synchronized(writeLock) {
                if (myAttempt == attemptGeneration && connecting.get() && !connected.get()) {
                    DiagnosticLog.warning("BLE GATT connect stalled; re-arming scanner")
                    drop("connection/authentication deadline")
                }
            }
        }, 20, TimeUnit.SECONDS)
    }

    private fun refreshDeviceCache(gattInstance: BluetoothGatt): Boolean = runCatching {
        val method = gattInstance.javaClass.getMethod("refresh")
        method.invoke(gattInstance) as? Boolean ?: false
    }.getOrDefault(false)

    private var discoveryRetries = 0
    private var discoveryStarted = false

    private fun discoverOnce(target: BluetoothGatt) = synchronized(writeLock) {
        if (gatt !== target || discoveryStarted) return@synchronized
        discoveryStarted = true
        try { if (!target.discoverServices()) drop("service discovery could not queue") }
        catch (_: SecurityException) { drop("Bluetooth permission revoked") }
        catch (_: Exception) { drop("service discovery failed") }
    }

    private fun currentGatt(attempt: Long, target: BluetoothGatt): Boolean = synchronized(writeLock) {
        if (attempt != attemptGeneration || (!connecting.get() && !connected.get())) return false
        // Some stacks deliver the first callback before connectGatt returns its handle.
        if (gatt == null) gatt = target
        gatt === target
    }

    @Suppress("DEPRECATION")
    private fun gattCallback(attempt: Long) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gattInstance: BluetoothGatt, status: Int, newState: Int) {
            if (!currentGatt(attempt, gattInstance)) return
            DiagnosticLog.info("GATT connection state: status=$status, newState=$newState")
            android.util.Log.i("BinderClipBLE", "GATT state change: status=$status, newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val bonded = try { gattInstance.device.bondState == BluetoothDevice.BOND_BONDED }
                    catch (_: SecurityException) { false }
                if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION && bonded) {
                    DiagnosticLog.warning("Bluetooth authentication failed for a bonded Mac. If Android reports a lost bond, forget and re-pair the Mac in system Bluetooth settings.")
                }
                drop("GATT connection rejected (status=$status)")
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                discoveryRetries = 0
                discoveryStarted = false
                try {
                    gattInstance.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                    gattInstance.requestMtu(512)
                } catch (_: SecurityException) {
                }
                executor.schedule({
                    try {
                        discoverOnce(gattInstance)
                    } catch (_: SecurityException) {
                    }
                }, 1500, TimeUnit.MILLISECONDS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                drop("ble disconnected (status=$status)")
            }
        }

        override fun onMtuChanged(gattInstance: BluetoothGatt, mtu: Int, status: Int) {
            if (!currentGatt(attempt, gattInstance)) return
            if (status == BluetoothGatt.GATT_SUCCESS) this@BluetoothLink.mtu = mtu
            DiagnosticLog.info("GATT MTU result: mtu=$mtu, status=$status")
            executor.schedule({
                try {
                    discoverOnce(gattInstance)
                } catch (_: SecurityException) {
                }
            }, 300, TimeUnit.MILLISECONDS)
        }

        override fun onServicesDiscovered(gattInstance: BluetoothGatt, status: Int) {
            if (!currentGatt(attempt, gattInstance)) return
            DiagnosticLog.info("GATT services discovered: status=$status (${gattInstance.services.size} services)")
            android.util.Log.i("BinderClipBLE", "Services discovered: status=$status, count=${gattInstance.services.size}")
            for (s in gattInstance.services) {
                android.util.Log.i("BinderClipBLE", " - Discovered Service: ${s.uuid}")
                for (c in s.characteristics) {
                    android.util.Log.i("BinderClipBLE", "    -> Characteristic: ${c.uuid} (props=${c.properties})")
                }
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                drop("service discovery failed")
                return
            }
            val service = gattInstance.services.firstOrNull { it.uuid == serviceUuid }
                ?: gattInstance.getService(serviceUuid)
            if (service == null) {
                if (discoveryRetries < 3) {
                    discoveryRetries++
                    DiagnosticLog.warning("BinderClip service not in current cache, retrying discovery ($discoveryRetries/3)...")
                    android.util.Log.w("BinderClipBLE", "Retrying service discovery ($discoveryRetries/3)")
                    executor.schedule({
                        try {
                            if (!currentGatt(attempt, gattInstance)) return@schedule
                            refreshDeviceCache(gattInstance)
                            discoveryStarted = false
                            discoverOnce(gattInstance)
                        } catch (_: SecurityException) {
                        }
                    }, 500, TimeUnit.MILLISECONDS)
                    return
                }
                DiagnosticLog.warning("BinderClip service not found on peripheral (found: ${gattInstance.services.map { it.uuid }})")
                drop("service not found")
                return
            }

            writeChar = service.getCharacteristic(writeCharUuid)
            notifyChar = service.getCharacteristic(notifyCharUuid)

            val psmChar = service.getCharacteristic(psmCharUuid)
            if (psmChar != null) {
                DiagnosticLog.info("Reading PSM characteristic...")
                try {
                    gattInstance.readCharacteristic(psmChar)
                } catch (_: SecurityException) {
                    setupGattFallback(gattInstance)
                }
            } else {
                setupGattFallback(gattInstance)
            }
        }

        override fun onCharacteristicRead(
            gattInstance: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (!currentGatt(attempt, gattInstance)) return
            if (characteristic.uuid == psmCharUuid && status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic.value
                val psm = if (data != null && data.size >= 2) {
                    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                } else 0
                DiagnosticLog.info("Read PSM from Mac: $psm")
                android.util.Log.i("BinderClipBLE", "Read PSM from Mac: $psm")

                if (psm > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    executor.execute {
                        connectL2cap(gattInstance.device, psm, gattInstance)
                    }
                } else {
                    setupGattFallback(gattInstance)
                }
            } else {
                setupGattFallback(gattInstance)
            }
        }

        override fun onDescriptorWrite(
            gattInstance: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (!currentGatt(attempt, gattInstance)) return
            if (descriptor.characteristic.uuid == notifyCharUuid && status == BluetoothGatt.GATT_SUCCESS) {
                synchronized(writeLock) {
                    DiagnosticLog.info("GATT notify enabled; awaiting encrypted handshake")
                    notificationGate.subscribed()?.let(::handleFrame)
                }
            }
        }

        override fun onCharacteristicWrite(gattInstance: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!currentGatt(attempt, gattInstance)) return
            executor.execute {
                synchronized(writeLock) {
                    if (!currentGatt(attempt, gattInstance)) return@execute
                    if (status != BluetoothGatt.GATT_SUCCESS) { drop("GATT write rejected"); return@execute }
                    if (gattWrites.isNotEmpty()) gattWrites.removeFirst()
                    gattWriteInFlight = false
                    flushGattWrites()
                    if (gattWrites.isEmpty() && gattBulkSending) {
                        gattBulkSending = false
                        updateConnectionPriority()
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gattInstance: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receiveGattChunk(gattInstance, characteristic, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gattInstance: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < 33) characteristic.value?.let { receiveGattChunk(gattInstance, characteristic, it.copyOf()) }
        }

        private fun receiveGattChunk(gattInstance: BluetoothGatt, characteristic: BluetoothGattCharacteristic, chunk: ByteArray) {
            if (!currentGatt(attempt, gattInstance)) return
            if (characteristic.uuid == notifyCharUuid && !isL2capActive) {
                try {
                    synchronized(writeLock) {
                        EnergyCounters.add("ble.receive.bytes", chunk.size.toLong())
                        if (connected.get() && secure != null) noteActivity()
                        incomingGattBytes += chunk.size
                        val payloads = frameReader.feed(chunk)
                        if (payloads.isNotEmpty()) incomingGattBytes = 0
                        if (!updateConnectionPriority()) return
                        for (payload in payloads) notificationGate.receive(payload)?.let(::handleFrame)
                    }
                } catch (_: Exception) { drop("invalid BLE framing") }
            }
        }
    }

    /** L2CAP connect() can block for a long time on some radios; run it off the serial
     *  executor so pending scan/GATT work is never starved while the channel negotiates. */
    private fun connectL2cap(device: BluetoothDevice, psm: Int, gattInstance: BluetoothGatt) {
        val myAttempt = attemptGeneration
        if (gatt !== gattInstance) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setupGattFallback(gattInstance)
            return
        }
        Thread({
            runCatching {
                DiagnosticLog.info("Opening L2CAP channel (PSM $psm)...")
                android.util.Log.i("BinderClipBLE", "Opening L2CAP channel PSM $psm")
                val l2capSocket = device.createInsecureL2capChannel(psm)
                synchronized(writeLock) {
                    if (myAttempt != attemptGeneration || gatt !== gattInstance) { l2capSocket.close(); return@runCatching }
                    pendingSocket = l2capSocket
                }
                l2capSocket.connect()
                synchronized(writeLock) {
                    if (myAttempt != attemptGeneration || gatt !== gattInstance) { l2capSocket.close(); return@runCatching }
                    pendingSocket = null
                    socket = l2capSocket
                    input = l2capSocket.inputStream
                    output = l2capSocket.outputStream
                    isL2capActive = true
                }
                DiagnosticLog.info("L2CAP channel connected; awaiting encrypted handshake")
                android.util.Log.i("BinderClipBLE", "L2CAP connected; awaiting encrypted handshake")
                readL2capLoop()
            }.onFailure { e ->
                if (myAttempt != attemptGeneration) return@onFailure
                if (connected.get()) { drop("active L2CAP failed"); return@onFailure }
                synchronized(writeLock) {
                    runCatching { pendingSocket?.close() }; pendingSocket = null
                    runCatching { socket?.close() }; socket = null
                    input = null; output = null; isL2capActive = false
                    secure = null; frameReader.reset()
                }
                DiagnosticLog.warning("L2CAP connection failed (${e.message}), falling back to GATT stream")
                android.util.Log.w("BinderClipBLE", "L2CAP failed, falling back to GATT", e)
                // GATT operations must run on the thread the GATT callbacks use (main).
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (myAttempt == attemptGeneration && gatt === gattInstance) setupGattFallback(gattInstance)
                }
            }
        }, "binderclip-ble-l2cap-connect").apply { isDaemon = true }.start()
    }

    private fun setupGattFallback(gattInstance: BluetoothGatt) {
        if (gatt !== gattInstance) return
        DiagnosticLog.info("Configuring GATT fallback stream...")
        val notify = notifyChar
        if (notify == null) {
            drop("notify char missing")
            return
        }
        try {
            if (!gattInstance.setCharacteristicNotification(notify, true)) { drop("GATT notifications unavailable"); return }
            val descriptor = notify.getDescriptor(cccdUuid)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!gattInstance.writeDescriptor(descriptor)) drop("GATT subscription could not queue")
            } else {
                drop("GATT notification descriptor missing")
            }
        } catch (e: SecurityException) {
            drop("security exception: ${e.message}")
        } catch (e: Exception) {
            drop("gatt fallback failed: ${e.message}")
        }
    }

    private fun readL2capLoop() {
        val myAttempt = attemptGeneration
        val stream = input ?: return
        val ownedSocket = socket
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val count = stream.read(buffer)
                if (count <= 0) break
                EnergyCounters.add("ble.receive.bytes", count.toLong())
                synchronized(writeLock) {
                    if (myAttempt != attemptGeneration || socket !== ownedSocket || !isL2capActive) return
                    if (connected.get() && secure != null) noteActivity()
                    for (payload in frameReader.feed(buffer.copyOf(count))) handleFrame(payload)
                }
            }
            if (myAttempt == attemptGeneration && socket === ownedSocket) drop("l2cap remote closed")
        } catch (e: IOException) {
            if (myAttempt == attemptGeneration && socket === ownedSocket) drop("l2cap read failed")
        }
    }

    fun shutdown() { stop(); executor.shutdownNow() }

    fun stop() {
        synchronized(writeLock) {
            attemptGeneration++
            attemptTimeout?.cancel(false)
            attemptTimeout = null
            runCatching { pendingSocket?.close() }
            pendingSocket = null
            stopScan()
            connecting.set(false)
            connected.set(false)
            isL2capActive = false
            stopHeartbeat()
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
            gatt = null
            writeChar = null
            notifyChar = null
            synchronized(writeLock) {
                gattWrites.clear(); gattWriteInFlight = false
                incomingGattBytes = 0; gattBulkSending = false; requestedPriority = -1
                frameReader.reset(); secure = null; notificationGate.reset()
            }
        }
    }

    private fun updateConnectionPriority(): Boolean = synchronized(writeLock) {
        val target = gatt ?: return true
        val priority = when {
            !isL2capActive && (gattBulkSending || incomingGattBytes >= 16_384) -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
            interactive -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            else -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
        }
        if (priority == requestedPriority) return true
        try {
            target.requestConnectionPriority(priority)
            requestedPriority = priority
        } catch (_: SecurityException) { drop("Bluetooth permission revoked"); return false }
        catch (_: Exception) { /* Connection parameters are an optional controller hint. */ }
        true
    }

    fun setInteractive(value: Boolean) {
        interactive = value
        executor.execute {
            if (value) resetRetry()
            sendPowerState(if (value) "awake" else "sleep")
            if (!updateConnectionPriority()) return@execute
            if (connected.get()) startHeartbeat("")
        }
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

    private fun sendFrame(fields: List<Pair<String, Any>>): Boolean = synchronized(writeLock) {
        val channel = secure ?: return false
        val packet = channel.seal(BtCbor.encode(fields), 3)
        sendWireFrame(listOf("type" to "secure", "data" to java.util.Base64.getEncoder().encodeToString(packet)))
    }

    private fun sendWireFrame(fields: List<Pair<String, Any>>): Boolean {
        val framed = BtFrameIo.frame(BtCbor.encode(fields))
        return if (isL2capActive) {
            val out = output ?: return false
            try { synchronized(writeLock) { out.write(framed); out.flush() }; true }
            catch (_: IOException) { drop("write failed"); false }
        } else sendGattChunked(framed)
    }

    private fun sendGattChunked(data: ByteArray): Boolean = synchronized(writeLock) {
        if (gatt == null || writeChar == null) return false
        if (gattWrites.sumOf { it.size } + data.size > 3_000_000) { drop("GATT outgoing queue full"); return false }
        if (data.size >= 16_384) gattBulkSending = true
        if (!updateConnectionPriority()) return false
        val chunkSize = (mtu - 3).coerceIn(20, 512)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            gattWrites.addLast(data.copyOfRange(offset, end))
            offset = end
        }
        executor.execute { synchronized(writeLock) { flushGattWrites() } }
        true
    }

    @Suppress("DEPRECATION")
    private fun flushGattWrites() {
        if (gattWriteInFlight || gattWrites.isEmpty()) return
        val targetGatt = gatt ?: return
        val write = writeChar ?: return
        write.value = gattWrites.first
        write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            gattWriteInFlight = true
            val queued = if (Build.VERSION.SDK_INT >= 33) {
                targetGatt.writeCharacteristic(write, gattWrites.first, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else targetGatt.writeCharacteristic(write)
            if (!queued) { gattWriteInFlight = false; drop("GATT write could not queue") }
        } catch (_: SecurityException) { gattWriteInFlight = false; drop("Bluetooth permission revoked") }
        catch (_: Exception) { gattWriteInFlight = false; drop("GATT write failed") }
    }

    companion object {
        /** Extract the Mac's advertised endpoints from a decoded BT frame (list of "host:port" strings). */
        fun endpointsFromFields(fields: List<Pair<String, Any>>): List<String>? {
            val raw = fields.firstOrNull { it.first == "endpoints" }?.second
            val list = (raw as? List<*>)?.filterIsInstance<String>()
            return list?.takeIf { it.isNotEmpty() }
        }
    }

    private fun handleFrame(payload: ByteArray) {
        val fields = try {
            val wire = BtCbor.decode(payload)
            val type = wire.firstOrNull { it.first == "type" }?.second as? String
            if (type == "secure_hello" && secure == null) {
                val version = wire.firstOrNull { it.first == "version" }?.second as? Long
                require(version == SyncProtocol.VERSION.toLong())
                val serverNonce = java.util.Base64.getDecoder().decode(wire.first { it.first == "nonce" }.second as String)
                val psk = store.groupKey ?: error("Not paired")
                val clientNonce = SecureChannel.randomNonce()
                secure = SecureChannel(psk, serverNonce, clientNonce, "ble")
                sendWireFrame(listOf("type" to "secure_reply", "nonce" to java.util.Base64.getEncoder().encodeToString(clientNonce),
                    "proof" to java.util.Base64.getEncoder().encodeToString(SecureChannel.proof(psk, serverNonce, clientNonce, "ble"))))
                sendFrame(listOf("type" to "auth", "deviceId" to store.deviceId, "deviceName" to deviceNameProvider(), "version" to SyncProtocol.VERSION))
                return
            }
            require(type == "secure")
            val channel = secure ?: error("Missing encrypted session")
            val packet = java.util.Base64.getDecoder().decode(wire.first { it.first == "data" }.second as String)
            BtCbor.decode(channel.open(packet, 3))
        } catch (_: Exception) {
            drop("encrypted handshake/frame rejected")
            return
        }
        noteActivity()
        lastAuthenticatedFrameMs = lastHeardMs
        val type = fields.firstOrNull { it.first == "type" }?.second as? String ?: return
        fun text(key: String) = fields.firstOrNull { it.first == key }?.second as? String
        fun uint(key: String) = fields.firstOrNull { it.first == key }?.second as? Long
        when (type) {
            "auth_ok" -> {
                val id = text("deviceId") ?: ""
                val name = text("deviceName") ?: "Mac"
                connecting.set(false)
                connected.set(true)
                attemptTimeout?.cancel(false)
                retry.reset()
                sendPowerState(if (interactive) "awake" else "sleep")
                if (!updateConnectionPriority()) return
                callbacks.onAuthenticated(id, name)
                // Surface the Mac's live endpoints so the service can try the faster
                // WebSocket path first (mesh beats LAN beats Bluetooth).
                endpointsFromFields(fields)?.let { callbacks.onEndpoints(it) }
                startHeartbeat(id)
            }
            "clipboard" -> text("text")?.let(callbacks::onText)
            "openUrl" -> text("url")?.let(callbacks::onOpenUrl)
            "unpair" -> callbacks.onUnpair()
            "ping" -> sendFrame(listOf("type" to "pong", "t" to (uint("t") ?: 0L)))
            "pong" -> Unit
            "power" -> if (text("state") == "sleep") callbacks.onRemoteSleep()
        }
    }

    private fun noteActivity() {
        lastHeardMs = android.os.SystemClock.elapsedRealtime()
    }

    private fun startHeartbeat(remoteId: String) {
        stopHeartbeat()
        heartbeat = executor.scheduleWithFixedDelay({
            if (!connected.get()) return@scheduleWithFixedDelay
            val budget = PowerPolicy.budgetMs(interactive)
            if (android.os.SystemClock.elapsedRealtime() - lastHeardMs > budget) {
                drop("heartbeat timeout")
                return@scheduleWithFixedDelay
            }
            // Complete authenticated frames suppress duplicate probes. Fragment progress keeps
            // our receive deadline alive, but must not starve the Mac while a large frame drains.
            if (PowerPolicy.shouldProbeHeartbeat(android.os.SystemClock.elapsedRealtime(), lastAuthenticatedFrameMs, interactive))
            {
                EnergyCounters.add("heartbeat.probe.sent")
                sendFrame(listOf("type" to "ping", "t" to System.currentTimeMillis()))
            }
        }, PowerPolicy.heartbeatMs(interactive), PowerPolicy.heartbeatMs(interactive), TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel(false)
        heartbeat = null
    }

    private fun drop(reason: String) {
        val wasActive = connected.get() || connecting.get() || scanning.get()
        stop()
        retry.failed(android.os.SystemClock.elapsedRealtime(), java.util.concurrent.ThreadLocalRandom.current().nextLong(3_001))
        if (wasActive) callbacks.onLinkDown(reason)
    }
}
