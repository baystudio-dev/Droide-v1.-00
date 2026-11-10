package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TerminalCwdReadinessTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private fun marker(event: String) = TerminalCommandActivityTracker.TITLE_PREFIX + token + ":" + event

    @Test fun unavailablePromptNeverClaimsReadyButUserCanRecoverWithCd() {
        val tracker = TerminalCommandActivityTracker(token)
        tracker.consumeTitle(marker("cwd-unavailable:0"))
        assertEquals(TerminalCommandPhase.CWD_UNAVAILABLE, tracker.state.value.phase)
        assertTrue(tracker.state.value.displayText().contains("Working directory unavailable"))
        assertTrue(tracker.state.value.displayText().contains("cd"))
        tracker.submitted()
        assertEquals(TerminalCommandPhase.AWAITING_RESPONSE, tracker.state.value.phase)
        tracker.consumeTitle(marker("running"))
        tracker.consumeTitle(marker("ready:0"))
        assertEquals(TerminalCommandPhase.READY, tracker.state.value.phase)
        assertEquals(0, tracker.state.value.lastExitCode)
    }

    @Test fun foreignAndMalformedUnavailableTitlesAreIgnored() {
        val tracker = TerminalCommandActivityTracker(token)
        listOf(marker("cwd-unavailable:"), marker("cwd-unavailable:999"),
            marker("cwd-unavailable:0:bad"), marker("cwd-unavailable:0").replace(token, "a".repeat(32)))
            .forEach { tracker.consumeTitle(it) }
        assertEquals(TerminalCommandPhase.STARTING, tracker.state.value.phase)
    }

    @Test fun lateUnavailableTitleCannotReviveClosedTerminalAndNewLaunchResetsState() {
        val tracker = TerminalCommandActivityTracker(token)
        tracker.closed(1)
        tracker.consumeTitle(marker("cwd-unavailable:0"))
        assertEquals(TerminalCommandPhase.CLOSED, tracker.state.value.phase)
        tracker.resetForLaunch()
        tracker.consumeTitle(marker("ready:0"))
        assertEquals(TerminalCommandPhase.READY, tracker.state.value.phase)
    }

    @Test fun physicalDirectoryIsReportedInsteadOfUnverifiedPwd() = withRoot { root ->
        val cwd = root.resolve("actual").apply { mkdir() }
        val text = runShell(cwd, "export PWD=/not/the/real/directory; " + TerminalPromptPresentation.activityDirectoryCommand)
        val paths = mutableListOf<String>()
        TerminalDirectoryStream(paths::add).consume(text)
        assertEquals(listOf(cwd.canonicalPath), paths)
        assertTrue(text.contains(marker("ready:0")))
    }

    @Test fun pathEndingInNewlinesIsNotTruncatedAndCannotInjectTitles() = withRoot { root ->
        val cwd = root.resolve("日本語;\u0007\n\n").apply { mkdir() }
        val text = runShell(cwd, TerminalPromptPresentation.activityDirectoryCommand)
        val paths = mutableListOf<String>()
        TerminalDirectoryStream(paths::add).consume(text)
        assertEquals(listOf(cwd.canonicalPath), paths)
    }

    @Test fun removedCwdReportsUnavailableWithoutRecreatingOrChangingDirectory() = withRoot { root ->
        val cwd = root.resolve("deleted").apply { mkdir() }
        val text = runShell(cwd, "rmdir -- '${cwd.path}'; " + TerminalPromptPresentation.activityDirectoryCommand)
        assertTrue(text, text.contains(marker("cwd-unavailable:0")))
        assertFalse(text, text.contains(marker("ready:")))
        assertFalse(text, text.contains(TerminalPromptPresentation.TITLE_PREFIX))
        assertFalse(cwd.exists())
    }

    @Test fun explicitCdAfterInvalidCwdRestoresValidPromptAndReportsExitStatus() = withRoot { root ->
        val cwd = root.resolve("deleted").apply { mkdir() }
        val text = runShell(cwd, "rmdir -- '${cwd.path}'; " + TerminalPromptPresentation.activityDirectoryCommand +
            "; cd -- '${root.path}'; false; " + TerminalPromptPresentation.activityDirectoryCommand)
        val tracker = TerminalCommandActivityTracker(token)
        val phases = mutableListOf<TerminalCommandPhase>()
        val paths = mutableListOf<String>()
        val stream = TerminalDirectoryStream(paths::add) { title -> tracker.consumeTitle(title); phases += tracker.state.value.phase }
        text.forEach { stream.consume(it.toString()) }
        assertTrue(phases.contains(TerminalCommandPhase.CWD_UNAVAILABLE))
        assertEquals(TerminalCommandPhase.READY, tracker.state.value.phase)
        assertEquals(1, tracker.state.value.lastExitCode)
        assertEquals(listOf(root.canonicalPath), paths)
    }

    @Test fun healthyHooksWorkInBashAndPosixShell() = withRoot { root ->
        for (shell in listOf("/bin/bash", "/bin/sh")) {
            val text = runShell(root, "false; " + TerminalPromptPresentation.activityDirectoryCommand, shell)
            assertTrue(text, text.contains(marker("ready:1")))
        }
    }

    private fun runShell(cwd: File, script: String, shell: String = "/bin/bash"): String {
        val child = ProcessBuilder(shell, "-c", script).directory(cwd).apply {
            environment()[TerminalCommandActivityTracker.TOKEN_ENV] = token
        }.start()
        child.outputStream.close()
        assertTrue("shell fixture timeout", child.waitFor(5, TimeUnit.SECONDS))
        val err = child.errorStream.bufferedReader().readText()
        assertEquals(err, 0, child.exitValue())
        return child.inputStream.bufferedReader().readText()
    }
    private fun withRoot(action: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-cwd-readiness").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
}
