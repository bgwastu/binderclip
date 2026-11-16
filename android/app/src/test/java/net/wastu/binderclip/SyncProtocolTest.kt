package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProtocolTest {
    @Test
    fun pairingUrlRoundTripUrlSafePsk() {
        val id = "mac-studio-123"
        val name = "Studio Mac"
        val raw = ByteArray(32) { it.toByte() }
        val psk = SyncProtocol.urlSafeBase64(raw)
        assertFalse(psk.contains("+"))
        assertFalse(psk.contains("/"))
        assertFalse(psk.contains("="))
        val endpoints = listOf("192.168.1.100:39421", "100.64.0.1:39421")

        val urlString = SyncProtocol.createPairingUrl(
            deviceId = id,
            deviceName = name,
            psk = psk,
            endpoints = endpoints
        )

        val parsed = SyncProtocol.parsePairingUrl(urlString)
        assertNotNull(parsed)
        assertEquals(SyncProtocol.VERSION, parsed!!.version)
        assertEquals(id, parsed.deviceId)
        assertEquals(name, parsed.deviceName)
        assertEquals(psk, parsed.psk)
        assertEquals(endpoints, parsed.endpoints)
        assertTrue(raw.contentEquals(SyncProtocol.decodeBase64(parsed.psk)))
    }

    @Test
    fun decodeStandardBase64PlusSlash() {
        val raw = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xef.toByte()) + ByteArray(29) { 1 }
        val standard = java.util.Base64.getEncoder().encodeToString(raw)
        val decoded = SyncProtocol.decodeBase64(standard)
        assertNotNull(decoded)
        assertTrue(raw.contentEquals(decoded))
    }

    @Test
    fun parsePreservesPlusInStandardBase64Psk() {
        val raw = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xef.toByte()) + ByteArray(29) { 1 }
        val psk = java.util.Base64.getEncoder().encodeToString(raw)
        assertTrue(psk.contains("+") && psk.contains("/"))
        val url = "binderclip://pair?v=${SyncProtocol.VERSION}&id=mac&name=Mac&psk=$psk&endpoints=192.168.1.1:39421"
        val parsed = SyncProtocol.parsePairingUrl(url, AppProfile.production)
        assertEquals(psk, parsed?.psk)
    }

    private val validPsk = SyncProtocol.urlSafeBase64(ByteArray(32) { 7 })

    private fun invite(v: String = SyncProtocol.VERSION.toString(), id: String = "mac-1", psk: String = validPsk,
                       name: String = "Mac", endpoints: String = "192.168.1.1:39421") =
        "binderclip://pair?v=$v&id=$id&name=$name&psk=$psk&endpoints=$endpoints"

    @Test
    fun parseRejectsMalformedInvites() {
        val p = AppProfile.production
        assertNotNull(SyncProtocol.parsePairingUrl(invite(), p))
        assertNull(SyncProtocol.parsePairingUrl(invite(v = "2"), p))
        assertNull(SyncProtocol.parsePairingUrl(invite().replace("v=${SyncProtocol.VERSION}&", ""), p))
        assertNull(SyncProtocol.parsePairingUrl(invite(psk = "short"), p))
        assertNull(SyncProtocol.parsePairingUrl(invite(psk = SyncProtocol.urlSafeBase64(ByteArray(16))), p))
        assertNull(SyncProtocol.parsePairingUrl(invite(id = "a%20b"), p))
        assertNull(SyncProtocol.parsePairingUrl(invite(id = "x".repeat(129)), p))
    }

    @Test
    fun parseDropsLoopbackAndBadEndpointsAndSanitizesName() {
        val parsed = SyncProtocol.parsePairingUrl(
            invite(name = "%0AEvil%07Mac", endpoints = "127.0.0.1:39421,localhost:1,[::1]:39421,100.64.0.1:39421,10.0.0.2:0,10.0.0.3:70000,100.64.0.1:39421"),
            AppProfile.production
        )
        assertNotNull(parsed)
        assertEquals(listOf("100.64.0.1:39421"), parsed!!.endpoints)
        assertEquals("EvilMac", parsed.deviceName)
        assertEquals("Mac", SyncProtocol.sanitizeDeviceName("  \n "))
        assertEquals(64, SyncProtocol.sanitizeDeviceName("x".repeat(100)).length)
    }

    @Test
    fun sha256HexProducesCorrectHash() {
        val text = "hello binderclip"
        val hash = SyncProtocol.sha256Hex(text)
        assertEquals(64, hash.length)
        assertEquals(hash, SyncProtocol.sha256Hex(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun nsdTxtDecodesAndTrimsAttributes() {
        val attributes = mapOf(
            "id" to " mac-studio-123 ".toByteArray(Charsets.UTF_8),
            "v" to "2".toByteArray(Charsets.UTF_8),
            "empty" to ByteArray(0)
        )
        assertEquals("mac-studio-123", SyncProtocol.nsdTxt(attributes, "id"))
        assertEquals("2", SyncProtocol.nsdTxt(attributes, "v"))
        assertNull(SyncProtocol.nsdTxt(attributes, "empty"))
        assertNull(SyncProtocol.nsdTxt(attributes, "missing"))
    }

    @Test
    fun orderedConnectEndpointsPrefersMeshOverLanAndLastGood() {
        val candidates = listOf(
            "192.168.100.184:39421",
            "100.96.0.7:39421",
        )
        val ordered = SyncProtocol.orderedConnectEndpoints("192.168.100.184:39421", candidates)
        assertEquals("100.96.0.7:39421", ordered.first())
        // Mesh appears once, LAN last-good still present but after mesh.
        assertTrue(ordered.indexOf("100.96.0.7:39421") < ordered.indexOf("192.168.100.184:39421"))
    }

    @Test
    fun orderedConnectEndpointsWithoutMeshKeepsLanOrder() {
        val candidates = listOf("192.168.100.184:39421", "10.0.0.5:39421")
        val ordered = SyncProtocol.orderedConnectEndpoints(null, candidates)
        assertEquals(candidates, ordered)
    }

    @Test
    fun discoveredMacIdentityFiltering() {
        // Both ids known and equal -> accept.
        assertTrue(SyncProtocol.shouldAcceptDiscoveredMac("mac-1", "mac-1", AppProfile.production))
        // Both known but different -> reject foreign BinderClip Mac.
        assertFalse(SyncProtocol.shouldAcceptDiscoveredMac("mac-1", "mac-2", AppProfile.production))
        // Legacy Mac without TXT id -> keep legacy accept behavior.
        assertTrue(SyncProtocol.shouldAcceptDiscoveredMac("mac-1", null, AppProfile.production))
        assertTrue(SyncProtocol.shouldAcceptDiscoveredMac("mac-1", "", AppProfile.production))
        // No stored peer id -> cannot verify, keep legacy accept behavior.
        assertTrue(SyncProtocol.shouldAcceptDiscoveredMac(null, "mac-1", AppProfile.production))
    }
}
