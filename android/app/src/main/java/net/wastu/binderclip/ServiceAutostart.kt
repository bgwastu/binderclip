package net.wastu.binderclip

import android.content.Intent

/** Boot and root keep-alive policy for the persistent sync service. */
object ServiceAutostart {
    fun bootScriptName(packageName: String): String =
        if (requireSafePackageName(packageName) == AppProfile.debug.applicationId) AppProfile.debug.bootScriptName
        else { require(packageName == AppProfile.production.applicationId); AppProfile.production.bootScriptName }
    const val SERVICE_D_DIR = "/data/adb/service.d"
    const val BOOT_COMPLETED_D_DIR = "/data/adb/boot-completed.d"

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

    fun bootScript(packageName: String): String =
        BOOT_SCRIPT.replace("{{PKG}}", requireSafePackageName(packageName)).replace("{{$}}", "$")

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    private val BOOT_SCRIPT = """
        |#!/system/bin/sh
        |# BinderClip: start the sync service after boot. Installed only after the owner grants su.
        |PKG='{{PKG}}'
        |SVC="{{$}}PKG/net.wastu.binderclip.BinderClipService"
        |
        |while [ "{{$}}(getprop sys.boot_completed)" != "1" ]; do
        |  sleep 1
        |done
        |sleep 2
        |
        |dumpsys deviceidle whitelist +"{{$}}PKG" >/dev/null 2>&1
        |cmd deviceidle whitelist +"{{$}}PKG" >/dev/null 2>&1
        |cmd appops set "{{$}}PKG" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
        |cmd appops set "{{$}}PKG" RUN_IN_BACKGROUND allow >/dev/null 2>&1
        |pm grant "{{$}}PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
        |
        |am start-foreground-service -n "{{$}}SVC" >/dev/null 2>&1 \
        |  || am startservice -n "{{$}}SVC" >/dev/null 2>&1
        |""".trimMargin()
}
