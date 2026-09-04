package net.wastu.binderclip

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Optional root integration. It never reads a clipboard through a forged system identity.
 * After the owner approves BinderClip in KernelSU or Magisk, it grants background clipboard
 * access and can install a late-start boot script so the sync service comes back after reboot
 * without opening the app.
 */
object RootClipboardBridge {
    private const val BACKGROUND_CLIPBOARD_PERMISSION = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"
    @Volatile private var ignoredUnreadableImage: String? = null
    sealed interface Clip {
        val fingerprint: String

        data class Text(val value: String) : Clip {
            override val fingerprint = "text:$value"
        }

        data class Image(val value: ImagePayload) : Clip {
            override val fingerprint = "image:${value.sha256}"
        }

        /** A private provider can expose an image only to its share-sheet recipient. */
        data class UnreadableImage(override val fingerprint: String) : Clip
    }

    private fun getUserId(): Int = runCatching {
        android.os.Process.myUid() / 100000
    }.getOrDefault(0)

    private val suPaths = listOf("su", "/system/bin/su", "/system/xbin/su", "/data/adb/ksu/bin/su")

    fun isAvailable(): Boolean = runCatching {
        suPaths.any { path ->
            runCatching {
                val process = ProcessBuilder(path, "-c", "id -u").redirectErrorStream(true).start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
                process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                    output.lines().any { it.trim() == "0" || it.contains("uid=0(") }
            }.getOrDefault(false)
        }
    }.getOrDefault(false)

    fun enableBackgroundAccess(context: Context): Boolean {
        val pkg = shellQuote(context.packageName)
        val userId = getUserId()

        // Grant permission targeting the active user/space and fallback to global
        runRootCommand("pm grant --user $userId $pkg $BACKGROUND_CLIPBOARD_PERMISSION")
        runRootCommand("pm grant $pkg $BACKGROUND_CLIPBOARD_PERMISSION")

        // Set appops for the active user/space and fallback
        runRootCommand("cmd appops set --user $userId $pkg READ_CLIPBOARD allow")
        runRootCommand("cmd appops set $pkg READ_CLIPBOARD allow")

        // Suppress system "pasted from your clipboard" toast
        runRootCommand("settings put --user $userId secure clipboard_show_access_notifications 0", logFailure = false)
        runRootCommand("settings put secure clipboard_show_access_notifications 0", logFailure = false)
        runRootCommand("cmd settings put --user $userId secure clipboard_show_access_notifications 0", logFailure = false)
        runRootCommand("cmd settings put secure clipboard_show_access_notifications 0", logFailure = false)

        val granted = hasBackgroundAccess(context)
        if (!granted) {
            DiagnosticLog.warning("Background clipboard permission not granted after root commands (userId=$userId)")
        }
        return granted
    }

    fun revokeBackgroundAccess(context: Context) {
        val pkg = shellQuote(context.packageName)
        val userId = getUserId()
        runRootCommand("pm revoke --user $userId $pkg $BACKGROUND_CLIPBOARD_PERMISSION")
        runRootCommand("pm revoke $pkg $BACKGROUND_CLIPBOARD_PERMISSION")
        runRootCommand("cmd appops set --user $userId $pkg READ_CLIPBOARD default")
        runRootCommand("cmd appops set $pkg READ_CLIPBOARD default")
    }

    fun hasBackgroundAccess(context: Context): Boolean =
        context.checkSelfPermission(BACKGROUND_CLIPBOARD_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun read(context: Context, clipboard: ClipboardManager): Clip? = runCatching {
        val clip = clipboard.primaryClip ?: return null
        val item = clip.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        val imageMime = clip.description?.filterMimeTypes("image/*")
            ?.firstOrNull { it in ImagePayload.ALLOWED_MIME_TYPES }
        val directText = item.text?.toString()?.takeIf { it.isNotBlank() }

        // 1. Direct text / URL precedence
        if (directText != null) {
            val trimmed = directText.trim().lowercase()
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || imageMime == null) {
                ignoredUnreadableImage = null
                return Clip.Text(directText)
            }
        }

        // 2. Image content
        if (imageMime != null || item.uri != null) {
            val scheme = item.uri?.scheme?.lowercase()
            if (scheme == "content" || scheme == "file" || imageMime != null) {
                val fingerprint = "unreadable-image:${item.uri ?: clip.description}"
                if (fingerprint == ignoredUnreadableImage) return Clip.UnreadableImage(fingerprint)
                ImageClipboard.read(context, clipboard)?.let {
                    ignoredUnreadableImage = null
                    return Clip.Image(it)
                }
                if (scheme == "file") {
                    ignoredUnreadableImage = fingerprint
                    return Clip.UnreadableImage(fingerprint)
                }
            } else if (scheme == "http" || scheme == "https") {
                ignoredUnreadableImage = null
                return Clip.Text(item.uri.toString())
            }
        }
        ignoredUnreadableImage = null
        val text = directText ?: runCatching { item.coerceToText(context)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
        text?.let(Clip::Text)
    }.getOrNull()

    fun syncKeepAlive(context: Context, paired: Boolean) {
        if (!isAvailable()) return
        val packageName = ServiceAutostart.requireSafePackageName(context.packageName)
        if (paired) {
            applyKeepAlivePrivileges(packageName)
            when {
                !runRootCommand("test -d /data/adb", logFailure = false) -> {}
                installBootScript(packageName) -> DiagnosticLog.info("Installed root boot keep-alive")
                else -> DiagnosticLog.warning("Could not install root boot keep-alive")
            }
        } else if (removeBootScript()) {
            DiagnosticLog.info("Removed root boot keep-alive")
        }
    }

    private fun applyKeepAlivePrivileges(packageName: String) {
        runRootCommand("dumpsys deviceidle whitelist +$packageName", logFailure = false)
        runRootCommand("cmd deviceidle whitelist +$packageName", logFailure = false)
        runRootCommand("cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow", logFailure = false)
        runRootCommand("cmd appops set $packageName RUN_IN_BACKGROUND allow", logFailure = false)
        runRootCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS", logFailure = false)
    }

    private fun installBootScript(packageName: String): Boolean {
        val encoded = android.util.Base64.encodeToString(
            ServiceAutostart.bootScript(packageName).toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val servicePath = "${ServiceAutostart.SERVICE_D_DIR}/${ServiceAutostart.BOOT_SCRIPT_NAME}"
        val bootCompletedPath = "${ServiceAutostart.BOOT_COMPLETED_D_DIR}/${ServiceAutostart.BOOT_SCRIPT_NAME}"
        val installed = runRootCommand(
            "mkdir -p ${ServiceAutostart.SERVICE_D_DIR} && " +
                "printf '%s' $encoded | base64 -d > $servicePath && chmod 755 $servicePath",
        )
        runRootCommand(
            "if [ -d ${ServiceAutostart.BOOT_COMPLETED_D_DIR} ]; then " +
                "printf '%s' $encoded | base64 -d > $bootCompletedPath && chmod 755 $bootCompletedPath; fi",
            logFailure = false,
        )
        return installed
    }

    private fun removeBootScript(): Boolean = runRootCommand(
        "rm -f ${ServiceAutostart.SERVICE_D_DIR}/${ServiceAutostart.BOOT_SCRIPT_NAME} " +
            "${ServiceAutostart.BOOT_COMPLETED_D_DIR}/${ServiceAutostart.BOOT_SCRIPT_NAME}",
    )

    private fun runRootCommand(command: String, logFailure: Boolean = true): Boolean = runCatching {
        for (path in suPaths) {
            val success = runCatching {
                val process = ProcessBuilder(path, "-c", command).redirectErrorStream(true).start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
                val finished = process.waitFor(5, TimeUnit.SECONDS)
                finished && process.exitValue() == 0
            }.getOrDefault(false)
            if (success) return true
        }
        if (logFailure) DiagnosticLog.warning("Root command failed ($command)")
        false
    }.getOrElse {
        if (logFailure) DiagnosticLog.warning("Root command exception ($command): ${it.message}")
        false
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"
}
