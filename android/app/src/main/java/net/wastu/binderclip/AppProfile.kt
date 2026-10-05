package net.wastu.binderclip

/** App identity and protocol realm are chosen at build time, never by a pairing invitation. */
data class AppProfile(val isDebug: Boolean) {
    val applicationId get() = if (isDebug) "net.wastu.binderclip.debug" else "net.wastu.binderclip"
    val displayName get() = if (isDebug) "BinderClip Debug" else "BinderClip"
    val port get() = if (isDebug) 39422 else 39421
    val pairingScheme get() = if (isDebug) "binderclip-debug" else "binderclip"
    val bonjourType get() = if (isDebug) "_binderclip-dbg._tcp" else "_binderclip._tcp"
    val cryptoDomain get() = if (isDebug) "BinderClip/debug/v3" else "BinderClip/v3"
    val bootScriptName get() = if (isDebug) "binderclip-debug.sh" else "binderclip.sh"
    val bluetoothEnabledByDefault get() = !isDebug
    val canEnableBluetoothRadio get() = !isDebug
    fun bluetoothUuid(index: Int): String {
        require(index in 1..4)
        return if (isDebug) "a2b76ec4-81f0-4dbe-b5d1-770b3e28ad0$index"
        else "7d3e0f5a-9b1c-4e8d-a6f2-0c4b8d1e5a7${index + 2}"
    }
    companion object {
        val production = AppProfile(false)
        val debug = AppProfile(true)
        val current = AppProfile(BuildConfig.DEBUG)
    }
}
