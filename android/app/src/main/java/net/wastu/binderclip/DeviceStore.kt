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
    private val prefs = context.getSharedPreferences("binderclip", Context.MODE_PRIVATE)

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
        get() = prefs.getString("group_key", null)?.let(::decrypt)
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
                port = prefs.getInt("peer_port", 39_421),
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
                        port = item.optInt("port", 39_421), deviceId = id,
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

    /** Reset pairing key: fresh PSK key, empty roster. */
    fun resetPairingKey() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        groupKey = key
        peer = null
        peerCandidates = emptyList()
        lastGoodEndpoint = null
        members = emptyList()
    }

    /** Unpair and clear saved keys and member roster. Preserves stable device ID. */
    fun unpair() {
        groupKey = null
        peer = null
        peerCandidates = emptyList()
        lastGoodEndpoint = null
        members = emptyList()
    }
    var pendingText: String?
        get() = prefs.getString("pending_text", null)
        set(value) = prefs.edit().apply { if (value == null) remove("pending_text") else putString("pending_text", value) }.apply()
    fun isRootClipboardAutomationEnabled(): Boolean = prefs.getBoolean("root_clipboard_automation", false)
    fun setRootClipboardAutomationEnabled(enabled: Boolean) { prefs.edit().putBoolean("root_clipboard_automation", enabled).apply() }

    /** When true, suppress the transient toasts shown for automatic clipboard copy/receive/send. */
    fun isSyncToastHidden(): Boolean = prefs.getBoolean("hide_sync_toasts", false)
    fun setSyncToastHidden(hidden: Boolean) { prefs.edit().putBoolean("hide_sync_toasts", hidden).apply() }

    fun reset() { prefs.edit().clear().apply() }

    private fun keyAlias(): String {
        val userId = android.os.Process.myUid() / 100000
        return if (userId == 0) KEY_ALIAS else "$KEY_ALIAS.u$userId"
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
    companion object {
        const val KEY_ALIAS = "net.wastu.binderclip.group-key"
        const val GCM_NONCE_BYTES = 12
        fun nonce(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
    }
}
