package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TerminalLaunchSpecTest {
    @Test fun nativeTerminalCapabilitiesSurviveEnvResetButDoNotLeakIntoCommandMode() = runBlocking {
        val report = "printf '%s|%s|%s' \"\${TERM-unset}\" \"\${COLORTERM-unset}\" \"\${TERM_PROGRAM-unset}\""
        val spec = TerminalLaunchSpec(prefix = listOf("/usr/bin/env", "-i"), shell = "/bin/sh",
            interactiveArgs = listOf("-c", report))
        val cwd = File(requireNotNull(System.getProperty("java.io.tmpdir")))
        val native = LocalProcessSupervisor.capture(spec.interactiveCommand(), cwd)
        assertEquals(0, native.exitCode)
        assertEquals("xterm-256color|truecolor|Droide", native.output)
        val command = LocalProcessSupervisor.capture(spec.shellCommand(report), cwd)
        assertEquals("unset|unset|unset", command.output)
        assertFalse(spec.pipeCommand().any { it == "COLORTERM=truecolor" })
        assertTrue(spec.pipeCommand().contains("TERM=dumb"))
    }

    @Test fun explicitlySelectedTerminalCapabilitiesOverrideTheDefaults() = runBlocking {
        val spec = TerminalLaunchSpec(prefix = listOf("/usr/bin/env", "-i"), shell = "/bin/sh",
            interactiveArgs = listOf("-c", "printf '%s|%s' \"\$TERM\" \"\$COLORTERM\""),
            interactiveEnvironment = mapOf("TERM" to "vt100", "COLORTERM" to ""))
        val result = LocalProcessSupervisor.capture(spec.interactiveCommand(),
            File(requireNotNull(System.getProperty("java.io.tmpdir"))))
        assertEquals(0, result.exitCode)
        assertEquals("vt100|", result.output)
    }

    @Test fun interactiveLeaseSurvivesGuestEnvironmentReset() = runBlocking {
        val spec = TerminalLaunchSpec(prefix = listOf("/usr/bin/env", "-i"), shell = "/bin/sh",
            interactiveArgs = listOf("-c", "printf '%s' \"\$DROIDE_PROCESS_LEASE\""))
        val environment = mapOf("DROIDE_PROCESS_LEASE" to "outer-host-token")
        val cwd = File(requireNotNull(System.getProperty("java.io.tmpdir")))
        val withoutInjection = LocalProcessSupervisor.capture(spec.interactiveArgv, cwd, environment)
        assertEquals("", withoutInjection.output)
        val injected = LocalProcessSupervisor.capture(spec.interactiveCommand(mapOf("DROIDE_PROCESS_LEASE" to "owned-guest-token")), cwd, environment)
        assertEquals(0, injected.exitCode)
        assertEquals("owned-guest-token", injected.output)
    }

    @Test fun executesMultilineScriptWithGuestEnvironment() = runBlocking {
        val spec = TerminalLaunchSpec(prefix = listOf("/usr/bin/env"), shell = "/bin/sh")
        val argv = spec.shellCommand("cat <<'END'\nhello\nEND\nprintf '%s' \"\$DROIDE_JOB\"", mapOf("DROIDE_JOB" to "value with spaces"))
        val result = LocalProcessSupervisor.capture(argv, File(requireNotNull(System.getProperty("java.io.tmpdir"))))
        assertEquals(0, result.exitCode)
        assertEquals("hello\nvalue with spaces", result.output)
    }

    @Test fun rejectsNulAndOversizedScripts() {
        val spec = TerminalLaunchSpec(shell = "/bin/sh")
        for (script in listOf("echo\u0000bad", "x".repeat(32_001))) {
            try { spec.shellCommand(script); fail("Expected invalid script") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun argvAndEnvironmentRemainValidated() {
        val spec = TerminalLaunchSpec(shell = "/bin/sh")
        try { spec.command(listOf("echo", "two\nlines")); fail("Expected argv rejection") } catch (_: IllegalArgumentException) { }
        try { spec.shellCommand("true", mapOf("BAD-KEY" to "value")); fail("Expected env rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun interactivePresentationSurvivesEnvResetWithoutChangingAutomation() = runBlocking {
        val spec = TerminalLaunchSpec(prefix = listOf("/usr/bin/env", "-i"), shell = "/bin/sh",
            interactiveArgs = listOf("-c", "printf '%s' \"\$PS1\""),
            interactiveEnvironment = mapOf("PS1" to "\$ "))
        val cwd = File(requireNotNull(System.getProperty("java.io.tmpdir")))
        val interactive = LocalProcessSupervisor.capture(spec.interactiveCommand(), cwd)
        assertEquals(0, interactive.exitCode)
        assertEquals("\$ ", interactive.output)
        val automation = LocalProcessSupervisor.capture(spec.shellCommand("printf '%s' \"\${PROMPT_COMMAND-unset}\""), cwd)
        assertEquals("unset", automation.output)
    }

    @Test fun leaseInjectionKeepsPrecedenceOverInteractiveDefaults() {
        val spec = TerminalLaunchSpec(prefix = listOf("/usr/bin/env", "-i"), shell = "/bin/sh",
            interactiveEnvironment = mapOf("DROIDE_PROCESS_LEASE" to "default-token", "PS1" to "\$ "))
        val argv = spec.interactiveCommand(mapOf("DROIDE_PROCESS_LEASE" to "owned-token"))
        assertTrue("DROIDE_PROCESS_LEASE=owned-token" in argv)
        assertFalse("DROIDE_PROCESS_LEASE=default-token" in argv)
        assertTrue("PS1=\$ " in argv)
    }
}
