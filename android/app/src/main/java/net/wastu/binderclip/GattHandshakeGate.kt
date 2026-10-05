package net.wastu.binderclip

/** A peripheral may notify before the CCCD write acknowledgement reaches Android. */
class GattHandshakeGate {
    private var ready = false
    private var pending: ByteArray? = null
    fun receive(packet: ByteArray): ByteArray? {
        if (ready) return packet
        require(pending == null && packet.size <= 16_384) { "Unexpected pre-subscription traffic" }
        pending = packet
        return null
    }
    fun subscribed(): ByteArray? {
        ready = true
        return pending.also { pending = null }
    }
    fun reset() { ready = false; pending = null }
}
