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
    ): Decision = when {
        !paired || !fallbackEnabled || wsConnected || btConnected -> Decision.IDLE
        !permissionGranted -> Decision.NEEDS_PERMISSION
        !btAdapterOn -> Decision.PROMPT_ENABLE_BT
        !networkAvailable || backoffSeconds >= FALLBACK_THRESHOLD_SECONDS -> Decision.LISTEN_BT
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
        fun onLinkDown(reason: String)
        fun onText(text: String)
        fun onOpenUrl(url: String)
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
    @Volatile private var interactive = true
    private var heartbeat: ScheduledFuture<*>? = null
    private val frameReader = BtFrameIo.Reader()
    private val writeLock = Any()

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

    fun adapterEnabled(): Boolean = adapter()?.isEnabled == true

    fun isConnected(): Boolean = connected.get()

    fun isListening(): Boolean = scanning.get() || connecting.get()

    /** Alias for startConnecting, matching the existing service evaluator contract. */
    fun startListening(): Boolean = startConnecting()

    fun startConnecting(): Boolean {
        if (!hasPermission() || !adapterEnabled() || store.groupKey == null) return false
        if (connected.get() || connecting.get() || scanning.get()) return true

        val scanner = try {
            adapter()?.bluetoothLeScanner
        } catch (_: SecurityException) {
            return false
        } ?: return false

        scanning.set(true)
        DiagnosticLog.info("Starting BLE scan for service $serviceUuid")
        android.util.Log.i("BinderClipBLE", "Starting BLE scan for service $serviceUuid")
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        executor.execute {
            try {
                scanner.startScan(listOf(filter), settings, scanCallback)
            } catch (e: SecurityException) {
                DiagnosticLog.error("BLE scan security exception: ${e.message}")
                scanning.set(false)
            } catch (e: Exception) {
                DiagnosticLog.error("BLE scan exception: ${e.message}")
                scanning.set(false)
            }
        }
        return true
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            DiagnosticLog.info("Found BinderClip BLE peripheral: ${device.address}")
            android.util.Log.i("BinderClipBLE", "Found BinderClip BLE peripheral: ${device.address} (${result.scanRecord?.deviceName})")
            stopScan()
            connectDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            DiagnosticLog.warning("BLE scan failed with error code $errorCode")
            android.util.Log.w("BinderClipBLE", "BLE scan failed: $errorCode")
            scanning.set(false)
        }
    }

    private fun stopScan() {
        if (scanning.getAndSet(false)) {
            try {
                adapter()?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
    }

    private fun connectDevice(device: BluetoothDevice) {
        if (connected.get() || connecting.get()) return
        connecting.set(true)
        DiagnosticLog.info("Connecting to BLE GATT ${device.address}...")
        android.util.Log.i("BinderClipBLE", "Connecting to BLE GATT ${device.address}")
        executor.execute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    gatt = device.connectGatt(appContext, false, gattCallback)
                }
            } catch (e: SecurityException) {
                DiagnosticLog.error("GATT connect security error: ${e.message}")
                connecting.set(false)
            } catch (e: Exception) {
                DiagnosticLog.error("GATT connect error: ${e.message}")
                connecting.set(false)
            }
        }
    }

    private fun refreshDeviceCache(gattInstance: BluetoothGatt): Boolean = runCatching {
        val method = gattInstance.javaClass.getMethod("refresh")
        method.invoke(gattInstance) as? Boolean ?: false
    }.getOrDefault(false)

    private var discoveryRetries = 0

    @Suppress("DEPRECATION")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gattInstance: BluetoothGatt, status: Int, newState: Int) {
            DiagnosticLog.info("GATT connection state: status=$status, newState=$newState")
            android.util.Log.i("BinderClipBLE", "GATT state change: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                discoveryRetries = 0
                val refreshed = refreshDeviceCache(gattInstance)
                android.util.Log.i("BinderClipBLE", "Refreshed GATT device cache: $refreshed")
                try {
                    gattInstance.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gattInstance.requestMtu(512)
                } catch (_: SecurityException) {
                }
                executor.schedule({
                    try {
                        gattInstance.discoverServices()
                    } catch (_: SecurityException) {
                    }
                }, 400, TimeUnit.MILLISECONDS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                drop("ble disconnected (status=$status)")
            }
        }

        override fun onMtuChanged(gattInstance: BluetoothGatt, mtu: Int, status: Int) {
            DiagnosticLog.info("GATT MTU negotiated: $mtu")
            executor.schedule({
                try {
                    gattInstance.discoverServices()
                } catch (_: SecurityException) {
                }
            }, 300, TimeUnit.MILLISECONDS)
        }

        override fun onServicesDiscovered(gattInstance: BluetoothGatt, status: Int) {
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
                            refreshDeviceCache(gattInstance)
                            gattInstance.discoverServices()
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
            if (descriptor.characteristic.uuid == notifyCharUuid && status == BluetoothGatt.GATT_SUCCESS) {
                DiagnosticLog.info("GATT notify enabled; sending auth")
                sendAuthUnlocked()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gattInstance: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == notifyCharUuid && !isL2capActive) {
                val chunk = characteristic.value ?: return
                noteActivity()
                for (payload in frameReader.feed(chunk)) {
                    handleFrame(payload)
                }
            }
        }
    }

    private fun connectL2cap(device: BluetoothDevice, psm: Int, gattInstance: BluetoothGatt) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setupGattFallback(gattInstance)
            return
        }
        try {
            DiagnosticLog.info("Opening L2CAP channel (PSM $psm)...")
            android.util.Log.i("BinderClipBLE", "Opening L2CAP channel PSM $psm")
            val l2capSocket = device.createInsecureL2capChannel(psm)
            l2capSocket.connect()
            synchronized(this) {
                socket = l2capSocket
                input = l2capSocket.inputStream
                output = l2capSocket.outputStream
                isL2capActive = true
            }
            DiagnosticLog.info("L2CAP channel connected; sending auth frame")
            android.util.Log.i("BinderClipBLE", "L2CAP connected! Sending auth")
            sendAuthUnlocked()
            Thread({
                readL2capLoop()
            }, "binderclip-ble-l2cap").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            DiagnosticLog.warning("L2CAP connection failed (${e.message}), falling back to GATT stream")
            android.util.Log.w("BinderClipBLE", "L2CAP failed, falling back to GATT", e)
            setupGattFallback(gattInstance)
        }
    }

    private fun setupGattFallback(gattInstance: BluetoothGatt) {
        DiagnosticLog.info("Configuring GATT fallback stream...")
        val notify = notifyChar
        if (notify == null) {
            drop("notify char missing")
            return
        }
        try {
            gattInstance.setCharacteristicNotification(notify, true)
            val descriptor = notify.getDescriptor(cccdUuid)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gattInstance.writeDescriptor(descriptor)
            } else {
                sendAuthUnlocked()
            }
        } catch (e: SecurityException) {
            drop("security exception: ${e.message}")
        } catch (e: Exception) {
            drop("gatt fallback failed: ${e.message}")
        }
    }

    private fun readL2capLoop() {
        val stream = input ?: return
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val count = stream.read(buffer)
                if (count <= 0) break
                noteActivity()
                android.util.Log.i("BinderClipBLE", "L2CAP read $count bytes")
                for (payload in frameReader.feed(buffer.copyOf(count))) {
                    handleFrame(payload)
                }
            }
            drop("l2cap remote closed")
        } catch (e: IOException) {
            drop("l2cap read failed: ${e.message}")
        }
    }

    fun stop() {
        synchronized(this) {
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
        val payload = BtCbor.encode(fields)
        val framed = BtFrameIo.frame(payload)

        if (isL2capActive) {
            val out = output ?: return false
            return try {
                synchronized(writeLock) {
                    out.write(framed)
                    out.flush()
                }
                true
            } catch (_: IOException) {
                drop("write failed")
                false
            }
        } else {
            return sendGattChunked(framed)
        }
    }

    private fun sendAuthUnlocked() {
        val pskBytes = store.groupKey ?: return
        val fields = listOf(
            "type" to "auth",
            "psk" to SyncProtocol.urlSafeBase64(pskBytes),
            "deviceId" to store.deviceId,
            "deviceName" to deviceNameProvider(),
            "version" to SyncProtocol.VERSION,
        )
        val payload = BtCbor.encode(fields)
        val framed = BtFrameIo.frame(payload)

        if (isL2capActive) {
            val out = output ?: return
            try {
                synchronized(writeLock) {
                    out.write(framed)
                    out.flush()
                }
            } catch (_: IOException) {
                drop("auth write failed")
            }
        } else {
            sendGattChunked(framed)
        }
    }

    private fun sendGattChunked(data: ByteArray): Boolean {
        val targetGatt = gatt ?: return false
        val write = writeChar ?: return false
        val chunkSize = 512
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            try {
                write.value = chunk
                write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                targetGatt.writeCharacteristic(write)
            } catch (_: SecurityException) {
                drop("gatt write security exception")
                return false
            } catch (_: Exception) {
                drop("gatt write failed")
                return false
            }
            offset = end
        }
        return true
    }

    private fun handleFrame(payload: ByteArray) {
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
                connecting.set(false)
                connected.set(true)
                callbacks.onAuthenticated(id, name)
                startHeartbeat(id)
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
        val wasActive = connected.getAndSet(false) || connecting.getAndSet(false)
        stopHeartbeat()
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        isL2capActive = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        gatt = null
        writeChar = null
        notifyChar = null
        frameReader.reset()
        if (wasActive) {
            callbacks.onLinkDown(reason)
        }
    }
}
