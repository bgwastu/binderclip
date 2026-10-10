package net.wastu.binderclip

import android.content.Context
import android.content.pm.PackageManager

/**
 * Root setup. It never reads a clipboard through a forged system identity.
 * After the owner approves BinderClip in KernelSU or Magisk, it attempts to grant background clipboard
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

    fun isAvailable(): Boolean = !AppProfile.current.isDebug && runCatching {
        suPaths.any { path ->
            RootCommandRunner.succeeds(listOf(path, "-c", "test \"\$(id -u)\" = 0"), timeoutMs = 3_000)
        }
    }.getOrDefault(false)

    fun enableBackgroundAccess(context: Context): Boolean {
        if (AppProfile.current.isDebug) return true
        val pkg = shellQuote(context.packageName)
        val userId = getUserId()

        // This is an AOSP signature permission; only ROMs that allow the grant will succeed.
        // Target the active user/space first, then try the ROM's default-user command.
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
            DiagnosticLog.warning("Background clipboard permission not granted; READ_CLIPBOARD AppOp alone does not grant background access (userId=$userId)")
        }
        return granted
    }

    fun revokeBackgroundAccess(context: Context) {
        if (AppProfile.current.isDebug) return
        val pkg = shellQuote(context.packageName)
        val userId = getUserId()
        runRootCommand("pm revoke --user $userId $pkg $BACKGROUND_CLIPBOARD_PERMISSION")
        runRootCommand("pm revoke $pkg $BACKGROUND_CLIPBOARD_PERMISSION")
        runRootCommand("cmd appops set --user $userId $pkg READ_CLIPBOARD default")
        runRootCommand("cmd appops set $pkg READ_CLIPBOARD default")
    }

    // ClipboardService checks this permission/focus before checking the READ_CLIPBOARD AppOp.
    // A successful `appops set ... allow` is not evidence of background clipboard access.
    fun hasBackgroundAccess(context: Context): Boolean =
        context.checkSelfPermission(BACKGROUND_CLIPBOARD_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun read(context: Context, clipboard: AppClipboard): Clip? = runCatching {
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

    /** Doze whitelist, background appops, notifications grant, and adb boot script — no manual OEM autostart UI. */
    fun ensureAutostart(context: Context) {
        if (AppProfile.current.isDebug) return
        if (!isAvailable()) return
        val packageName = ServiceAutostart.requireSafePackageName(context.packageName)
        applyKeepAlivePrivileges(packageName, getUserId())
        when {
            !runRootCommand("test -d /data/adb", logFailure = false) -> {}
            installBootScript(packageName) -> DiagnosticLog.info("Installed root boot keep-alive")
            else -> DiagnosticLog.warning("Could not install root boot keep-alive")
        }
    }

    fun syncKeepAlive(context: Context, paired: Boolean) {
        if (AppProfile.current.isDebug) return
        if (!isAvailable()) return
        if (paired) {
            ensureAutostart(context)
        } else if (removeBootScript(context.packageName)) {
            DiagnosticLog.info("Removed root boot keep-alive")
        }
    }

    private fun applyKeepAlivePrivileges(packageName: String, userId: Int) {
        ServiceAutostart.keepAliveCommands(packageName, userId).forEach {
            runRootCommand(it, logFailure = false)
        }
    }

    private fun installBootScript(packageName: String): Boolean {
        val encoded = android.util.Base64.encodeToString(
            ServiceAutostart.bootScript(packageName, getUserId()).toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val servicePath = "${ServiceAutostart.SERVICE_D_DIR}/${ServiceAutostart.bootScriptName(packageName)}"
        val bootCompletedPath = "${ServiceAutostart.BOOT_COMPLETED_D_DIR}/${ServiceAutostart.bootScriptName(packageName)}"
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

    private fun removeBootScript(packageName: String): Boolean = runRootCommand(
        "rm -f ${ServiceAutostart.SERVICE_D_DIR}/${ServiceAutostart.bootScriptName(packageName)} " +
            "${ServiceAutostart.BOOT_COMPLETED_D_DIR}/${ServiceAutostart.bootScriptName(packageName)}",
    )

    private fun runRootCommand(command: String, logFailure: Boolean = true): Boolean = runCatching {
        for (path in suPaths) {
            val success = RootCommandRunner.succeeds(listOf(path, "-c", command), timeoutMs = 5_000)
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
