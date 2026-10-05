package net.wastu.binderclip

import org.junit.Assert.*
import org.junit.Test

class SecureChannelTest {
    private val key = ByteArray(32) { it.toByte() }
    private val serverNonce = ByteArray(32) { (it + 32).toByte() }
    private val clientNonce = ByteArray(32) { (it + 64).toByte() }
    private fun client(transport: String = "ws", profile: AppProfile = AppProfile.current) = SecureChannel(key, serverNonce, clientNonce, transport, profile = profile)
    private fun server(transport: String = "ws", profile: AppProfile = AppProfile.current) = SecureChannel(key, serverNonce, clientNonce, transport, true, profile)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun matchesIndependentHkdfAesGcmVector() {
        assertEquals("b9abb92d08dd27dba1745c700a84a78b8efa3401ae67adc77861930c3e3c9c32",
            SecureChannel.proof(key, serverNonce, clientNonce, "ws", AppProfile.production).hex())
        val packet = client(profile = AppProfile.production).seal("clipboard test".toByteArray(), 1)
        assertEquals("0000000000000001c2e436814e014e917c288cdd7d717c5a4edacd534996a94da470fe5a988b", packet.hex())
        assertEquals("clipboard test", server(profile = AppProfile.production).open(packet, 1).toString(Charsets.UTF_8))
    }
    @Test fun tamperingDoesNotAdvanceReceiveCounter() {
        val packet = client().seal("copy".toByteArray(), 1)
        val receiver = server()
        val corrupt = packet.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { receiver.open(corrupt, 1) }
        assertEquals("copy", receiver.open(packet, 1).toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { receiver.open(packet, 1) }
    }
    @Test fun rejectsReflectionWrongTransportAndWrongKind() {
        val packet = client().seal(byteArrayOf(9), 1)
        assertThrows(Exception::class.java) { client().open(packet, 1) }
        assertThrows(Exception::class.java) { server("ble").open(packet, 1) }
        assertThrows(Exception::class.java) { server().open(packet, 2) }
    }
    @Test fun rejectsWrongKeyFreshSessionReplayAndOutOfOrderFrames() {
        val sender = client()
        val first = sender.seal(byteArrayOf(1), 1)
        val second = sender.seal(byteArrayOf(2), 1)
        assertThrows(Exception::class.java) { server().open(second, 1) }
        assertThrows(Exception::class.java) { SecureChannel(ByteArray(32), serverNonce, clientNonce, "ws", true).open(first, 1) }
        assertThrows(Exception::class.java) { SecureChannel(key, SecureChannel.randomNonce(), clientNonce, "ws", true).open(first, 1) }
    }
    @Test fun encryptsBothDirectionsAndBleCbor() {
        val phone = client("ble"); val mac = server("ble")
        val fields = listOf("type" to "clipboard", "text" to "hello")
        val packet = phone.seal(BtCbor.encode(fields), 3)
        assertEquals(fields, BtCbor.decode(mac.open(packet, 3)))
        assertEquals("pong", phone.open(mac.seal("pong".toByteArray(), 3), 3).toString(Charsets.UTF_8))
    }
    @Test fun rejectsInvalidNonceAndKeyLengths() {
        assertThrows(IllegalArgumentException::class.java) { SecureChannel(key, ByteArray(1), clientNonce, "ws") }
        assertThrows(IllegalArgumentException::class.java) { SecureChannel(ByteArray(16), serverNonce, clientNonce, "ws") }
    }
}
