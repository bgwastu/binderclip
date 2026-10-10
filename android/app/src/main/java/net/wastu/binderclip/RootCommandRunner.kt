package net.wastu.binderclip

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Root command output is unused. Discard it so a full pipe cannot defeat the timeout. */
internal object RootCommandRunner {
    fun succeeds(arguments: List<String>, timeoutMs: Long): Boolean {
        require(timeoutMs > 0)
        if (Thread.currentThread().isInterrupted) return false
        var process: Process? = null
        try {
            process = ProcessBuilder(arguments)
                .redirectErrorStream(true)
                .redirectOutput(File("/dev/null"))
                .start()
            process.outputStream.close()
            return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) && process.exitValue() == 0
        } catch (_: IOException) {
            return false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            process?.let {
                if (it.isAlive) it.destroyForcibly()
                runCatching { it.inputStream.close() }
                runCatching { it.errorStream.close() }
                runCatching { it.outputStream.close() }
            }
        }
    }
}
