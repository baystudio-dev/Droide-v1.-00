package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CustomToolMetadataTest {
    @Test fun normalFirstLineFormatsKeepExistingDescriptions() = withRoot { root ->
        val script = script(root)
        val manager = CustomToolManager(root)
        for (ending in listOf("\n", "\r", "\r\n", "")) {
            script.writeText("# desc:  hello world  $ending" + if (ending.isEmpty()) "" else "not metadata")
            assertEquals("hello world", manager.discover().single().description)
        }
        script.writeText("# desc:   \ntrue")
        assertEquals("", manager.discover().single().description)
        for (line in listOf("#!/bin/sh", "", "\uFEFF# desc: hidden", " # desc: hidden")) {
            script.writeText("$line\ntrue")
            assertEquals("Custom tool demo", manager.discover().single().description)
        }
    }

    @Test fun unicodeAcrossReadBoundariesIsDecodedAsOneLine() = withRoot { root ->
        val text = "# desc:" + " ".repeat(503) + "🙂 日本語 café"
        script(root).writeText("$text\ntrue")
        assertEquals("🙂 日本語 café", CustomToolManager(root).discover().single().description)
    }

    @Test fun exactByteLimitAcceptsEofAndLineTerminators() = withRoot { root ->
        val file = script(root)
        val first = "# desc:" + " ".repeat(8_192 - 7 - 2) + "ok"
        assertEquals(8_192, first.toByteArray().size)
        for (end in listOf("", "\nbody", "\rbody", "\r\nbody")) {
            file.writeText(first + end)
            assertEquals("ok", CustomToolManager(root).discover().single().description)
        }
    }

    @Test fun oversizedFirstLineReportsOmissionAndKeepsTheToolCallable() = withRoot { root ->
        val file = script(root)
        for (body in listOf("a".repeat(8_186), "é".repeat(4_093), "a".repeat(1_900_000))) {
            file.writeText("# desc:$body\ntrue")
            val tool = CustomToolManager(root).discover().single()
            assertTrue(tool.description, tool.description.startsWith("[Description omitted:"))
            assertTrue(tool.description.contains("8192 bytes"))
            assertEquals("demo", tool.name)
            assertEquals(listOf("sh", ".droide/tools/demo.sh", "value"), CustomToolManager(root).argv("demo", "value"))
        }
    }

    @Test fun descriptionLimitNeverSplitsUnicodePair() = withRoot { root ->
        script(root).writeText("# desc: " + "a".repeat(299) + "🙂tail\ntrue")
        val result = CustomToolManager(root).discover().single().description
        assertEquals("a".repeat(299), result)
        assertFalse(result.last().isHighSurrogate())
    }

    @Test fun longNonMetadataLineKeepsGenericDescription() = withRoot { root ->
        script(root).writeText("x".repeat(1_900_000))
        assertEquals("Custom tool demo", CustomToolManager(root).discover().single().description)
    }

    @Test fun discoverySeesEditsDeletionAndRecreation() = withRoot { root ->
        val file = script(root)
        val manager = CustomToolManager(root)
        file.writeText("# desc: before\ntrue")
        assertEquals("before", manager.discover().single().description)
        file.writeText("# desc: after\ntrue")
        assertEquals("after", manager.discover().single().description)
        assertTrue(file.delete()); assertTrue(manager.discover().isEmpty())
        file.writeText("# desc: restored\ntrue")
        assertEquals("restored", manager.discover().single().description)
    }

    @Test fun scriptAdmissionKeepsExtensionsSizeAndDirectoryRules() = withRoot { root ->
        val dir = script(root).parentFile
        assertTrue(File(dir, "demo.sh").delete())
        File(dir, "empty.py").writeBytes(byteArrayOf())
        File(dir, "directory.js").mkdir()
        File(dir, "unsupported.txt").writeText("true")
        File(dir, "tooLarge.sh").writeBytes(ByteArray(2 * 1024 * 1024 + 1) { 120 })
        File(dir, "atLimit.sh").writeBytes(ByteArray(2 * 1024 * 1024) { 120 })
        assertEquals(listOf("atLimit"), CustomToolManager(root).discover().map { it.name })
    }

    @Test fun stableSymlinkIsNotDiscovered() = withRoot { root ->
        val real = script(root)
        Files.createSymbolicLink(File(real.parentFile, "linked.sh").toPath(), real.toPath())
        assertEquals(listOf("demo"), CustomToolManager(root).discover().map { it.name })
    }

    @Test fun nameNormalizationDeduplicationAndFiftyFileLimitRemainCompatible() = withRoot { root ->
        val dir = script(root).parentFile
        File(dir, "hello world.py").writeText("pass")
        File(dir, "hello_world.js").writeText("true")
        repeat(60) { File(dir, "tool$it.sh").writeText("true") }
        val expected = dir.listFiles()!!.take(50).map {
            it.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_-]"), "_").take(56).ifBlank { "tool" }
        }.distinct()
        assertEquals(expected, CustomToolManager(root).discover().map { it.name })
    }

    @Test fun argvPreservesInterpreterLiteralArgumentsAndInputLimit() = withRoot { root ->
        val dir = script(root).parentFile
        val input = "'; $() ; ".repeat(1_000)
        for ((extension, interpreter) in listOf("sh" to "sh", "py" to "python3", "js" to "node")) {
            File(dir, "run_$extension.$extension").writeText("# desc: tool\ntrue")
            assertEquals(listOf(interpreter, ".droide/tools/run_$extension.$extension", input.take(8_000)),
                CustomToolManager(root).argv("run_$extension", input))
        }
        assertNull(CustomToolManager(root).argv("absent", ""))
    }

    @Test fun fingerprintIncludesEntireScriptAndRejectsChangedTailAfterApproval() = withRoot { root -> runBlocking {
        val file = script(root).apply { writeText("# desc: " + "x".repeat(20_000) + "\nBODY\n") }
        val manager = CustomToolManager(root)
        val approved = requireNotNull(manager.permissionFingerprint("demo"))
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.canonicalPath.toByteArray()); digest.update(0.toByte()); digest.update(file.readBytes())
        assertEquals(digest.digest().joinToString("") { "%02x".format(it) }, approved)
        file.appendText("CHANGED_AFTER_METADATA_LIMIT")
        assertNotEquals(approved, manager.permissionFingerprint("demo"))
        val terminal = RecordingTerminal()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.execute("demo", "input", terminal, approved) }
        }
        assertTrue(terminal.calls.isEmpty())
    } }

    @Test fun approvedToolWithOversizedMetadataUsesTheApprovedTerminalWithoutImplicitDeadline() = withRoot { root -> runBlocking {
        script(root).writeText("# desc:" + "x".repeat(20_000) + "\ntrue")
        val terminal = RecordingTerminal()
        val result = AgentTools.execute("custom_demo", buildJsonObject { put("input", "literal ; argument") },
            agentWorkflowContext(root, terminal = terminal))
        assertEquals("exit=0\nrecorded", result)
        assertEquals(listOf(listOf("sh", ".droide/tools/demo.sh", "literal ; argument")), terminal.calls)
        assertEquals(listOf(0L), terminal.deadlines)
    } }

    @Test fun bothPermissionGatesStillPreventExecution() = withRoot { root -> runBlocking {
        script(root).writeText("# desc:" + "x".repeat(20_000) + "\ntrue")
        for (gate in listOf("shell_escape", "shell")) {
            val terminal = RecordingTerminal()
            val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule(gate, "*", PermEffect.DENY)))
            val result = AgentTools.execute("custom_demo", buildJsonObject { put("input", "input") },
                agentWorkflowContext(root, terminal = terminal, permissions = permissions))
            assertTrue(result, result.startsWith("DENIED"))
            assertTrue(terminal.calls.isEmpty())
        }
    } }

    private class RecordingTerminal : ITerminalSession by AgentWorkflowTerminal() {
        val calls = mutableListOf<List<String>>()
        val deadlines = mutableListOf<Long>()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
            calls += argv; deadlines += timeoutMs
            return ExecResult(0, "recorded", false)
        }
    }
    private fun script(root: File) = File(root, ".droide/tools/demo.sh").apply { parentFile.mkdirs(); writeText("true") }
    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-custom-metadata-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
