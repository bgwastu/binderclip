package net.wastu.binderclip

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

data class PairingInfo(
    val version: Int,
    val deviceId: String,
    val deviceName: String,
    val psk: String,
    val endpoints: List<String>,
)

data class ImageMetadata(
    val id: String,
    val mimeType: String,
    val hash: String,
    val originId: String,
    val size: Int,
)

object SyncProtocol {
    const val VERSION = 2
    const val DEFAULT_PORT = 39421
    const val MAXIMUM_TEXT_BYTES = 1_048_576
    const val MAXIMUM_IMAGE_BYTES = 32 * 1024 * 1024
    const val HEARTBEAT_INTERVAL_MS = 2_000L
    const val HEARTBEAT_BUDGET_MS = 5_000L
    const val HEARTBEAT_SLEEP_BUDGET_MS = 45_000L
    const val AUTH_DEADLINE_MS = 2_000L
    const val CONNECT_TIMEOUT_SECONDS = 8L
    const val BT_SERVICE_UUID = "7d3e0f5a-9b1c-4e8d-a6f2-0c4b8d1e5a73"
    const val BT_PSM_CHAR_UUID = "7d3e0f5a-9b1c-4e8d-a6f2-0c4b8d1e5a74"
    const val BT_WRITE_CHAR_UUID = "7d3e0f5a-9b1c-4e8d-a6f2-0c4b8d1e5a75"
    const val BT_NOTIFY_CHAR_UUID = "7d3e0f5a-9b1c-4e8d-a6f2-0c4b8d1e5a76"

    fun orderedConnectEndpoints(
        lastGood: String?,
        candidates: List<String>,
        remembered: String? = null,
    ): List<String> {
        val out = ArrayList<String>()
        fun add(raw: String?) {
            val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return
            if (value !in out) out.add(value)
        }
        add(lastGood)
        candidates.forEach(::add)
        add(remembered)
        return out
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun urlSafeBase64(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun decodeBase64(value: String): ByteArray? = runCatching {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = when (normalized.length % 4) {
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> normalized
        }
        Base64.getDecoder().decode(padded)
    }.getOrNull()

    fun parseEndpoint(raw: String): Pair<String, Int>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            if (close <= 1) return null
            val host = trimmed.substring(1, close)
            val rest = trimmed.substring(close + 1)
            if (!rest.startsWith(":")) return null
            val port = rest.substring(1).toIntOrNull() ?: return null
            return host to port
        }
        val colon = trimmed.lastIndexOf(':')
        if (colon <= 0) return trimmed to DEFAULT_PORT
        val host = trimmed.substring(0, colon)
        val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
        if (host.isBlank()) return null
        return host to port
    }

    fun mergeAdvertisedEndpoints(current: List<String>, incoming: List<String>, limit: Int = 8): List<String> {
        val incomingClean = incoming.map { it.trim() }.filter { it.isNotBlank() }
        val currentClean = current.map { it.trim() }.filter { it.isNotBlank() }
        return (incomingClean + currentClean).distinct().take(limit)
    }

    const val MDNS_TXT_ID = "id"
    const val MDNS_TXT_NAME = "name"
    const val MDNS_TXT_VERSION = "v"

    fun nsdTxt(attributes: Map<String, ByteArray>, key: String): String? =
        attributes[key]?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotBlank() }

    /** Only reject a discovered Mac when both identities are known and differ; missing ids keep
     *  legacy accept behavior so older Mac builds stay reachable. */
    fun shouldAcceptDiscoveredMac(pairedPeerId: String?, advertisedId: String?): Boolean {
        if (advertisedId.isNullOrBlank()) return true
        if (pairedPeerId.isNullOrBlank()) return true
        return advertisedId == pairedPeerId
    }

    fun endpointsFromJson(json: JSONObject): List<String> {
        val array = json.optJSONArray("endpoints") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    fun parsePairingUrl(uriString: String): PairingInfo? = runCatching {
        val trimmed = uriString.trim()
        if (!trimmed.startsWith("binderclip://", ignoreCase = true)) return null

        val queryPart = trimmed.substringAfter("?", "")
        if (queryPart.isEmpty()) return null

        val params = mutableMapOf<String, String>()
        for (pair in queryPart.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = pair.substring(0, idx).trim()
                val rawVal = pair.substring(idx + 1).trim()
                val decoded = if (key == "psk") {
                    runCatching {
                        URLDecoder.decode(rawVal.replace("+", "%2B"), "UTF-8")
                    }.getOrDefault(rawVal)
                } else {
                    runCatching {
                        URLDecoder.decode(rawVal, "UTF-8")
                    }.getOrDefault(rawVal)
                }
                params[key] = decoded
            }
        }

        val id = params["id"]?.takeIf { it.isNotBlank() } ?: return null
        val psk = params["psk"]?.takeIf { it.isNotBlank() } ?: return null
        val version = params["v"]?.toIntOrNull() ?: VERSION
        val name = params["name"] ?: "Mac"
        val rawEndpoints = params["endpoints"].orEmpty()
        val endpoints = rawEndpoints.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        PairingInfo(
            version = version,
            deviceId = id,
            deviceName = name,
            psk = psk,
            endpoints = endpoints
        )
    }.getOrNull()

    fun createPairingUrl(deviceId: String, deviceName: String, psk: String, endpoints: List<String>): String {
        val query = buildString {
            append("v=").append(URLEncoder.encode(VERSION.toString(), "UTF-8"))
            append("&id=").append(URLEncoder.encode(deviceId, "UTF-8"))
            append("&name=").append(URLEncoder.encode(deviceName, "UTF-8"))
            append("&psk=").append(URLEncoder.encode(psk, "UTF-8"))
            append("&endpoints=").append(URLEncoder.encode(endpoints.joinToString(","), "UTF-8"))
        }
        return "binderclip://pair?$query"
    }

    fun packImage(image: ImagePayload, originId: String): ByteArray {
        val hash = sha256Hex(image.data)
        val json = JSONObject().apply {
            put("type", "image")
            put("id", image.id)
            put("mimeType", image.mimeType)
            put("hash", hash)
            put("originId", originId)
            put("size", image.data.size)
        }
        val metaBytes = json.toString().toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + metaBytes.size + image.data.size)
        buffer.putInt(metaBytes.size)
        buffer.put(metaBytes)
        buffer.put(image.data)
        return buffer.array()
    }

    fun unpackImage(packet: ByteArray): Pair<ImageMetadata, ByteArray>? = runCatching {
        if (packet.size < 4) return null
        val buffer = ByteBuffer.wrap(packet)
        val headerLen = buffer.int
        if (headerLen <= 0 || packet.size < 4 + headerLen) return null

        val metaBytes = ByteArray(headerLen)
        buffer.get(metaBytes)
        val json = JSONObject(metaBytes.toString(Charsets.UTF_8))

        val meta = ImageMetadata(
            id = json.optString("id", UUID.randomUUID().toString()),
            mimeType = json.getString("mimeType"),
            hash = json.getString("hash"),
            originId = json.optString("originId", ""),
            size = json.getInt("size")
        )
        val imageData = ByteArray(packet.size - 4 - headerLen)
        buffer.get(imageData)
        if (imageData.size != meta.size) return null
        Pair(meta, imageData)
    }.getOrNull()
}
