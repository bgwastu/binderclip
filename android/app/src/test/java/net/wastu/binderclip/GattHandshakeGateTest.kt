package net.wastu.binderclip

import org.junit.Assert.*
import org.junit.Test

class GattHandshakeGateTest {
    @Test fun challengeWaitsForDescriptorAcknowledgement() {
        val gate = GattHandshakeGate()
        val challenge = byteArrayOf(1, 2, 3)
        assertNull(gate.receive(challenge))
        assertArrayEquals(challenge, gate.subscribed())
        assertArrayEquals(byteArrayOf(4), gate.receive(byteArrayOf(4)))
    }
    @Test fun resetDiscardsOldChallengeAndUnexpectedTrafficIsBounded() {
        val gate = GattHandshakeGate()
        gate.receive(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { gate.receive(byteArrayOf(2)) }
        gate.reset()
        assertThrows(IllegalArgumentException::class.java) { gate.receive(ByteArray(16_385)) }
        assertNull(gate.subscribed())
    }
}
