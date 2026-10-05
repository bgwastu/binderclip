package net.wastu.binderclip

import org.junit.Assert.*
import org.junit.Test

class AppIsolationTest {
    @Test fun pairingInvitationsCannotCrossEnvironments() {
        for (profile in listOf(AppProfile.production, AppProfile.debug)) {
            val url = SyncProtocol.createPairingUrl("mac", "Mac", "test", listOf("10.0.0.1:${profile.port}"), profile)
            assertNotNull(SyncProtocol.parsePairingUrl(url, profile))
            assertNull(SyncProtocol.parsePairingUrl(url, AppProfile(!profile.isDebug)))
            assertNull(SyncProtocol.parsePairingUrl(url.replace("://pair?", "://wrong?"), profile))
        }
    }
    @Test fun copiedPairingKeyCannotCrossAuthenticationOrEncryptionDomains() {
        val key = ByteArray(32) { it.toByte() }; val a = ByteArray(32) { (it + 32).toByte() }; val b = ByteArray(32) { (it + 64).toByte() }
        for (transport in listOf("ws", "ble")) {
            val prodProof = SecureChannel.proof(key, a, b, transport, AppProfile.production)
            val debugProof = SecureChannel.proof(key, a, b, transport, AppProfile.debug)
            assertFalse(prodProof.contentEquals(debugProof))
            for (senderProfile in listOf(AppProfile.production, AppProfile.debug)) {
                val packet = SecureChannel(key, a, b, transport, profile = senderProfile).seal("isolated".toByteArray(), 1)
                val other = SecureChannel(key, a, b, transport, true, AppProfile(!senderProfile.isDebug))
                assertThrows(Exception::class.java) { other.open(packet, 1) }
                assertEquals("isolated", SecureChannel(key, a, b, transport, true, senderProfile).open(packet, 1).decodeToString())
            }
        }
    }
    @Test fun debugCannotAutostartAndCannotRemoveProductionBootScript() {
        assertFalse(AppProfile.debug.bluetoothEnabledByDefault)
        assertFalse(AppProfile.debug.canEnableBluetoothRadio)
        assertTrue(AppProfile.production.bluetoothEnabledByDefault)
        assertTrue(AppProfile.production.canEnableBluetoothRadio)
        assertFalse(ServiceAutostart.shouldStart("android.intent.action.BOOT_COMPLETED", true, AppProfile.debug))
        assertEquals("binderclip-debug.sh", ServiceAutostart.bootScriptName(AppProfile.debug.applicationId))
        assertEquals("binderclip.sh", ServiceAutostart.bootScriptName(AppProfile.production.applicationId))
        assertThrows(IllegalArgumentException::class.java) { ServiceAutostart.bootScriptName("net.other.application") }
        assertTrue(ServiceAutostart.bootScript(AppProfile.debug.applicationId).contains("$" + "PKG/net.wastu.binderclip.BinderClipService"))
        assertFalse(SyncProtocol.shouldAcceptDiscoveredMac("mac", null, AppProfile.debug))
    }
}
