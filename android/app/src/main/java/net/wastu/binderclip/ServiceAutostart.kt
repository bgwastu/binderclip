package net.wastu.binderclip

import android.content.Intent

/** Boot and root keep-alive policy for the persistent sync service. */
object ServiceAutostart {
    fun bootScriptName(packageName: String): String =
        if (requireSafePackageName(packageName) == AppProfile.debug.applicationId) AppProfile.debug.bootScriptName
        else { require(packageName == AppProfile.production.applicationId); AppProfile.production.bootScriptName }
    const val SERVICE_D_DIR = "/data/adb/service.d"
    const val BOOT_COMPLETED_D_DIR = "/data/adb/boot-completed.d"
    const val BOOT_WAIT_SECONDS = 90

    val BOOT_ACTIONS = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON",
    )

    fun shouldStart(action: String?, paired: Boolean, profile: AppProfile = AppProfile.current): Boolean =
        !profile.isDebug && paired && action != null && action in BOOT_ACTIONS

    fun requireSafePackageName(packageName: String): String {
        val pkg = packageName.trim()
        require(pkg.matches(PACKAGE_NAME)) { "invalid package name" }
        return pkg
    }

    /** Best effort on each ROM. AOSP accepts `active`, not the bucket name `exempted`. */
    internal fun keepAliveCommands(packageName: String, userId: Int): List<String> {
        val pkg = requireSafePackageName(packageName)
        require(userId >= 0) { "invalid user id" }
        return listOf(
            "dumpsys deviceidle whitelist +$pkg",
            "cmd deviceidle whitelist +$pkg",
            "cmd appops set --user $userId $pkg RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set --user $userId $pkg RUN_IN_BACKGROUND allow",
            "am set-standby-bucket --user $userId $pkg active",
            "pm grant --user $userId $pkg android.permission.POST_NOTIFICATIONS",
        )
    }

    fun bootScript(packageName: String, userId: Int = 0): String = BOOT_SCRIPT
        .replace("{{PKG}}", requireSafePackageName(packageName))
        .replace("{{USER_ID}}", userId.toString())
        .replace("{{BOOT_WAIT_SECONDS}}", BOOT_WAIT_SECONDS.toString())
        .replace("{{KEEP_ALIVE}}", keepAliveCommands(packageName, userId).joinToString("\n") { "$it >/dev/null 2>&1" })
        .replace("{{$}}", "$")

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    private val BOOT_SCRIPT = """
        |#!/system/bin/sh
        |# BinderClip: start the sync service after boot. Installed only after the owner grants su.
        |PKG='{{PKG}}'
        |USER_ID={{USER_ID}}
        |SVC="{{$}}PKG/net.wastu.binderclip.BinderClipService"
        |
        |WAITED=0
        |while [ "{{$}}(getprop sys.boot_completed)" != "1" ]; do
        |  [ "{{$}}WAITED" -lt {{BOOT_WAIT_SECONDS}} ] || exit 1
        |  sleep 1
        |  WAITED={{$}}((WAITED + 1))
        |done
        |sleep 2
        |
        |# Query enabled components rather than parsing the OEM-dependent `pm dump` format.
        |RECEIVER={{$}}(cmd package query-receivers --components --user "{{$}}USER_ID" -n "{{$}}PKG/net.wastu.binderclip.BootReceiver" 2>/dev/null) || exit 0
        |case "{{$}}RECEIVER" in
        |  "{{$}}PKG/.BootReceiver"|"{{$}}PKG/net.wastu.binderclip.BootReceiver") ;;
        |  *) exit 0 ;;
        |esac
        |SERVICE={{$}}(cmd package query-services --components --user "{{$}}USER_ID" -n "{{$}}SVC" 2>/dev/null) || exit 0
        |case "{{$}}SERVICE" in
        |  "{{$}}PKG/.BinderClipService"|"{{$}}SVC") ;;
        |  *) exit 0 ;;
        |esac
        |
        |{{KEEP_ALIVE}}
        |
        |am start-foreground-service --user "{{$}}USER_ID" -n "{{$}}SVC" >/dev/null 2>&1 \
        |  || am startservice --user "{{$}}USER_ID" -n "{{$}}SVC" >/dev/null 2>&1
        |""".trimMargin()
}
