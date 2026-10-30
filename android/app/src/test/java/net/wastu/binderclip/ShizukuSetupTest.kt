package net.wastu.binderclip

import net.wastu.binderclip.shizuku.ClipboardCallArguments
import net.wastu.binderclip.shizuku.ShizukuSetup
import net.wastu.binderclip.shizuku.ShizukuStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShizukuSetupTest {
    private fun status(installed: Boolean = true, running: Boolean = true, preV11: Boolean = false, granted: Boolean = false, denied: Boolean = false) =
        ShizukuSetup.status(installed, running, preV11, granted, denied)

    @Test
    fun statusFollowsSetupOrder() {
        assertEquals(ShizukuStatus.NotInstalled, status(installed = false, running = false))
        assertEquals(ShizukuStatus.NotRunning, status(running = false))
        assertEquals(ShizukuStatus.Outdated, status(preV11 = true, granted = true))
        assertEquals(ShizukuStatus.NotAllowed, status())
        assertEquals(ShizukuStatus.Denied, status(denied = true))
        assertEquals(ShizukuStatus.Ready, status(granted = true))
    }

    @Test
    fun suiBinderCountsAsInstalledWithoutShizukuApp() {
        assertEquals(ShizukuStatus.Ready, status(installed = false, granted = true))
    }

    @Test
    fun setupCardShowsOnlyTheNextStep() {
        fun step(status: ShizukuStatus, paired: Boolean = true, root: Boolean = false, active: Boolean = false) =
            CaptureSetup.step(paired, root, status, active)
        assertEquals(CaptureStep.Hidden, step(ShizukuStatus.NotInstalled, paired = false))
        assertEquals(CaptureStep.Hidden, step(ShizukuStatus.NotInstalled, root = true))
        assertEquals(CaptureStep.Install, step(ShizukuStatus.NotInstalled))
        assertEquals(CaptureStep.Start, step(ShizukuStatus.NotRunning))
        assertEquals(CaptureStep.Update, step(ShizukuStatus.Outdated))
        assertEquals(CaptureStep.Allow, step(ShizukuStatus.NotAllowed))
        assertEquals(CaptureStep.Denied, step(ShizukuStatus.Denied))
        assertEquals(CaptureStep.Connecting, step(ShizukuStatus.Ready))
        assertEquals(CaptureStep.Working, step(ShizukuStatus.Ready, active = true))
        assertEquals(1, CaptureSetup.stepNumber(CaptureStep.Install))
        assertEquals(3, CaptureSetup.stepNumber(CaptureStep.Denied))
        assertNull(CaptureSetup.stepNumber(CaptureStep.Working))
    }

    @Test
    fun clipboardArgumentsMatchEachPlatformSignature() {
        val int = Int::class.javaPrimitiveType!!
        val str = String::class.java
        val pkg = "com.android.shell"
        // Android 12: getPrimaryClip(pkg, userId)
        assertArrayEquals(arrayOf<Any?>(pkg, 10), ClipboardCallArguments.build(arrayOf(str, int), pkg, 10) { null })
        // Android 13: getPrimaryClip(pkg, attributionTag, userId)
        assertArrayEquals(arrayOf<Any?>(pkg, null, 10), ClipboardCallArguments.build(arrayOf(str, str, int), pkg, 10) { null })
        // Android 14+: getPrimaryClip(pkg, attributionTag, userId, deviceId)
        assertArrayEquals(arrayOf<Any?>(pkg, null, 10, 0), ClipboardCallArguments.build(arrayOf(str, str, int, int), pkg, 10) { null })
        // Listener registration supplies the listener by type.
        val listener = Any()
        assertArrayEquals(arrayOf<Any?>(listener, pkg, null, 0, 0),
            ClipboardCallArguments.build(arrayOf(Any::class.java, str, str, int, int), pkg, 0) { listener })
    }
}
