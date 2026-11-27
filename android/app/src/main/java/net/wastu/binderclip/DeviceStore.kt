package net.wastu.binderclip

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.SecureRandom
import java.security.KeyStore
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class RememberedPeer(
    val name: String,
    val host: String,
    val port: Int,
    val deviceId: String = "",
    val platform: String = "macOS",
    val connected: Boolean = false,
)

data class QueuedShare(val id: String, val text: String)

object DeviceNames {
    fun android(context: Context): String {
        val custom = DeviceStore(context).customDeviceName
        if (!custom.isNullOrBlank()) return custom.trim()
        val deviceName = runCatching {
            Settings.Global.getString(context.contentResolver, "device_name")?.trim().orEmpty()
        }.getOrDefault("")
        if (deviceName.isNotBlank()) return deviceName.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty().trim()
        val brand = Build.BRAND.orEmpty().trim()
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val maker = sequenceOf(brand, manufacturer)
            .map { it.orEmpty().trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("android", ignoreCase = true) }
            ?.replaceFirstChar { it.uppercase() }
        return when {
            model.isBlank() -> maker ?: "Android"
            maker.isNullOrBlank() || model.startsWith(maker, ignoreCase = true) -> model
            else -> "$maker $model"
        }
    }
}

/** Durable state; the group key is encrypted by a non-exportable Android Keystore key. */
class DeviceStore(context: Context) {
    companion object {
        private val shareLock = Any()
        private const val SHARE_OUTBOX = "share_outbox"
        private const val SHARE_INBOX = "share_inbox"
        private const val LEGACY_INBOX = "pending_shared_text"
        private const val RECEIVED_SHARES = "received_shares"
        private const val MAX_QUEUED_SHARES = 8
        const val KEY_ALIAS = "net.wastu.binderclip.group-key"
        const val GCM_NONCE_BYTES = 12
        fun nonce(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
    }
    private val prefs = context.getSharedPreferences("binderclip", Context.MODE_PRIVATE)
    private val fileTransfers = FileTransferStore.forContext(context)

    internal fun clipboardCheckpoint(): JSONObject? = runCatching {
        prefs.getString("clipboard_checkpoint", null)?.let(::decrypt)?.let { JSONObject(String(it, Charsets.UTF_8)) }
    }.getOrNull()

    internal fun saveClipboardCheckpoint(value: JSONObject?) {
        prefs.edit().apply {
            if (value == null) remove("clipboard_checkpoint")
            else putString("clipboard_checkpoint", encrypt(value.toString().toByteArray(Charsets.UTF_8)))
        }.apply()
    }

    /** Explicit text/link shares survive service startup, reconnects, and process death. */
    fun enqueueShare(text: String, id: String = UUID.randomUUID().toString()): Boolean = synchronized(shareLock) {
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > SyncProtocol.MAXIMUM_TEXT_BYTES) return@synchronized false
        val current = queuedSharesLocked()
        if (current.any { it.id == id } || id in deliveredShares()) return@synchronized true
        if (current.size >= MAX_QUEUED_SHARES) return@synchronized false
        saveSharesLocked(current + QueuedShare(id, text))
    }

    fun nextQueuedShare(): QueuedShare? = synchronized(shareLock) { queuedSharesLocked().firstOrNull() }

    fun removeQueuedShare(id: String): Boolean = synchronized(shareLock) {
        val current = queuedSharesLocked()
        if (current.firstOrNull()?.id != id) return@synchronized false
        saveSharesLocked(current.drop(1), (deliveredShares() + id).takeLast(64))
    }

    fun deliveredShares(): List<String> = prefs.getString("delivered_shares", null)?.split(' ').orEmpty()

    fun clearQueuedShares() = synchronized(shareLock) {
        prefs.edit().remove(SHARE_OUTBOX).remove("delivered_shares").commit()
    }

    private fun queuedSharesLocked() = readShares(SHARE_OUTBOX)

    private fun readShares(key: String): List<QueuedShare> = runCatching {
        val encrypted = prefs.getString(key, null) ?: return emptyList()
        val data = decrypt(encrypted) ?: return emptyList()
        val array = JSONArray(String(data, Charsets.UTF_8))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(QueuedShare(item.getString("id"), item.getString("text")))
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeShares(shares: List<QueuedShare>): String {
        val array = JSONArray().also { output -> shares.forEach { share ->
            output.put(JSONObject().put("id", share.id).put("text", share.text))
        } }
        return encrypt(array.toString().toByteArray(Charsets.UTF_8))
    }

    private fun saveSharesLocked(shares: List<QueuedShare>, delivered: List<String>? = null): Boolean {
        return prefs.edit().apply {
            putString(SHARE_OUTBOX, encodeShares(shares))
            delivered?.let { putString("delivered_shares", it.joinToString(" ")) }
        }.commit()
    }

    var customDeviceName: String?
        get() = prefs.getString("custom_device_name", null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().apply {
            val trimmed = value?.trim()
            if (trimmed.isNullOrBlank()) remove("custom_device_name")
            else putString("custom_device_name", trimmed)
        }.apply()

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
    var groupKey: ByteArray?
        get() {
            val sealed = prefs.getString("group_key", null) ?: return null
            // Keystore decryption is slow and this is read on every service tick; reuse the
            // last result while the stored ciphertext is unchanged (shared across instances).
            GroupKeyCache.get(sealed)?.let { return it }
            return decrypt(sealed)?.also { GroupKeyCache.put(sealed, it) }
        }
        set(value) = prefs.edit().apply {
            if (value == null) remove("group_key")
            else putString("group_key", encrypt(value))
        }.apply()
    var peer: RememberedPeer?
        get() {
            val host = prefs.getString("peer_host", null) ?: return null
            return RememberedPeer(
                name = prefs.getString("peer_name", "Mac") ?: "Mac",
                host = host,
                port = prefs.getInt("peer_port", SyncProtocol.DEFAULT_PORT),
                deviceId = prefs.getString("peer_id", "") ?: "",
                platform = prefs.getString("peer_platform", "macOS") ?: "macOS",
                connected = false
            )
        }
        set(value) = prefs.edit().apply {
            if (value == null) {
                remove("peer_name"); remove("peer_host"); remove("peer_port"); remove("peer_id"); remove("peer_platform"); remove("peer_connected")
            } else {
                putString("peer_name", value.name)
                putString("peer_host", value.host)
                putInt("peer_port", value.port)
                putString("peer_id", value.deviceId)
                putString("peer_platform", value.platform)
                putBoolean("peer_connected", false)
            }
        }.apply()

    /** Alternate addresses for the primary peer (LAN, Tailscale, and other unicast) so reconnect can
     *  fall back to a reachable route when one interface drops. */
    var peerCandidates: List<String>
        get() = prefs.getString("peer_candidates", "[]")?.let { raw ->
            runCatching {
                val a = JSONArray(raw)
                buildList { for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
            }.getOrDefault(emptyList())
        } ?: emptyList()
        set(value) = prefs.edit().putString("peer_candidates", JSONArray(value).toString()).apply()

    var lastGoodEndpoint: String?
        get() = prefs.getString("last_good_endpoint", null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().apply {
            val trimmed = value?.trim()
            if (trimmed.isNullOrBlank()) remove("last_good_endpoint")
            else putString("last_good_endpoint", trimmed)
        }.apply()
    var members: List<RememberedPeer>
        get() = runCatching {
            val raw = prefs.getString("members", "[]") ?: "[]"
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    add(RememberedPeer(
                        name = item.optString("name", "Device"), host = item.optString("host"),
                        port = item.optInt("port", SyncProtocol.DEFAULT_PORT), deviceId = id,
                        platform = item.optString("platform", "Android"), connected = false,
                    ))
                }
            }
        }.getOrDefault(emptyList())
        set(value) = prefs.edit().putString("members", JSONArray().apply {
            value.distinctBy { it.deviceId }.take(8).forEach { member -> put(JSONObject().apply {
                put("id", member.deviceId); put("name", member.name); put("host", member.host)
                put("port", member.port); put("platform", member.platform); put("connected", false)
            }) }
        }.toString()).apply()

    fun upsertMembers(incoming: List<RememberedPeer>) {
        val merged = (members.associateBy { it.deviceId } + incoming.associateBy { it.deviceId }).values.take(8)
        members = merged
    }
    fun updateMemberConnectionState(deviceId: String, isConnected: Boolean) {
        members = members.map {
            if (it.deviceId == deviceId) it.copy(connected = isConnected) else it
        }
    }

    fun applyRename(deviceId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (deviceId == this.deviceId) customDeviceName = trimmed
        peer = peer?.let { if (it.deviceId == deviceId) it.copy(name = trimmed) else it }
        members = members.map { if (it.deviceId == deviceId) it.copy(name = trimmed) else it }
    }
    fun removeMember(deviceId: String) { members = members.filterNot { it.deviceId == deviceId } }


    /** Everything that belongs to a pairing, apart from the key and the peer identity itself. */
    fun clearPairedData() {
        saveClipboardCheckpoint(null)
        clearQueuedShares()
        fileTransfers.clearPairing()
        members = emptyList()
        pendingText = null
        synchronized(shareLock) {
            prefs.edit().remove(SHARE_INBOX).remove(RECEIVED_SHARES).remove(LEGACY_INBOX).commit()
        }
    }

    /** Unpair and clear saved keys and member roster. Preserves stable device ID. */
    fun unpair() {
        clearPairedData()
        groupKey = null
        peer = null
        peerCandidates = emptyList()
        lastGoodEndpoint = null
    }

    /** Received text that could not be applied yet. It is clipboard content, so it is encrypted at
     *  rest like the pairing key; a legacy plaintext value is migrated on first read. */
    var pendingText: String?
        get() {
            prefs.getString("pending_text", null)?.let { legacy ->
                pendingText = legacy
                return legacy
            }
            return prefs.getString("pending_text_enc", null)?.let(::decrypt)?.toString(Charsets.UTF_8)
        }
        set(value) = prefs.edit().apply {
            remove("pending_text")
            if (value == null) remove("pending_text_enc")
            else putString("pending_text_enc", encrypt(value.toByteArray(Charsets.UTF_8)))
        }.apply()
    fun hasPendingSharedText(): Boolean = prefs.contains(SHARE_INBOX) || prefs.contains(LEGACY_INBOX)

    fun nextReceivedShare(): QueuedShare? = synchronized(shareLock) { inbox().firstOrNull() }

    fun removeReceivedShare(id: String): Boolean = synchronized(shareLock) {
        val shares = inbox()
        if (shares.firstOrNull()?.id != id) return@synchronized false
        prefs.edit().remove(LEGACY_INBOX).apply {
            if (shares.size == 1) remove(SHARE_INBOX)
            else putString(SHARE_INBOX, encodeShares(shares.drop(1)))
        }.commit()
    }

    fun receiveShare(share: QueuedShare): Boolean = synchronized(shareLock) {
        val shares = inbox()
        val received = prefs.getString(RECEIVED_SHARES, null)?.split(' ').orEmpty()
        if (share.id in received || shares.any { it.id == share.id }) return@synchronized true
        if (shares.size >= MAX_QUEUED_SHARES) return@synchronized false
        val saved = listOf(SHARE_INBOX, LEGACY_INBOX, RECEIVED_SHARES).associateWith { prefs.getString(it, null) }
        val committed = prefs.edit().remove(LEGACY_INBOX)
            .putString(SHARE_INBOX, encodeShares(shares + share))
            .putString(RECEIVED_SHARES, (received + share.id).takeLast(64).joinToString(" "))
            .commit()
        // SharedPreferences updates memory even when it fails to write to disk.
        if (!committed) prefs.edit().apply { saved.forEach { (key, value) -> putString(key, value) } }.commit()
        committed
    }

    private fun inbox(): List<QueuedShare> {
        val legacy = prefs.getString(LEGACY_INBOX, null)?.let(::decrypt)?.toString(Charsets.UTF_8)
        val shares = readShares(SHARE_INBOX)
        return if (legacy == null) shares else listOf(QueuedShare("", legacy)) + shares
    }

    fun setRootClipboardAutomationEnabled(enabled: Boolean) { prefs.edit().putBoolean("root_clipboard_automation", enabled).apply() }
    /** Set after Shizuku capture first works, so a later missing Shizuku is reported as paused rather than unset. */
    fun isShizukuCaptureConfigured(): Boolean = prefs.getBoolean("shizuku_capture_configured", false)
    fun setShizukuCaptureConfigured(configured: Boolean) { prefs.edit().putBoolean("shizuku_capture_configured", configured).apply() }


    /** Bluetooth LE fallback for text/links; on by default, engages only off-Wi-Fi. */
    fun isBtFallbackEnabled(): Boolean = prefs.getBoolean("bluetooth_fallback", AppProfile.current.bluetoothEnabledByDefault)
    fun setBtFallbackEnabled(enabled: Boolean) { prefs.edit().putBoolean("bluetooth_fallback", enabled).apply() }

    var autoApplyClipboard: Boolean
        get() = prefs.getBoolean("auto_apply_clipboard", AppProfile.current.isDebug)
        set(value) { prefs.edit().putBoolean("auto_apply_clipboard", value).apply() }

    /** Suppress transient toasts shown for clipboard copy/receive/send by default. */
    fun isSyncToastHidden(): Boolean = prefs.getBoolean("hide_sync_toasts", true)
    fun setSyncToastHidden(hidden: Boolean) { prefs.edit().putBoolean("hide_sync_toasts", hidden).apply() }

    fun isAutostartEnabled(): Boolean = prefs.getBoolean("autostart_enabled", true)
    fun setAutostartEnabled(enabled: Boolean) { prefs.edit().putBoolean("autostart_enabled", enabled).apply() }


    private fun keyAlias(): String {
        val userId = android.os.Process.myUid() / 100000
        val base = if (AppProfile.current.isDebug) "${BuildConfig.APPLICATION_ID}.group-key" else KEY_ALIAS
        return if (userId == 0) base else "$base.u$userId"
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = keyAlias()
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }
    private fun encrypt(value: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return android.util.Base64.encodeToString(cipher.iv + cipher.doFinal(value), android.util.Base64.NO_WRAP)
    }
    private fun decrypt(value: String): ByteArray? = runCatching {
        val bytes = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
        require(bytes.size > GCM_NONCE_BYTES)
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, GCM_NONCE_BYTES)))
        }.doFinal(bytes.copyOfRange(GCM_NONCE_BYTES, bytes.size))
    }.getOrNull()
}

private object GroupKeyCache {
    private var sealed: String? = null
    private var plain: ByteArray? = null

    @Synchronized fun get(key: String): ByteArray? = if (key == sealed) plain?.copyOf() else null

    @Synchronized fun put(key: String, value: ByteArray) {
        sealed = key
        plain = value.copyOf()
    }
}
