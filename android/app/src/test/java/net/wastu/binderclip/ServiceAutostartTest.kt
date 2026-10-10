package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ServiceAutostartTest {
    @Test
    fun startsAfterBootWhenPaired() {
        assertTrue(ServiceAutostart.shouldStart("android.intent.action.BOOT_COMPLETED", paired = true, profile = AppProfile.production))
        assertTrue(ServiceAutostart.shouldStart("android.intent.action.MY_PACKAGE_REPLACED", paired = true, profile = AppProfile.production))
        assertTrue(ServiceAutostart.shouldStart("android.intent.action.QUICKBOOT_POWERON", paired = true, profile = AppProfile.production))
        assertTrue(ServiceAutostart.shouldStart("com.htc.intent.action.QUICKBOOT_POWERON", paired = true, profile = AppProfile.production))
    }

    @Test
    fun doesNotStartWhenUnpairedOrUnknownAction() {
        assertFalse(ServiceAutostart.shouldStart("android.intent.action.BOOT_COMPLETED", paired = false, profile = AppProfile.production))
        assertFalse(ServiceAutostart.shouldStart("android.intent.action.SCREEN_ON", paired = true, profile = AppProfile.production))
        assertFalse(ServiceAutostart.shouldStart(null, paired = true, profile = AppProfile.production))
        assertFalse(ServiceAutostart.shouldStart("", paired = true, profile = AppProfile.production))
    }

    @Test
    fun bootScriptStartsForegroundServiceAndWaitsForBoot() {
        val script = ServiceAutostart.bootScript("net.wastu.binderclip")
        assertTrue(script.startsWith("#!/system/bin/sh\n"))
        assertTrue(script.contains("PKG='net.wastu.binderclip'"))
        assertTrue(script.contains("SVC=\"\$PKG/net.wastu.binderclip.BinderClipService\""))
        assertTrue(script.contains("getprop sys.boot_completed"))
        assertTrue(script.contains("dumpsys deviceidle whitelist +net.wastu.binderclip"))
        assertTrue(script.contains("cmd appops set --user 0 net.wastu.binderclip RUN_ANY_IN_BACKGROUND allow"))
        assertTrue(script.contains("am start-foreground-service --user \"\$USER_ID\" -n \"\$SVC\""))
        assertTrue(script.contains("am startservice --user \"\$USER_ID\" -n \"\$SVC\""))
        assertFalse(script.contains("su "))
        assertFalse(script.contains("{{PKG}}"))
        assertFalse(script.contains("{{$}}"))
    }

    @Test
    fun bootScriptRejectsUnsafePackageName() {
        assertThrows(IllegalArgumentException::class.java) {
            ServiceAutostart.bootScript("net.wastu.binderclip; rm -rf /")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServiceAutostart.bootScript("binderclip")
        }
        assertEquals("net.wastu.binderclip", ServiceAutostart.requireSafePackageName("net.wastu.binderclip"))
        assertThrows(IllegalArgumentException::class.java) {
            ServiceAutostart.bootScript("net.wastu.binderclip", userId = -1)
        }
    }

    @Test fun bootWaitStopsAfterNinetySecondsWithoutStartingOrGrantingAnything() {
        val result = runBoot(bootAfter = Int.MAX_VALUE)
        assertEquals(1, result.exitCode)
        assertEquals(ServiceAutostart.BOOT_WAIT_SECONDS, result.calls.count { it == "sleep 1" })
        assertFalse(result.calls.any { it.startsWith("am ") || it.startsWith("cmd ") || it.startsWith("pm ") })
    }

    @Test fun bootCanCompleteAtTheWaitBoundary() {
        val result = runBoot(bootAfter = ServiceAutostart.BOOT_WAIT_SECONDS)
        assertEquals(0, result.exitCode)
        assertEquals(ServiceAutostart.BOOT_WAIT_SECONDS, result.calls.count { it == "sleep 1" })
        assertTrue(result.calls.any { it.startsWith("am start-foreground-service ") })
    }

    @Test fun disabledOrMissingComponentsPreventRootPrivilegesAndServiceStartup() {
        for ((receiver, service) in listOf(
            "missing" to "short", "short" to "missing", "error" to "short", "short" to "error",
            "partial-error" to "short", "short" to "partial-error",
        )) {
            val result = runBoot(receiver = receiver, service = service)
            assertEquals(0, result.exitCode)
            assertFalse(result.calls.any { it.startsWith("am ") || it.startsWith("dumpsys ") || it.startsWith("pm ") })
            assertFalse(result.calls.any { it.startsWith("cmd appops ") || it.startsWith("cmd deviceidle ") })
        }
    }

    @Test fun fullAndShortComponentNamesAreAcceptedAndForegroundStartupHasAFallback() {
        for (componentFormat in listOf("short", "full")) {
            val result = runBoot(receiver = componentFormat, service = componentFormat, foregroundFails = true)
            assertEquals(0, result.exitCode)
            val starts = result.calls.filter { it.startsWith("am start") }
            assertEquals(listOf(
                "am start-foreground-service --user 0 -n net.wastu.binderclip/net.wastu.binderclip.BinderClipService",
                "am startservice --user 0 -n net.wastu.binderclip/net.wastu.binderclip.BinderClipService",
            ), starts)
        }
    }

    @Test fun commandsTargetTheInstalledUserAndUnsupportedPrivilegesDoNotPreventStartup() {
        val result = runBoot(userId = 10, privilegesFail = true)
        assertEquals(0, result.exitCode)
        assertTrue(result.calls.contains("cmd package query-receivers --components --user 10 -n net.wastu.binderclip/net.wastu.binderclip.BootReceiver"))
        assertTrue(result.calls.contains("cmd package query-services --components --user 10 -n net.wastu.binderclip/net.wastu.binderclip.BinderClipService"))
        assertTrue(result.calls.contains("am set-standby-bucket --user 10 net.wastu.binderclip active"))
        assertTrue(result.calls.contains("pm grant --user 10 net.wastu.binderclip android.permission.POST_NOTIFICATIONS"))
        assertTrue(result.calls.contains("cmd appops set --user 10 net.wastu.binderclip RUN_ANY_IN_BACKGROUND allow"))
        assertTrue(result.calls.contains("am start-foreground-service --user 10 -n net.wastu.binderclip/net.wastu.binderclip.BinderClipService"))
    }

    private data class ScriptResult(val exitCode: Int, val calls: List<String>)

    /** Execute the generated script with command stubs, without root, Android, or real waits. */
    private fun runBoot(
        bootAfter: Int = 0,
        receiver: String = "short",
        service: String = "short",
        foregroundFails: Boolean = false,
        privilegesFail: Boolean = false,
        userId: Int = 0,
    ): ScriptResult {
        val directory = Files.createTempDirectory("binderclip-boot-test").toFile()
        try {
            val bin = directory.resolve("bin").apply { mkdir() }
            val calls = directory.resolve("calls")
            val stub = """
                |#!/bin/sh
                |NAME={{$}}{0##*/}
                |printf '%s' "{{$}}NAME" >> "{{$}}BINDERCLIP_TEST_CALLS"
                |for ARG in "{{$}}@"; do printf ' %s' "{{$}}ARG" >> "{{$}}BINDERCLIP_TEST_CALLS"; done
                |printf '\n' >> "{{$}}BINDERCLIP_TEST_CALLS"
                |case "{{$}}NAME" in
                |  getprop)
                |    COUNT=0
                |    [ ! -f "{{$}}BINDERCLIP_TEST_COUNT" ] || read -r COUNT < "{{$}}BINDERCLIP_TEST_COUNT"
                |    COUNT={{$}}((COUNT + 1))
                |    printf '%s' "{{$}}COUNT" > "{{$}}BINDERCLIP_TEST_COUNT"
                |    if [ "{{$}}COUNT" -gt "{{$}}BINDERCLIP_TEST_BOOT_AFTER" ]; then printf '1\n'; else printf '0\n'; fi
                |    exit 0 ;;
                |  sleep) exit 0 ;;
                |  cmd)
                |    if [ "{{$}}1" = package ]; then
                |      case "{{$}}2" in
                |        query-receivers) FORMAT="{{$}}BINDERCLIP_TEST_RECEIVER"; COMPONENT=BootReceiver ;;
                |        query-services) FORMAT="{{$}}BINDERCLIP_TEST_SERVICE"; COMPONENT=BinderClipService ;;
                |        *) exit 1 ;;
                |      esac
                |      case "{{$}}FORMAT" in
                |        short) printf 'net.wastu.binderclip/.%s\n' "{{$}}COMPONENT" ;;
                |        full) printf 'net.wastu.binderclip/net.wastu.binderclip.%s\n' "{{$}}COMPONENT" ;;
                |        error) exit 1 ;;
                |        partial-error) printf 'net.wastu.binderclip/.%s\n' "{{$}}COMPONENT"; exit 1 ;;
                |        *) printf 'No components found\n' ;;
                |      esac
                |      exit 0
                |    fi ;;
                |  am)
                |    if [ "{{$}}1" = start-foreground-service ]; then exit "{{$}}BINDERCLIP_TEST_FOREGROUND_FAILS"; fi
                |    [ "{{$}}1" != startservice ] || exit 0 ;;
                |esac
                |exit "{{$}}BINDERCLIP_TEST_PRIVILEGES_FAIL"
            """.trimMargin().replace("{{$}}", "$")
            for (name in listOf("getprop", "sleep", "cmd", "am", "pm", "dumpsys")) {
                bin.resolve(name).apply { writeText(stub); setExecutable(true) }
            }
            val script = directory.resolve("boot.sh").apply {
                writeText(ServiceAutostart.bootScript("net.wastu.binderclip", userId))
            }
            val builder = ProcessBuilder("/bin/sh", script.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("output"))
            builder.environment().putAll(mapOf(
                "PATH" to "${bin.absolutePath}:/usr/bin:/bin",
                "BINDERCLIP_TEST_CALLS" to calls.absolutePath,
                "BINDERCLIP_TEST_COUNT" to directory.resolve("count").absolutePath,
                "BINDERCLIP_TEST_BOOT_AFTER" to bootAfter.toString(),
                "BINDERCLIP_TEST_RECEIVER" to receiver,
                "BINDERCLIP_TEST_SERVICE" to service,
                "BINDERCLIP_TEST_FOREGROUND_FAILS" to if (foregroundFails) "1" else "0",
                "BINDERCLIP_TEST_PRIVILEGES_FAIL" to if (privilegesFail) "1" else "0",
            ))
            val process = builder.start()
            try {
                assertTrue("Boot script failed to terminate", process.waitFor(10, TimeUnit.SECONDS))
                return ScriptResult(process.exitValue(), calls.readLines())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
