package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TerminalPipeShellTest {
    private val token = "a".repeat(32)
    private fun run(vararg commands: String): ExecResult {
        val spec = TerminalLaunchSpec(prefix = listOf("/usr/bin/env", "-i", "HOME=/tmp", "TERM=xterm-256color", "PATH=/usr/bin:/bin"),
            shell = "/bin/bash", interactiveArgs = listOf("--noprofile", "--norc", "-i"))
        val input = TerminalPipeShell.ready() + "\n" + commands.joinToString("\n") { TerminalPipeShell.command(it) } + "\n"
        val child = ProcessBuilder(spec.pipeCommand(mapOf(TerminalCommandActivityTracker.TOKEN_ENV to token)))
            .redirectErrorStream(true).start()
        child.outputStream.bufferedWriter().use { it.write(input) }
        val text = child.inputStream.bufferedReader().readText()
        assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
        return ExecResult(child.exitValue(), text, false)
    }

    @Test fun pipeLaunchHasNoInteractiveFlagsOrFalseTerminalTypeWhileNativeFlagsStayIntact() {
        val spec = TerminalLaunchSpec(prefix = listOf("env", "-i"), shell = "/bin/bash", interactiveArgs = listOf("-li"))
        assertEquals(listOf("-l", "-s"), TerminalPipeShell.args(spec.interactiveArgs))
        assertTrue(spec.interactiveCommand().last() == "-li")
        assertTrue(spec.pipeCommand().contains("TERM=dumb"))
        assertEquals(listOf("--noprofile", "-s"), TerminalPipeShell.args(listOf("--noprofile", "--interactive", "-i")))
    }

    @Test fun realBashPipeHasNoJobControlWarningsAndReportsItsActualExitStatus() {
        val result = run("printf 'DROIDE_ECHO_OK\\n'", "false", "printf 'PREVIOUS_EXIT=%s\\n' \"\$?\"")
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("DROIDE_ECHO_OK"))
        assertFalse(result.output.contains("no job control"))
        assertFalse(result.output.contains("cannot set terminal process group"))
        assertTrue(result.output.contains("$token:ready:1"))
        assertTrue(result.output.contains("PREVIOUS_EXIT=1"))
    }

    @Test fun multilineCommandsExportsAndWorkingDirectoryRemainInOnePersistentShell() {
        val dir = Files.createTempDirectory("pipe-cwd-").toFile()
        try {
            val result = run("cd '${dir.path}'; export DROIDE_PIPE_VALUE='still present'", "printf '%s|%s\\n' \"\$PWD\" \"\$DROIDE_PIPE_VALUE\"",
                "cat <<'DROIDE_EOF'\nquote ' \" ; \\ data\nDROIDE_EOF")
            assertTrue(result.output.contains(dir.path + "|still present"))
            assertTrue(result.output.contains("quote ' \" ; \\ data"))
            assertTrue(result.output.contains("$token:ready:0"))
        } finally { dir.delete() }
    }

    @Test fun stdinReadsCannotStealAnotherCommandOrPrivateProtocolFrame() {
        val result = run("read value", "printf 'FOLLOWING_COMMAND_OK\\n'")
        assertTrue(result.output.contains("$token:ready:1"))
        assertTrue(result.output.contains("FOLLOWING_COMMAND_OK"))
        assertFalse(result.output.contains("no job control"))
    }

    @Test fun quotedLargeInputHasBoundedTransportAndNativeNulValidationIsRetained() {
        assertTrue(TerminalPipeShell.command("'".repeat(64_000)).length <= TerminalPipeShell.MAX_FRAME_CHARS)
        assertTrue(runCatching { TerminalPipeShell.command("x".repeat(64_001)) }.isFailure)
        assertTrue(runCatching { TerminalPipeShell.command("echo\u0000bad") }.isFailure)
    }
}
