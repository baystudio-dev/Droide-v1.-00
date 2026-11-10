package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentWorkspaceCommandPolicyTest {
    private fun fixture(block: suspend (File, File) -> Unit) = runBlocking {
        val base = Files.createTempDirectory("agent-root-policy-").toFile()
        try {
            val root = File(base, "project ' \$; \nroot").apply { mkdirs() }
            block(root, File(base, "human").apply { mkdirs() })
        } finally { PathSecurity.deleteTreeNoFollow(base) }
    }

    @Test fun quotedRootListsPipelinesAndTrailingCommentsKeepShellMeaning() = fixture { root, human ->
        val command = "printf 'first\\n' > one.txt; printf 'second\\n' | tr a-z A-Z > two.txt\n" +
            "printf '%s' \"\$DROIDE_TEST_MARKER\"; exit 23 # trailing comment"
        val wrapped = AgentWorkspaceCommandPolicy.shell(root, command)
        assertTrue(wrapped.endsWith(command))
        val result = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", wrapped), human,
            environment = mapOf("DROIDE_TEST_MARKER" to "same-backend"), timeoutMs = 5_000)
        assertEquals(23, result.exitCode)
        assertEquals("same-backend", result.output)
        assertEquals("first\n", File(root, "one.txt").readText())
        assertEquals("SECOND\n", File(root, "two.txt").readText())
        assertFalse(File(human, "one.txt").exists())
        assertFalse(File(human, "two.txt").exists())
    }

    @Test fun heredocAndExplicitCommandLocalCdRemainIntact() = fixture { root, human ->
        File(root, "child").mkdirs()
        val command = """cat > source.txt <<'END'
            |literal ${'$'}HOME ; ' " text
            |END
            |cd child
            |printf 'nested' > target.txt
        """.trimMargin()
        val result = LocalProcessSupervisor.capture(
            listOf("/bin/sh", "-c", AgentWorkspaceCommandPolicy.shell(root, command)), human, timeoutMs = 5_000)
        assertEquals(0, result.exitCode)
        assertEquals("literal \$HOME ; ' \" text\n", File(root, "source.txt").readText())
        assertEquals("nested", File(root, "child/target.txt").readText())
        assertFalse(File(human, "source.txt").exists())
    }

    @Test fun rootRemovedAfterAdmissionCannotRunLaterListCommandsInHumanCwd() = fixture { root, human ->
        val command = AgentWorkspaceCommandPolicy.shell(root, "printf first > first.txt; printf second > second.txt")
        assertTrue(root.delete())
        val result = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", command), human, timeoutMs = 5_000)
        assertNotEquals(0, result.exitCode)
        assertFalse(File(human, "first.txt").exists())
        assertFalse(File(human, "second.txt").exists())
    }

    @Test fun absentRootAndSymlinkRootAreRejectedBeforeLaunch() = fixture { root, human ->
        assertThrows(IllegalArgumentException::class.java) {
            AgentWorkspaceCommandPolicy.shell(File(root, "missing"), "touch should-not-run")
        }
        val link = File(human, "linked")
        Files.createSymbolicLink(link.toPath(), root.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            AgentWorkspaceCommandPolicy.shell(link, "touch should-not-run")
        }
        assertFalse(File(root, "should-not-run").exists())
    }
}
