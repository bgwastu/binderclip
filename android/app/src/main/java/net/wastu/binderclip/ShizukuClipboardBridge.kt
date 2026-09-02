package net.wastu.binderclip

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

object ShizukuClipboardBridge {
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    @Volatile var onAvailabilityChanged: (() -> Unit)? = null
    @Volatile private var clipboardProxy: Any? = null
    @Volatile private var getPrimaryClipMethod: Method? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        clipboardProxy = null
        getPrimaryClipMethod = null
        onAvailabilityChanged?.invoke()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        clipboardProxy = null
        getPrimaryClipMethod = null
        onAvailabilityChanged?.invoke()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            onAvailabilityChanged?.invoke()
        }
    }

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }
    }

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) != null
    }.getOrDefault(false)

    fun isAvailable(): Boolean = runCatching {
        Shizuku.pingBinder()
    }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(activity: Activity, requestCode: Int = 1002) {
        if (!isAvailable()) return
        runCatching {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            }
        }
    }

    fun enablePrivileges(context: Context): Boolean {
        if (!hasPermission()) return false
        val pkg = context.packageName
        val cmds = listOf(
            "cmd appops set $pkg READ_CLIPBOARD allow",
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "dumpsys deviceidle whitelist +$pkg",
            "cmd deviceidle whitelist +$pkg",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
        )
        var allOk = true
        for (cmd in cmds) {
            if (!exec(cmd)) allOk = false
        }
        return allOk
    }

    fun read(context: Context): RootClipboardBridge.Clip? {
        // Direct binder wrapping on modern Android requires UID-token matching.
        // Privileges granted via Shizuku (AppOps/Doze whitelist) enable resilient background operation.
        return null
    }

    private fun fetchPrimaryClip(): ClipData? = runCatching {
        var proxy = clipboardProxy
        var method = getPrimaryClipMethod

        if (proxy == null || method == null) {
            val rawBinder = SystemServiceHelper.getSystemService("clipboard") ?: return null
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)
            val stubClass = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            proxy = asInterface.invoke(null, wrappedBinder) ?: return null
            clipboardProxy = proxy

            val allMethods = proxy.javaClass.methods.map { m ->
                "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"
            }
            android.util.Log.d("BinderClipShizuku", "IClipboard methods: $allMethods")

            val methods = proxy.javaClass.methods.filter { it.name == "getPrimaryClip" }
            method = methods.maxByOrNull { it.parameterTypes.size } ?: return null
            getPrimaryClipMethod = method
        }

        val paramTypes = method.parameterTypes
        val args = arrayOfNulls<Any>(paramTypes.size)
        for (i in paramTypes.indices) {
            args[i] = when (paramTypes[i]) {
                String::class.java -> if (i == 0) "android" else null
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
        val hasClipMethod = proxy.javaClass.methods.firstOrNull { it.name == "hasPrimaryClip" && it.parameterTypes.size == method.parameterTypes.size }
        val hasClip = hasClipMethod?.invoke(proxy, *args)
        android.util.Log.d("BinderClipShizuku", "hasPrimaryClip returned: $hasClip")

        val descMethod = proxy.javaClass.methods.firstOrNull { it.name == "getPrimaryClipDescription" && it.parameterTypes.size == method.parameterTypes.size }
        val desc = descMethod?.invoke(proxy, *args)
        android.util.Log.d("BinderClipShizuku", "getPrimaryClipDescription returned: $desc")

        val clipData = method.invoke(proxy, *args) as? ClipData
        android.util.Log.d("BinderClipShizuku", "getPrimaryClip returned: $clipData (itemCount=${clipData?.itemCount}, text=${clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text})")
        clipData
    }.onFailure {
        android.util.Log.e("BinderClipShizuku", "fetchPrimaryClip error", it)
    }.getOrNull()

    private fun readImagePayload(context: Context, uri: Uri, declaredMime: String?): ImagePayload? = runCatching {
        // First try reading via ContentResolver
        val mime = declaredMime?.lowercase()?.takeIf { it in ImagePayload.ALLOWED_MIME_TYPES }
            ?: context.contentResolver.getType(uri)?.lowercase()?.takeIf { it in ImagePayload.ALLOWED_MIME_TYPES }
            ?: "image/png"

        val directBytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(16_384)
                val out = java.io.ByteArrayOutputStream()
                var count: Int
                while (input.read(buffer).also { count = it } >= 0) {
                    if (out.size() + count > ImagePayload.MAXIMUM_BYTES) break
                    out.write(buffer, 0, count)
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()

        if (directBytes != null) {
            return ImagePayload(mimeType = mime, data = directBytes)
        }

        // Fallback: Read content stream using Shizuku shell process
        val escapedUri = uri.toString().replace("'", "'\\''")
        val process = execProcess("content read --uri '$escapedUri'") ?: return null
        val stream = process.inputStream
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16_384)
        var n: Int
        while (stream.read(buf).also { n = it } >= 0) {
            if (out.size() + n > ImagePayload.MAXIMUM_BYTES) break
            out.write(buf, 0, n)
        }
        process.waitFor(5, TimeUnit.SECONDS)
        val bytes = out.toByteArray()
        if (bytes.isNotEmpty() && process.exitValue() == 0) {
            ImagePayload(mimeType = mime, data = bytes)
        } else null
    }.getOrNull()

    private fun exec(command: String): Boolean = runCatching {
        val process = execProcess(command) ?: return false
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        finished && process.exitValue() == 0
    }.getOrDefault(false)

    private fun execProcess(command: String): Process? = runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
        method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
    }.getOrNull()
}
