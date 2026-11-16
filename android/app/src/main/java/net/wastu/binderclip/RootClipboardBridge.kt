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
    private const val ACCESS_NOTICE_SETTING = "clipboard_show_access_notifications"
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

    /** The su binary that last proved it grants uid 0; commands run through it exactly once. */
    @Volatile private var workingSu: String? = null

    private fun findSu(): String? = workingSu ?: runCatching {
        suPaths.firstOrNull { path ->
            RootCommandRunner.succeeds(listOf(path, "-c", "test \"\$(id -u)\" = 0"), timeoutMs = 3_000)
        }
    }.getOrNull()?.also { workingSu = it }

    fun isAvailable(): Boolean {
        if (AppProfile.current.isDebug) return false
        workingSu = null // root can be revoked at any time; re-probe
        return findSu() != null
    }

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

        // Suppress the system "pasted from your clipboard" toast. The setting is device-wide, so the
        // user's original value is backed up once and put back by revokeBackgroundAccess.
        val backup = shellQuote(accessNoticeBackup(context).path)
        runRootCommand(
            "[ -e $backup ] || settings get --user $userId secure $ACCESS_NOTICE_SETTING > $backup; " +
                "settings put --user $userId secure $ACCESS_NOTICE_SETTING 0 || settings put secure $ACCESS_NOTICE_SETTING 0",
            logFailure = false,
        )

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
        restoreAccessNotice(context, userId)
    }

    private fun accessNoticeBackup(context: Context) = java.io.File(context.noBackupFilesDir, "clipboard_access_notice.bak")

    /** Only 0/1 is ever written back; "null" (never set) deletes the key so the ROM default returns. */
    private fun restoreAccessNotice(context: Context, userId: Int) {
        val backup = shellQuote(accessNoticeBackup(context).path)
        runRootCommand(
            "[ -e $backup ] || exit 0; v=\$(cat $backup); case \"\$v\" in " +
                "0|1) settings put --user $userId secure $ACCESS_NOTICE_SETTING \"\$v\" ;; " +
                "*) settings delete --user $userId secure $ACCESS_NOTICE_SETTING ;; esac; rm -f $backup",
            logFailure = false,
        )
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

        val uri = item.uri
        val scheme = uri?.scheme?.lowercase()
        var plan = ClipboardRules.plan(directText, scheme, uri != null, imageMime)
        if (plan == ClipboardRules.Plan.Image) {
            val fingerprint = "unreadable-image:${uri ?: clip.description}"
            if (fingerprint == ignoredUnreadableImage) return Clip.UnreadableImage(fingerprint)
            ImageClipboard.read(context, clipboard)?.let {
                ignoredUnreadableImage = null
                return Clip.Image(it)
            }
            plan = ClipboardRules.afterImageFailure(directText)
            if (plan == ClipboardRules.Plan.Unsupported) {
                ignoredUnreadableImage = fingerprint
                return Clip.UnreadableImage(fingerprint)
            }
        }
        ignoredUnreadableImage = null
        when (plan) {
            ClipboardRules.Plan.DirectText -> Clip.Text(directText!!)
            ClipboardRules.Plan.WebUri -> Clip.Text(uri.toString())
            ClipboardRules.Plan.Coerce -> runCatching { item.coerceToText(context)?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let(Clip::Text)
            else -> null
        }
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

    // A failing command is not retried through other su paths: that ran non-idempotent
    // commands (grants, boot-script writes) up to four times.
    private fun runRootCommand(command: String, logFailure: Boolean = true): Boolean = runCatching {
        val su = findSu() ?: return false
        RootCommandRunner.succeeds(listOf(su, "-c", command), timeoutMs = 5_000).also {
            if (!it && logFailure) DiagnosticLog.warning("Root command failed (${command.substringBefore(' ')})")
        }
    }.getOrElse {
        if (logFailure) DiagnosticLog.warning("Root command exception (${command.substringBefore(' ')}): ${it.javaClass.simpleName}")
        false
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"
}
