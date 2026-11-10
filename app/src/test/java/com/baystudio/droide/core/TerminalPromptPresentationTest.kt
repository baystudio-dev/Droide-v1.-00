package com.baystudio.droide.core

import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class TerminalPromptPresentationTest {
    private fun title(path: String) = TerminalPromptPresentation.TITLE_PREFIX +
        Base64.getEncoder().encodeToString(path.toByteArray(Charsets.UTF_8))

    @Test fun directoryMetadataPreservesUnicodeSpacesAndControlBytes() {
        val path = "/workspace/日本語 😀; folder\nwith\u0007bell"
        assertEquals(path, TerminalPromptPresentation.directoryFromTitle(title(path)))
    }

    @Test fun rejectsMalformedRelativeAndNulDirectories() {
        for (value in listOf(null, "normal title", TerminalPromptPresentation.TITLE_PREFIX + "%%%", title("relative/path"), title("/bad\u0000path"))) {
            assertNull(TerminalPromptPresentation.directoryFromTitle(value))
        }
    }

    @Test fun rejectsInvalidUtf8AndOversizeMetadata() {
        assertNull(TerminalPromptPresentation.directoryFromTitle(TerminalPromptPresentation.TITLE_PREFIX +
            Base64.getEncoder().encodeToString(byteArrayOf(47, 0xc0.toByte(), 0xaf.toByte()))))
        assertNull(TerminalPromptPresentation.directoryFromTitle(title("/" + "x".repeat(4_096))))
        assertNull(TerminalPromptPresentation.directoryFromTitle(TerminalPromptPresentation.TITLE_PREFIX + "A".repeat(9_000)))
    }

    @Test fun metadataAtTheByteLimitStillWorks() {
        val path = "/" + "x".repeat(4_095)
        assertEquals(path, TerminalPromptPresentation.directoryFromTitle(title(path)))
    }

    @Test fun headerEscapesControlsAndBidiWithoutDroppingVisibleUnicode() {
        assertEquals("/日本語\\n\\t\\u001b\\u202e", TerminalPromptPresentation.displayDirectory("/日本語\n\t\u001b\u202e"))
    }

    @Test fun fragmentedMetadataDoesNotLeakIntoPipeOutput() {
        val paths = mutableListOf<String>()
        val stream = TerminalDirectoryStream(paths::add)
        val text = "before\u001b]2;${title("/one folder/日本語")}\u0007\$ after\n"
        assertEquals("before\$ after\n", text.map { stream.consume(it.toString()) }.joinToString(""))
        assertEquals(listOf("/one folder/日本語"), paths)
    }

    @Test fun supportsStTerminatorAndLeavesAnsiRenderingToItsExistingPolicy() {
        val paths = mutableListOf<String>()
        val stream = TerminalDirectoryStream(paths::add)
        assertEquals("\u001b[32m\$ ", stream.consume("\u001b]0;${title("/tmp")}\u001b\\\u001b[32m\$ "))
        assertEquals(listOf("/tmp"), paths)
    }

    @Test fun oversizedOscCannotGrowMemoryOrOverwriteDirectory() {
        val paths = mutableListOf<String>()
        val stream = TerminalDirectoryStream(paths::add)
        assertEquals("OK", stream.consume("\u001b]2;" + "x".repeat(100_000) + "\u0007OK"))
        assertTrue(paths.isEmpty())
        assertEquals("\$ ", stream.consume("\u001b]2;${title("/next")}\u0007\$ "))
        assertEquals(listOf("/next"), paths)
    }

    @Test fun nonDirectoryTitlesAreNotTreatedAsPaths() {
        val paths = mutableListOf<String>()
        val stream = TerminalDirectoryStream(paths::add)
        assertEquals("result", stream.consume("\u001b]2;vim\u0007result"))
        assertTrue(paths.isEmpty())
    }

    @Test fun androidProfileDoesNotReplaceUserRcOrFollowSymlinks() {
        val root = Files.createTempDirectory("droide-prompt-profile").toFile()
        try {
            val userRc = root.resolve(".mkshrc").apply { writeText("user-alias-data") }
            val profile = root.resolve("presentation")
            val env = TerminalPromptPresentation.androidEnvironment(profile)
            assertEquals("user-alias-data", userRc.readText())
            assertEquals(TerminalPromptPresentation.COMPACT_PROMPT, env["PS1"])
            val output = java.io.File(requireNotNull(env["ENV"]))
            assertEquals(TerminalPromptPresentation.androidProfile(), output.readText())
            output.delete()
            Files.createSymbolicLink(output.toPath(), userRc.toPath())
            try { TerminalPromptPresentation.androidEnvironment(profile); fail("Expected symlink refusal") }
            catch (_: IllegalStateException) { }
            assertEquals("user-alias-data", userRc.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun generatedProfilesAreValidShellSourceWithNestedPrintfQuoting() {
        val root = Files.createTempDirectory("droide-prompt-syntax").toFile()
        try {
            for ((name, text) in mapOf("android" to TerminalPromptPresentation.androidProfile(), "ash" to TerminalPromptPresentation.ashProfile())) {
                val file = root.resolve("$name.sh").apply { writeText(text) }
                val child = ProcessBuilder("/bin/sh", "-n", file.absolutePath).redirectErrorStream(true).start()
                val diagnostic = child.inputStream.bufferedReader().readText()
                assertEquals(diagnostic, 0, child.waitFor())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun actualInteractiveBashLoadsUserAliasesPathAndExportsWithoutEditingUserRc() {
        val root = Files.createTempDirectory("droide-bash-user-rc-").toFile()
        try {
            val home = root.resolve("root").apply { mkdirs() }
            val rc = home.resolve(".bashrc").apply { writeText(
                "alias audit_alias='printf alias-loaded'\nexport AUDIT_SETTING=user-value\nexport PATH=\"\$HOME/custom/bin:\$PATH\"\n") }
            val original = rc.readText()
            val args = hostBashArgs(root)
            val output = runBash(root, args + listOf("-c", "audit_alias; printf '|%s|%s' \"\$AUDIT_SETTING\" \"\${PATH%%:*}\""))
            assertEquals("alias-loaded|user-value|${home.path}/custom/bin", output)
            assertEquals(original, rc.readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun rcProfilePreservesUserPromptArrayExitStatusAndPreExecutionHook() {
        val root = Files.createTempDirectory("droide-bash-user-hook-").toFile()
        try {
            root.resolve("root").mkdirs()
            root.resolve("root/.bashrc").writeText("PROMPT_COMMAND=('USER_SEEN_STATUS=\$?' 'USER_SECOND=yes')\nPS0=user-preexec\n")
            val script = "false; eval \"\${PROMPT_COMMAND[0]}\"; eval \"\${PROMPT_COMMAND[1]}\"; eval \"\${PROMPT_COMMAND[2]}\"; " +
                "printf '|CHECK:%s:%s:%s:%s' \"\$USER_SEEN_STATUS\" \"\$USER_SECOND\" \"\${#PROMPT_COMMAND[@]}\" \"\$PS0\""
            val output = runBash(root, hostBashArgs(root) + listOf("-c", script))
            assertTrue(output, output.contains("|CHECK:1:yes:3:"))
            assertTrue(output, output.endsWith("user-preexec"))
            assertTrue(output, output.contains(TerminalPromptPresentation.TITLE_PREFIX))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun sameLaunchSpecKeepsProtocolAndPipeOutputFreeOfUserRcSideEffects() {
        val root = Files.createTempDirectory("droide-bash-protocol-rc-").toFile()
        try {
            root.resolve("root").mkdirs()
            val marker = root.resolve("rc-was-loaded")
            root.resolve("root/.bashrc").writeText("echo USER_RC_NOISE\ntouch '${marker.path}'\n")
            val spec = TerminalLaunchSpec(shell = "/bin/bash", interactiveArgs = hostBashArgs(root),
                interactiveEnvironment = TerminalPromptPresentation.bashStartupEnvironment())
            assertEquals("protocol-clean", runBash(root, spec.shellCommand("printf protocol-clean").drop(1)))
            assertFalse(marker.exists())
            assertEquals("pipe-clean", runBash(root, spec.pipeCommand().drop(1), "printf pipe-clean\n"))
            assertFalse(marker.exists())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun hostBashArgs(rootfs: java.io.File): List<String> = TerminalPromptPresentation.bashInteractiveArgs(rootfs).map {
        if (it == "/opt/droide/terminal/bashrc-v1.sh") rootfs.resolve("opt/droide/terminal/bashrc-v1.sh").path else it
    }

    private fun runBash(root: java.io.File, args: List<String>, input: String? = null): String {
        val child = ProcessBuilder(listOf("/bin/bash") + args).apply {
            environment()["HOME"] = root.resolve("root").path
            environment().remove("BASH_ENV")
            environment().remove("PROMPT_COMMAND")
            environment().remove("PS0")
            environment().putAll(TerminalPromptPresentation.bashStartupEnvironment())
        }.start()
        try {
            child.outputStream.bufferedWriter().use { if (input != null) it.write(input) }
            assertTrue("Bash startup fixture timed out", child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            val diagnostic = child.errorStream.bufferedReader().readText()
            assertEquals(diagnostic, 0, child.exitValue())
            return child.inputStream.bufferedReader().readText()
        } finally { child.destroyForcibly() }
    }
}

/** Exports the production hook for real host PTY checks, without duplicating its shell text. */
object TerminalPromptHostProbe {
    @JvmStatic fun main(args: Array<String>) {
        val map = when (args.single()) {
            "bash" -> TerminalPromptPresentation.bashEnvironment()
            "android" -> mapOf("PROFILE" to TerminalPromptPresentation.androidProfile())
            "ash" -> mapOf("PROFILE" to TerminalPromptPresentation.ashProfile())
            else -> error("Unknown prompt backend")
        }
        map.forEach { (key, value) -> println(key + "=" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))) }
    }
}
