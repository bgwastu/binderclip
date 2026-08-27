package net.wastu.binderclip

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BtCborTest {
    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun fields(vararg pairs: Pair<String, Any>): List<Pair<String, Any>> = pairs.toList()

    @Test
    fun encodesReferenceGoldenVectors() {
        val cases: List<Pair<List<Pair<String, Any>>, String>> = listOf(
            fields(
                "type" to "clipboard",
                "text" to ""
            ) to "a2647479706569636c6970626f617264647465787460",
            fields(
                "type" to "unpair"
            ) to "a1647479706566756e70616972",
            fields(
                "type" to "pong",
                "t" to 42L
            ) to "a2647479706564706f6e676174182a",
            fields(
                "type" to "ping",
                "t" to 1787719445677L
            ) to "a264747970656470696e6761741b000001a03c61ecad",
            fields(
                "type" to "clipboard",
                "text" to "héllo 世界 📋"
            ) to "a2647479706569636c6970626f61726464746578747268c3a96c6c6f20e4b896e7958c20f09f938b",
        )
        for ((fieldList, expectedHex) in cases) {
            assertEquals(expectedHex, hex(BtCbor.encode(fieldList)))
        }
    }

    @Test
    fun encodesAuthFrameWithAllHeadSizes() {
        val encoded = BtCbor.encode(listOf(
            "type" to "auth",
            "psk" to "0N1jn4tZ6L9UqiEwPWDUaJsZVwyVpESBlTA8V5mWcik",
            "deviceId" to "006f94c0-2324-493f-a9a2-17410daa6108",
            "deviceName" to "Atlas",
            "version" to 2,
        ))
        // psk is 44 chars -> 0x78 u8 head; deviceId 36 chars -> 0x78 too.
        assertEquals(
            "a5647479706564617574686370736b782b304e316a6e34745a364c39557169457750574455614a735a56777956704553426c54413856356d5763696b686465766963654964782430303666393463302d323332342d343933662d613961322d3137343130646161363130386a6465766963654e616d656541746c61736776657273696f6e02",
            hex(encoded)
        )
    }

    @Test
    fun encodesAuthOkWithStringArray() {
        val encoded = BtCbor.encode(listOf(
            "type" to "auth_ok",
            "deviceId" to "A6642397-8317-4657-A41C-221C84ABBD3A",
            "deviceName" to "Air",
            "version" to 2,
            "endpoints" to listOf("192.168.50.168:39421", "100.96.0.7:39421"),
        ))
        assertEquals(
            "a5647479706567617574685f6f6b686465766963654964782441363634323339372d383331372d343635372d413431432d3232314338344142424433416a6465766963654e616d65634169726776657273696f6e0269656e64706f696e747382743139322e3136382e35302e3136383a3339343231703130302e39362e302e373a3339343231",
            hex(encoded)
        )
    }

    @Test
    fun decodesGoldenVectors() {
        val decoded = BtCbor.decode(hex(
            "a5647479706567617574685f6f6b686465766963654964782441363634323339372d383331372d343635372d413431432d3232314338344142424433416a6465766963654e616d65634169726776657273696f6e0269656e64706f696e747382743139322e3136382e35302e3136383a3339343231703130302e39362e302e373a3339343231"
        ))
        assertEquals("auth_ok", decoded.first().second)
        assertEquals(listOf("192.168.50.168:39421", "100.96.0.7:39421"), decoded.last().second)

        val ping = BtCbor.decode(hex("a264747970656470696e6761741b000001a03c61ecad"))
        assertEquals(1787719445677L, ping.last().second)
    }

    @Test
    fun roundTripsEveryHeadSizeForText() {
        for (length in intArrayOf(0, 1, 23, 24, 255, 256, 65535, 65536, 70_000)) {
            val text = "x".repeat(length)
            val fields = listOf("type" to "clipboard", "text" to text)
            val decoded = BtCbor.decode(BtCbor.encode(fields))
            assertEquals(2, decoded.size)
            assertEquals(text, decoded[1].second)
        }
    }

    @Test
    fun rejectsUnsupportedAndTruncatedInput() {
        assertThrows(BtCbor.CodecException::class.java) { BtCbor.decode(hex("c07474797065")) }      // tag major
        assertThrows(BtCbor.CodecException::class.java) { BtCbor.decode(hex("a201026142")) }       // int key
        assertThrows(BtCbor.CodecException::class.java) { BtCbor.decode(hex("a26474797065")) }     // truncated
        assertThrows(BtCbor.CodecException::class.java) { BtCbor.decode(byteArrayOf()) }
        assertThrows(BtCbor.CodecException::class.java) {
            BtCbor.encode(listOf("type" to -1))                                                    // negative
        }
        assertThrows(BtCbor.CodecException::class.java) {
            BtCbor.encode((1..24).map { "k$it" to "v" })                                           // >23 fields
        }
    }

    @Test
    fun framingPrefixesLengthAndReassemblesChunks() {
        val payload = BtCbor.encode(listOf("type" to "power", "state" to "sleep"))
        val framed = BtFrameIo.frame(payload)
        assertEquals(payload.size.toLong(), java.nio.ByteBuffer.wrap(framed, 0, 4).int.toLong())

        val reader = BtFrameIo.Reader()
        var emitted = emptyList<ByteArray>()
        var index = 0
        while (index < framed.size) {
            val end = minOf(index + 3, framed.size)
            emitted = reader.feed(framed.copyOfRange(index, end))
            index = end
        }
        assertEquals(1, emitted.size)
        assertArrayEquals(payload, emitted[0])

        val second = BtFrameIo.frame(byteArrayOf(0x01))
        val both = reader.feed(second + second)
        assertEquals(2, both.size)
        assertArrayEquals(byteArrayOf(0x01), both[1])
    }

    @Test
    fun framingRejectsOversizeFrames() {
        val reader = BtFrameIo.Reader()
        val header = byteArrayOf(0x00, 0x17, 0x00, 0x00) // 1_500_001
        assertThrows(IllegalArgumentException::class.java) { reader.feed(header) }
        assertThrows(IllegalArgumentException::class.java) { BtFrameIo.frame(ByteArray(BtFrameIo.MAXIMUM_FRAME_BYTES + 1)) }
    }
}
