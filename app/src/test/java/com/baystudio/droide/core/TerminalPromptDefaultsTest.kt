package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Actual generated rc + Bash, with unmodified rc fixtures from the packaged Ubuntu rootfs. */
class TerminalPromptDefaultsTest {
    @Test fun packagedRootDefaultsBecomeLiteralDollarForEveryTerminalFlavor() {
        for (term in listOf("dumb", "xterm-256color", "xterm-color", "rxvt-unicode")) {
            withGuest { root ->
                rc(root).writeText(resource("packaged-root.bashrc"))
                assertEquals(term, "\$ ", prompt(root, term))
            }
        }
    }

    @Test fun packagedSkeletonDefaultsBecomeLiteralDollarIncludingColor() {
        for (term in listOf("dumb", "xterm-256color", "xterm-color", "rxvt-unicode")) {
            withGuest { root ->
                rc(root).writeText(resource("packaged-skel.bashrc"))
                assertEquals(term, "\$ ", prompt(root, term))
            }
        }
    }

    @Test fun chrootTitleCharactersRemainLiteralAndNeverExecute() = withGuest { root ->
        rc(root).writeText(resource("packaged-root.bashrc"))
        val marker = root.resolve("must-not-execute")
        val chroot = "日本語 * ? [abc] ; \$(touch ${marker.path}) ' \""
        assertEquals("\$ ", prompt(root, "xterm-256color", mapOf("debian_chroot" to chroot)))
        assertFalse(marker.exists())
    }

    @Test fun customPromptValuesIncludingEmptyAndStockLikeSuffixRemainUnchanged() {
        val plain = "\${debian_chroot:+(\$debian_chroot)}\\u@\\h:\\w\\\$ "
        for (custom in listOf("", "mine> ", "日本語 😀> ", "\\w > ", plain + "custom", "\$(printf custom)> ")) {
            withGuest { root ->
                rc(root).writeText("PS1=${literal(custom)}\n")
                assertEquals(custom, prompt(root))
            }
        }
    }

    @Test fun chrootPatternCharactersCannotMatchADifferentCustomTitle() = withGuest { root ->
        val custom = "\\[\\e]0;(custom)\\u@\\h: \\w\\a\\]" +
            "\${debian_chroot:+(\$debian_chroot)}\\u@\\h:\\w\\\$ "
        rc(root).writeText("PS1=${literal(custom)}\n")
        assertEquals(custom, prompt(root, env = mapOf("debian_chroot" to "*")))
    }

    @Test fun stockPromptExplicitlyMadeReadonlyIsPreservedWithoutStartupError() = withGuest { root ->
        rc(root).writeText(resource("packaged-root.bashrc") + "\nreadonly PS1\n")
        val expected = "\${debian_chroot:+(\$debian_chroot)}\\u@\\h:\\w\\\$ "
        assertEquals(expected, prompt(root, "dumb"))
    }

    @Test fun deliberatelyUnsetPromptWithNounsetRemainsUnset() = withGuest { root ->
        rc(root).writeText("unset PS1\nset -u\n")
        assertEquals("unset", run(root, "printf '%s' \"\${PS1+set}\"; printf unset").output)
    }

    @Test fun customAliasFileCanOverrideStockPromptAndKeepsItsSideEffects() = withGuest { root ->
        rc(root).writeText(resource("packaged-root.bashrc"))
        root.resolve("root/.bash_aliases").writeText("PS1='chosen> '\nalias chosen='printf alias-ok'\nexport CHOSEN_SETTING=retained\n")
        assertEquals("chosen> ", prompt(root))
        assertEquals("alias-ok|retained", run(root, "chosen; printf '|%s' \"\$CHOSEN_SETTING\"").output)
    }

    @Test fun missingUserRcUsesCompactSystemDefaultWithoutCreatingUserRc() = withGuest { root ->
        assertFalse(rc(root).exists())
        assertEquals("\$ ", prompt(root))
        assertFalse(rc(root).exists())
    }

    @Test fun existingGuestProfileUpgradesOnceWithoutEditingUserRc() = withGuest { root ->
        val user = rc(root).apply { writeText(resource("packaged-root.bashrc")) }
        val original = user.readBytes()
        val profile = root.resolve("opt/droide/terminal/bashrc-v1.sh")
        profile.parentFile.mkdirs()
        profile.writeText("# previous Droide profile\nPS1='old> '\n")
        TerminalPromptPresentation.bashInteractiveArgs(root)
        assertEquals(TerminalPromptPresentation.bashProfile(), profile.readText())
        assertTrue(profile.setLastModified(1_600_000_000_000L))
        TerminalPromptPresentation.bashInteractiveArgs(root)
        assertEquals(1_600_000_000_000L, profile.lastModified())
        assertEquals("\$ ", prompt(root))
        assertArrayEquals(original, user.readBytes())
    }

    @Test fun defaultStaysLiteralAfterDirectoryChangesWithoutChangingIdentityOrShellOptions() = withGuest { root ->
        rc(root).writeText(resource("packaged-root.bashrc"))
        val output = run(root, "before=\$UID; cd /; printf '%s|%s|%s|%s' \"\$PS1\" \"\$before\" \"\$UID\" \"\$-\"").output
        val fields = output.split('|')
        assertEquals("\$ ", fields[0])
        assertEquals(fields[1], fields[2])
        assertTrue(fields[3].contains('i'))
    }

    @Test fun userPromptHookCanStillChangePromptAndReceivesPreviousExitStatus() = withGuest { root ->
        rc(root).writeText(resource("packaged-root.bashrc") +
            "\nPROMPT_COMMAND=('USER_STATUS=\$?; PS1=\"hook> \"')\nPS0='user-preexec'\n")
        val output = run(root, "false; eval \"\${PROMPT_COMMAND[0]}\"; eval \"\${PROMPT_COMMAND[1]}\"; " +
            "printf '|CHECK:%s:%s:%s' \"\$USER_STATUS\" \"\$PS1\" \"\$PS0\"").output
        assertTrue(output, output.contains("|CHECK:1:hook> :"))
        assertTrue(output, output.endsWith("user-preexec"))
    }

    @Test fun scalarPromptHookKeepsItsStatusAndCanChooseItsOwnPrompt() = withGuest { root ->
        rc(root).writeText(resource("packaged-root.bashrc") +
            "\nPROMPT_COMMAND='USER_STATUS=\$?; PS1=\"scalar> \"'\n")
        val output = run(root, "false; eval \"\${PROMPT_COMMAND[0]}\"; eval \"\${PROMPT_COMMAND[1]}\"; " +
            "printf '|CHECK:%s:%s:%s' \"\$USER_STATUS\" \"\$PS1\" \"\${#PROMPT_COMMAND[@]}\"").output
        assertTrue(output, output.endsWith("|CHECK:1:scalar> :2"))
    }

    private data class BashResult(val output: String)

    private fun prompt(root: File, term: String = "xterm-256color", env: Map<String, String> = emptyMap()): String {
        val encoded = run(root, "printf '%s' \"\${PS1-}\" | base64 | tr -d '\\r\\n'", term, env).output
        return String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }

    private fun run(root: File, command: String, term: String = "xterm-256color", extra: Map<String, String> = emptyMap()): BashResult {
        val args = TerminalPromptPresentation.bashInteractiveArgs(root).map {
            if (it == "/opt/droide/terminal/bashrc-v1.sh") root.resolve("opt/droide/terminal/bashrc-v1.sh").path else it
        }
        val out = root.resolve("stdout"); val err = root.resolve("stderr")
        val child = ProcessBuilder(listOf("/bin/bash") + args + listOf("-c", command)).apply {
            environment().clear()
            environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to root.resolve("root").path,
                "TERM" to term, "LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8"))
            environment().putAll(TerminalPromptPresentation.bashStartupEnvironment())
            environment().putAll(extra)
            redirectOutput(out); redirectError(err)
        }.start()
        try {
            child.outputStream.close()
            assertTrue("Bash fixture timed out", child.waitFor(5, TimeUnit.SECONDS))
            val diagnostic = err.readText()
            assertEquals(diagnostic, 0, child.exitValue())
            assertFalse(diagnostic, diagnostic.contains("readonly variable"))
            assertFalse(diagnostic, diagnostic.contains("unbound variable"))
            return BashResult(out.readText())
        } finally { child.destroyForcibly() }
    }

    private fun rc(root: File) = root.resolve("root/.bashrc")
    private fun literal(text: String) = "'" + text.replace("'", "'\\''") + "'"
    private fun resource(name: String): String = checkNotNull(javaClass.getResourceAsStream("/terminal-prompt-defaults/$name"))
        .bufferedReader().use { it.readText() }
    private inline fun withGuest(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-literal-prompt-").toFile()
        root.resolve("root").mkdir()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
