package net.wastu.binderclip

/**
 * Minimal CBOR (RFC 8949) subset for the Bluetooth link: flat maps whose text-string keys map to
 * text strings, unsigned integers, or arrays of text strings. Encoding is deterministic (declared
 * field order, shortest-head rule). Decoding is strict — anything outside the subset fails closed,
 * matching the project's clipboard-classification stance. Golden vectors in BtCborTest pin this
 * codec to a reference CBOR implementation on both platforms.
 */
object BtCbor {
    class CodecException(message: String) : Exception(message)

    private const val MAJOR_UNSIGNED = 0
    private const val MAJOR_TEXT = 3
    private const val MAJOR_ARRAY = 4
    private const val MAJOR_MAP = 5

    /** Values: [String], [Int]/[Long] (non-negative), or [List]<[String]>. Max 23 fields. */
    fun encode(fields: List<Pair<String, Any>>): ByteArray {
        if (fields.size > 23) throw CodecException("map too large: ${fields.size}")
        val out = java.io.ByteArrayOutputStream(64 + fields.size * 16)
        writeHead(out, MAJOR_MAP, fields.size.toLong())
        for ((key, value) in fields) {
            writeText(out, key)
            when (value) {
                is String -> writeText(out, value)
                is Int -> {
                    if (value < 0) throw CodecException("negative integers unsupported")
                    writeUnsigned(out, value.toLong())
                }
                is Long -> writeUnsigned(out, value)
                is List<*> -> {
                    if (value.size > 23) throw CodecException("array too large: ${value.size}")
                    writeHead(out, MAJOR_ARRAY, value.size.toLong())
                    for (item in value) {
                        if (item !is String) throw CodecException("array element must be text")
                        writeText(out, item)
                    }
                }
                else -> throw CodecException("unsupported value type: ${value::class.java.name}")
            }
        }
        return out.toByteArray()
    }

    /** Decodes into ordered key/value pairs; values are [String], [Long], or [List]<[String]>. */
    fun decode(bytes: ByteArray): List<Pair<String, Any>> {
        val parser = Parser(bytes)
        val majorAndArg = parser.readHead()
        if (majorAndArg.major != MAJOR_MAP) throw CodecException("expected map, got major $majorAndArg.major")
        val fields = ArrayList<Pair<String, Any>>(majorAndArg.arg.toInt())
        repeat(majorAndArg.arg.toInt()) {
            val keyMajor = parser.readHead()
            if (keyMajor.major != MAJOR_TEXT) throw CodecException("map key must be text")
            val key = parser.readText(keyMajor.arg)
            val valueMajor = parser.readHead()
            val value: Any = when (valueMajor.major) {
                MAJOR_UNSIGNED -> valueMajor.arg
                MAJOR_TEXT -> parser.readText(valueMajor.arg)
                MAJOR_ARRAY -> {
                    if (valueMajor.arg > Int.MAX_VALUE.toLong()) throw CodecException("array too large")
                    val items = ArrayList<String>(valueMajor.arg.toInt())
                    repeat(valueMajor.arg.toInt()) {
                        val itemMajor = parser.readHead()
                        if (itemMajor.major != MAJOR_TEXT) throw CodecException("array element must be text")
                        items.add(parser.readText(itemMajor.arg))
                    }
                    items
                }
                else -> throw CodecException("unsupported value major ${valueMajor.major}")
            }
            fields.add(key to value)
        }
        return fields
    }

    private fun writeHead(out: java.io.ByteArrayOutputStream, major: Int, length: Long) {
        val prefix = major shl 5
        when {
            length <= 23 -> out.write(prefix or length.toInt())
            length <= 0xFF -> {
                out.write(prefix or 24)
                out.write(length.toInt())
            }
            length <= 0xFFFF -> {
                out.write(prefix or 25)
                out.write((length shr 8).toInt())
                out.write(length.toInt())
            }
            length <= 0xFFFF_FFFFL -> {
                out.write(prefix or 26)
                for (shift in 24 downTo 0 step 8) out.write((length shr shift).toInt())
            }
            else -> {
                out.write(prefix or 27)
                for (shift in 56 downTo 0 step 8) out.write((length shr shift).toInt())
            }
        }
    }

    private fun writeUnsigned(out: java.io.ByteArrayOutputStream, value: Long) {
        if (value < 0) throw CodecException("negative integers unsupported")
        writeHead(out, MAJOR_UNSIGNED, value)
    }

    private fun writeText(out: java.io.ByteArrayOutputStream, value: String) {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        writeHead(out, MAJOR_TEXT, utf8.size.toLong())
        out.write(utf8)
    }

    private class Head(val major: Int, val arg: Long)

    private class Parser(private val bytes: ByteArray) {
        private var position = 0

        fun readHead(): Head {
            val initial = readByte()
            val major = initial shr 5
            val info = initial and 0x1F
            val arg = when {
                info <= 23 -> info.toLong()
                info == 24 -> readByte().toLong()
                info == 25 -> readWide(2)
                info == 26 -> readWide(4)
                info == 27 -> readWide(8)
                else -> throw CodecException("reserved/indefinite additional info $info")
            }
            return Head(major, arg)
        }

        fun readText(length: Long): String {
            if (length > bytes.size - position) throw CodecException("truncated text")
            val start = position
            position += length.toInt()
            return String(bytes, start, length.toInt(), Charsets.UTF_8)
        }

        private fun readByte(): Int {
            if (position >= bytes.size) throw CodecException("truncated input")
            return bytes[position++].toInt() and 0xFF
        }

        private fun readWide(count: Int): Long {
            var value = 0L
            repeat(count) { value = (value shl 8) or readByte().toLong() }
            return value
        }
    }
}

/** Length-prefix stream framing ([u32be length][payload]) shared by both RFCOMM peers. */
object BtFrameIo {
    const val MAXIMUM_FRAME_BYTES = 1_500_000

    fun frame(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) throw IllegalArgumentException("empty payload")
        if (payload.size > MAXIMUM_FRAME_BYTES) throw IllegalArgumentException("payload too large")
        val out = java.io.ByteArrayOutputStream(payload.size + 4)
        for (shift in 24 downTo 0 step 8) out.write((payload.size shr shift) and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    /** Incremental decoder for chunked RFCOMM reads; emits complete payloads as they arrive. */
    class Reader {
        private var buffer = ByteArray(0)

        fun feed(chunk: ByteArray): List<ByteArray> {
            val merged = ByteArray(buffer.size + chunk.size)
            buffer.copyInto(merged)
            chunk.copyInto(merged, buffer.size)
            val frames = ArrayList<ByteArray>()
            var offset = 0
            while (true) {
                val remaining = merged.size - offset
                if (remaining < 4) break
                var length = 0
                for (index in 0 until 4) length = (length shl 8) or (merged[offset + index].toInt() and 0xFF)
                if (length <= 0 || length > MAXIMUM_FRAME_BYTES) {
                    reset()
                    throw IllegalArgumentException("invalid frame length $length")
                }
                if (remaining < 4 + length) break
                frames.add(merged.copyOfRange(offset + 4, offset + 4 + length))
                offset += 4 + length
            }
            buffer = if (offset == 0) merged else merged.copyOfRange(offset, merged.size)
            return frames
        }

        fun reset() {
            buffer = ByteArray(0)
        }
    }
}
