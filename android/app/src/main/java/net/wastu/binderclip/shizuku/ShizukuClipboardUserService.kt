package net.wastu.binderclip.shizuku

import android.content.ClipData
import android.content.Context
import android.content.IOnPrimaryClipChangedListener
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import net.wastu.binderclip.ImagePayload
import java.lang.reflect.Method

/**
 * Runs inside the process Shizuku starts as the shell user (uid 2000), never in BinderClip's own process.
 * Calling IClipboard as `com.android.shell`, which holds READ_CLIPBOARD_IN_BACKGROUND, lets the platform
 * ClipboardService deliver change callbacks and reads without focus. It only reads; BinderClip writes
 * incoming clips with its own identity. Never log clipboard content.
 */
class ShizukuClipboardUserService() : IShizukuClipboardService.Stub() {
    @Volatile private var context: Context? = null
    @Volatile private var clipboard: Any? = null
    @Volatile private var watcher: IClipboardWatcher? = null
    @Volatile private var userId = 0
    private var listener: IOnPrimaryClipChangedListener? = null

    /** Shizuku prefers this constructor; the context resolves clipboard image URIs as the shell user. */
    constructor(context: Context) : this() {
        this.context = context
    }

    @Synchronized
    override fun startWatching(watcher: IClipboardWatcher, userId: Int): Boolean {
        stopWatching()
        this.watcher = watcher
        this.userId = userId
        val next = object : IOnPrimaryClipChangedListener.Stub() {
            override fun dispatchPrimaryClipChanged() {
                runCatching { dispatch() }.onFailure { Log.w(TAG, "Clipboard dispatch failed: ${it.javaClass.simpleName}") }
            }
        }
        return runCatching {
            invoke("addPrimaryClipChangedListener", listener = next)
            listener = next
            true
        }.getOrElse {
            Log.w(TAG, "Clipboard listener refused: ${it.javaClass.simpleName}")
            this.watcher = null
            false
        }
    }

    @Synchronized
    override fun stopWatching() {
        listener?.let { runCatching { invoke("removePrimaryClipChangedListener", listener = it) } }
        listener = null
        watcher = null
    }

    override fun dispatchCurrent(): Boolean = runCatching { dispatch(); true }.getOrElse {
        Log.w(TAG, "Clipboard read refused: ${it.javaClass.simpleName}")
        false
    }

    override fun destroy() {
        stopWatching()
        System.exit(0)
    }

    private fun dispatch() {
        val target = watcher ?: return
        val clip = invoke("getPrimaryClip") as? ClipData
        val token = clip?.description?.timestamp ?: 0L
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        if (item == null) {
            target.onUnsupported(token)
            return
        }
        val imageMime = clip.description?.filterMimeTypes("image/*")?.firstOrNull { it in ImagePayload.ALLOWED_MIME_TYPES }
        val directText = item.text?.toString()?.takeIf { it.isNotBlank() }
        if (directText != null && (isWebUrl(directText) || imageMime == null)) {
            target.onText(directText, token)
            return
        }
        val uri = item.uri
        val scheme = uri?.scheme?.lowercase()
        when {
            uri != null && (scheme == "content" || scheme == "file" || imageMime != null) -> {
                // File URIs that are not supported images fail closed; they are never coerced to a filename.
                if (!streamImage(target, uri, imageMime, token)) {
                    if (directText != null) target.onText(directText, token) else target.onUnsupported(token)
                }
            }
            uri != null && (scheme == "http" || scheme == "https") -> target.onText(uri.toString(), token)
            directText != null -> target.onText(directText, token)
            uri == null -> {
                val coerced = context?.let { runCatching { item.coerceToText(it)?.toString() }.getOrNull() }
                if (!coerced.isNullOrBlank()) target.onText(coerced, token) else target.onUnsupported(token)
            }
            else -> target.onUnsupported(token)
        }
    }

    /** Streams bounded bytes over a pipe, avoiding the 1 MB binder transaction limit. */
    private fun streamImage(target: IClipboardWatcher, uri: Uri, declaredMime: String?, token: Long): Boolean {
        val resolver = context?.contentResolver ?: return false
        val mime = declaredMime ?: runCatching { resolver.getType(uri)?.lowercase() }.getOrNull()
            ?.takeIf { it in ImagePayload.ALLOWED_MIME_TYPES } ?: return false
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > ImagePayload.MAXIMUM_BYTES) return@use null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) } }
        }.start()
        try {
            target.onImage(read, mime, bytes.size.toLong(), token)
        } finally {
            runCatching { read.close() }
        }
        return true
    }

    private fun clipboard(): Any {
        clipboard?.let { return it }
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
        val proxy = Class.forName("android.content.IClipboard\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        clipboard = proxy
        return proxy
    }

    private fun invoke(name: String, listener: IOnPrimaryClipChangedListener? = null): Any? {
        val target = clipboard()
        val method: Method = target.javaClass.methods.firstOrNull { it.name == name }
            ?: throw NoSuchMethodException("IClipboard.$name")
        val args = ClipboardCallArguments.build(method.parameterTypes, SHELL_PACKAGE, userId) { type ->
            listener?.takeIf { type.isInstance(it) }
        }
        return method.invoke(target, *args)
    }

    private fun isWebUrl(text: String): Boolean {
        val trimmed = text.trim().lowercase()
        return trimmed.startsWith("http://") || trimmed.startsWith("https://")
    }

    companion object {
        private const val TAG = "BinderClipShizuku"
        const val SHELL_PACKAGE = "com.android.shell"
    }
}

/**
 * IClipboard signatures vary by release (Android 12 added userId, 13 attributionTag, 14 deviceId) and by OEM.
 * Arguments are chosen by type: the first String is the calling package, later Strings are a null attribution
 * tag; the first int is the user id, later ints are the default device id (0).
 */
internal object ClipboardCallArguments {
    fun build(types: Array<Class<*>>, callingPackage: String, userId: Int, other: (Class<*>) -> Any?): Array<Any?> {
        var strings = 0
        var ints = 0
        return types.map { type ->
            when (type) {
                String::class.java -> if (strings++ == 0) callingPackage else null
                Int::class.javaPrimitiveType -> if (ints++ == 0) userId else 0
                Boolean::class.javaPrimitiveType -> false
                else -> other(type)
            }
        }.toTypedArray()
    }
}
