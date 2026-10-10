package net.wastu.binderclip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Uses ordinary host shell processes, never su or Android's production root state. */
class RootCommandRunnerTest {
    @Test fun onlyZeroExitIsSuccess() {
        assertTrue(RootCommandRunner.succeeds(listOf("/bin/sh", "-c", "exit 0"), timeoutMs = 1_000))
        assertFalse(RootCommandRunner.succeeds(listOf("/bin/sh", "-c", "exit 1"), timeoutMs = 1_000))
        assertFalse(RootCommandRunner.succeeds(listOf("/binderclip-test/no-such-executable"), timeoutMs = 1_000))
    }

    @Test fun outputLargerThanAPipeBufferDoesNotBlockSuccessfulCommands() {
        assertTrue(RootCommandRunner.succeeds(
            listOf("/bin/sh", "-c", "i=0; while [ \"\$i\" -lt 10000 ]; do printf '%0100d' 0; i=\$((i + 1)); done"),
            timeoutMs = 5_000,
        ))
    }

    @Test fun continuousOutputCannotDefeatTheTimeout() {
        val started = System.nanoTime()
        assertFalse(RootCommandRunner.succeeds(
            listOf("/bin/sh", "-c", "while :; do printf 'discarded output\\n'; done"),
            timeoutMs = 100,
        ))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
    }

    @Test fun interruptedWorkDoesNotStartAnotherProcessOrClearTheInterrupt() {
        Thread.currentThread().interrupt()
        try {
            assertFalse(RootCommandRunner.succeeds(listOf("/bin/sh", "-c", "exit 0"), timeoutMs = 1_000))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
