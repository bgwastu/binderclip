package net.wastu.binderclip

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeFalse
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootAccessDeviceTest {
    @Test fun rootCanBeExecutedFromTheAppProcess() {
        assumeFalse("Isolated debug never requests root", AppProfile.current.isDebug)
        val probes = listOf("su", "/system/bin/su", "/system/xbin/su", "/data/adb/ksu/bin/su").map { path ->
            runCatching {
                val process = ProcessBuilder(path, "-c", "id -u").redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                "$path: exit=${process.exitValue()}, identity=$output"
            }.getOrElse { "$path: ${it.javaClass.simpleName}: ${it.message}" }
        }
        assertTrue("Root probes: ${probes.joinToString("; ")}", RootClipboardBridge.isAvailable())
    }
}
