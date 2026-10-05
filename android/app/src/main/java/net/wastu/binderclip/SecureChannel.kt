package net.wastu.binderclip

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Matches CryptoKit HKDF-SHA256 and AES-256-GCM. No application-level cleartext fallback. */
class SecureChannel(psk: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray, transport: String, server: Boolean = false) {
    private val context = "BinderClip/v3/$transport".toByteArray(Charsets.UTF_8)
    private val sendKey: ByteArray
    private val receiveKey: ByteArray
    private var sent = 0L
    private var received = 0L
    init {
        require(psk.size == 32 && serverNonce.size == 32 && clientNonce.size == 32)
        require(transport == "ws" || transport == "ble")
        val extracted = hmac(serverNonce + clientNonce, psk)
        fun derive(direction: String) = hmac(extracted, context + "/$direction".toByteArray() + byteArrayOf(1))
        sendKey = derive(if (server) "s2c" else "c2s")
        receiveKey = derive(if (server) "c2s" else "s2c")
    }
    @Synchronized fun seal(plaintext: ByteArray, kind: Byte): ByteArray {
        require(plaintext.size <= MAXIMUM_PLAINTEXT && sent < Long.MAX_VALUE)
        val sequence = ByteBuffer.allocate(8).putLong(++sent).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, ByteArray(4) + sequence))
        cipher.updateAAD(context + byteArrayOf(kind) + sequence)
        return sequence + cipher.doFinal(plaintext)
    }
    @Synchronized fun open(packet: ByteArray, kind: Byte): ByteArray {
        require(packet.size in 24..(MAXIMUM_PLAINTEXT + 24))
        val sequence = packet.copyOfRange(0, 8)
        val counter = ByteBuffer.wrap(sequence).long
        require(counter > 0 && counter == received + 1) { "Invalid encrypted sequence" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(128, ByteArray(4) + sequence))
        cipher.updateAAD(context + byteArrayOf(kind) + sequence)
        val plaintext = cipher.doFinal(packet.copyOfRange(8, packet.size))
        received = counter
        return plaintext
    }
    companion object {
        const val MAXIMUM_PLAINTEXT = 32 * 1024 * 1024
        fun randomNonce() = ByteArray(32).also { SecureRandom().nextBytes(it) }
        private fun hmac(key: ByteArray, bytes: ByteArray) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(bytes)
        }
        fun proof(psk: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray, transport: String): ByteArray =
            hmac(psk, "BinderClip/v3/$transport/proof".toByteArray() + serverNonce + clientNonce)
        fun validProof(psk: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray, transport: String, proof: ByteArray) =
            MessageDigest.isEqual(proof(psk, serverNonce, clientNonce, transport), proof)
    }
}
