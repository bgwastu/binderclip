package net.wastu.binderclip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppIsolationDeviceTest {
    @Test fun debugClipboardAndRootOperationsLeaveSystemStateUntouched() {
        assumeTrue(AppProfile.current.isDebug)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("net.wastu.binderclip.debug", context.packageName)
        val private = AppClipboard(context)
        val previous = private.primaryClip
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val system = activity.getSystemService(ClipboardManager::class.java)
                fun systemFingerprint() = system.primaryClip?.let { SyncProtocol.sha256Hex(it.toString()) }
                val before = systemFingerprint()
                val permission = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"
                val permissionBefore = activity.checkSelfPermission(permission)
                assertFalse(RootClipboardBridge.isAvailable())
                val link = BluetoothLink(activity, DeviceStore(activity), { "Debug test" }, object : BluetoothLink.Callbacks {
                    override fun onAuthenticated(remoteId: String, remoteName: String) {}
                    override fun onEndpoints(endpoints: List<String>) {}
                    override fun onLinkDown(reason: String) {}
                    override fun onText(text: String) {}
                    override fun onOpenUrl(url: String) {}
                    override fun onUnpair() {}
                })
                try { assertFalse("Debug must not enable the shared Bluetooth radio", link.requestEnable()) }
                finally { link.shutdown() }
                try {
                    private.setPrimaryClip(ClipData.newPlainText("Debug", "private isolated text"))
                    assertEquals("private isolated text", private.primaryClip?.getItemAt(0)?.text?.toString())
                    val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                    val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
                    bitmap.recycle()
                    val image = ImagePayload(mimeType = "image/png", data = bytes)
                    ImageClipboard.write(activity, private, image)
                    assertEquals(image.sha256, ImageClipboard.read(activity, private)?.sha256)
                    RootClipboardBridge.enableBackgroundAccess(activity)
                    RootClipboardBridge.ensureAutostart(activity)
                    RootClipboardBridge.syncKeepAlive(activity, false)
                    RootClipboardBridge.revokeBackgroundAccess(activity)
                    assertEquals(permissionBefore, activity.checkSelfPermission(permission))
                    assertEquals(PackageManager.PERMISSION_DENIED, permissionBefore)
                    assertEquals(before, systemFingerprint())
                    assertFalse(ServiceAutostart.shouldStart("android.intent.action.BOOT_COMPLETED", true))
                    val key = DeviceStore(activity).groupKey
                    val productionUrl = SyncProtocol.createPairingUrl("production", "Production", "test", emptyList(), AppProfile.production)
                    assertNull(SyncProtocol.parsePairingUrl(productionUrl))
                    assertTrue((key ?: byteArrayOf()).contentEquals(DeviceStore(activity).groupKey ?: byteArrayOf()))
                } finally {
                    if (previous != null) private.setPrimaryClip(previous) else private.clearPrimaryClip()
                }
            }
        }
    }
}
