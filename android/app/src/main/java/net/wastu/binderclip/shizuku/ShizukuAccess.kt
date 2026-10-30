package net.wastu.binderclip.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/** What the phone still needs before Shizuku can capture the clipboard. Order matches the setup steps. */
enum class ShizukuStatus { NotInstalled, NotRunning, Outdated, NotAllowed, Denied, Ready }

object ShizukuSetup {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION_REQUEST_CODE = 0xB1C

    /** Sui (root) exposes the same binder without the Shizuku app, so a live binder implies "installed". */
    fun status(installed: Boolean, running: Boolean, preV11: Boolean, granted: Boolean, deniedForever: Boolean): ShizukuStatus = when {
        !running && !installed -> ShizukuStatus.NotInstalled
        !running -> ShizukuStatus.NotRunning
        preV11 -> ShizukuStatus.Outdated
        granted -> ShizukuStatus.Ready
        deniedForever -> ShizukuStatus.Denied
        else -> ShizukuStatus.NotAllowed
    }
}

/**
 * Process-wide view of Shizuku. Binder and permission callbacks update [status] immediately, so the setup
 * card and the sync service advance without polling; [refresh] re-checks on resume (e.g. after install).
 */
object ShizukuAccess {
    private val mutableStatus = MutableStateFlow(ShizukuStatus.NotInstalled)
    val status: StateFlow<ShizukuStatus> = mutableStatus
    private val listeners = CopyOnWriteArraySet<(ShizukuStatus) -> Unit>()
    @Volatile private var appContext: Context? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == ShizukuSetup.PERMISSION_REQUEST_CODE) refresh()
    }

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun addListener(listener: (ShizukuStatus) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (ShizukuStatus) -> Unit) { listeners.remove(listener) }

    fun refresh(): ShizukuStatus {
        val context = appContext ?: return mutableStatus.value
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val preV11 = running && runCatching { Shizuku.isPreV11() }.getOrDefault(false)
        val granted = running && !preV11 &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val deniedForever = running && !preV11 && !granted &&
            runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        val next = ShizukuSetup.status(isInstalled(context), running, preV11, granted, deniedForever)
        val changed = mutableStatus.value != next
        mutableStatus.value = next
        if (changed) listeners.forEach { it(next) }
        return next
    }

    /** Shows Shizuku's own allow dialog. A "denied forever" answer must be changed inside Shizuku. */
    fun requestPermission() {
        if (refresh() != ShizukuStatus.NotAllowed) return
        runCatching { Shizuku.requestPermission(ShizukuSetup.PERMISSION_REQUEST_CODE) }
    }

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(ShizukuSetup.PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(ShizukuSetup.PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
