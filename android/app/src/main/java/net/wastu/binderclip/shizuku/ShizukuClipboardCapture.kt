package net.wastu.binderclip.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import net.wastu.binderclip.BuildConfig
import net.wastu.binderclip.DiagnosticLog
import net.wastu.binderclip.ImagePayload
import net.wastu.binderclip.LocalClipboardContent
import rikka.shizuku.Shizuku

/**
 * Binds the shell-uid clipboard reader while Shizuku is ready. Clip callbacks arrive on a binder thread;
 * [onClip] receives the parsed content plus the clip's platform timestamp. [onActiveChanged] reports
 * whether the platform accepted the listener and the first read.
 */
class ShizukuClipboardCapture(
    context: Context,
    private val executor: java.util.concurrent.Executor,
    private val onClip: (LocalClipboardContent, Long) -> Unit,
    private val onActiveChanged: (Boolean) -> Unit,
) {
    private val packageName = context.packageName
    private val userId = android.os.Process.myUid() / 100_000
    private val args = Shizuku.UserServiceArgs(ComponentName(packageName, ShizukuClipboardUserService::class.java.name))
        .daemon(false)
        .processNameSuffix("clipboard")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
        .tag("binderclip-clipboard")

    @Volatile private var service: IShizukuClipboardService? = null
    @Volatile private var bound = false
    @Volatile var active = false
        private set

    private val watcher = object : IClipboardWatcher.Stub() {
        override fun onText(text: String?, token: Long) {
            onClip(text?.takeIf { it.isNotBlank() }?.let(LocalClipboardContent::Text) ?: LocalClipboardContent.Unsupported, token)
        }

        override fun onImage(data: ParcelFileDescriptor?, mimeType: String?, size: Long, token: Long) {
            val image = data?.let { readImage(it, mimeType, size) }
            onClip(image?.let(LocalClipboardContent::Image) ?: LocalClipboardContent.Unsupported, token)
        }

        override fun onUnsupported(token: Long) = onClip(LocalClipboardContent.Unsupported, token)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val remote = binder?.takeIf { it.pingBinder() }?.let(IShizukuClipboardService.Stub::asInterface) ?: return
            service = remote
            // Connection callbacks run on the main thread; the first read can stream an image.
            executor.execute {
                if (service !== remote) return@execute
                val ok = runCatching { remote.startWatching(watcher, userId) && remote.dispatchCurrent() }.getOrDefault(false)
                if (ok) DiagnosticLog.info("Shizuku clipboard capture active")
                else DiagnosticLog.warning("Shizuku clipboard capture was refused by the platform")
                if (service === remote) setActive(ok)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            setActive(false)
        }
    }

    /** Idempotent; safe to call on every status change. */
    @Synchronized
    fun start() {
        if (bound) return
        if (ShizukuAccess.refresh() != ShizukuStatus.Ready) return
        bound = runCatching { Shizuku.bindUserService(args, connection); true }.getOrElse {
            DiagnosticLog.warning("Could not bind Shizuku clipboard service: ${it.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { service?.stopWatching() }
        if (bound) runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
        bound = false
        setActive(false)
    }

    /** Called when the Shizuku binder dies: the user service died with it, so a later start must rebind. */
    @Synchronized
    fun binderLost() {
        service = null
        bound = false
        setActive(false)
    }

    private fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        onActiveChanged(value)
    }

    private fun readImage(data: ParcelFileDescriptor, mimeType: String?, size: Long): ImagePayload? = runCatching {
        if (mimeType !in ImagePayload.ALLOWED_MIME_TYPES || size <= 0 || size > ImagePayload.MAXIMUM_BYTES) {
            data.close()
            return null
        }
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(data).use { input ->
            val output = java.io.ByteArrayOutputStream(size.toInt())
            val buffer = ByteArray(16_384)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= ImagePayload.MAXIMUM_BYTES) { "Image too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        ImagePayload(mimeType = mimeType!!, data = bytes)
    }.onFailure { DiagnosticLog.warning("Could not read Shizuku clipboard image: ${it.javaClass.simpleName}") }.getOrNull()
}
