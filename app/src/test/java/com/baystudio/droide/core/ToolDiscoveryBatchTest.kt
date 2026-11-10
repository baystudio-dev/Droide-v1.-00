package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ToolDiscoveryBatchTest {
    @Test fun missingCommandsDoNotMergeWithTheNextDetectedPathAndToolsAfter160RemainVisible() {
        val root = Files.createTempDirectory("droide discovery '").toFile()
        try {
            val tools = (1..180).map { "tool$it" }
            for (name in listOf("tool2", "tool180")) File(root, name).apply { writeText("#!/bin/sh\nexit 0\n"); check(setExecutable(true)) }
            val process = ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c", ToolDiscoveryBatch.command(tools))
                .apply { environment()["PATH"] = root.path }.start()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
            assertEquals(mapOf("tool2" to File(root, "tool2").path, "tool180" to File(root, "tool180").path),
                ToolDiscoveryBatch.parse(process.inputStream.bufferedReader().readText(), tools))
        } finally { root.deleteRecursively() }
    }
    @Test fun nonPathAndUnexpectedOutputCannotInventAnExecutable() {
        assertEquals(mapOf("node" to "/root/.local/bin/node"), ToolDiscoveryBatch.parse(
            "node\t/root/.local/bin/node\nmissing\t\nunknown\t/evil\nnode\tnode\n", listOf("node", "missing")))
    }
    @Test fun malformedCommandNamesAreRejectedBeforeAnyShellRuns() {
        assertThrows(IllegalArgumentException::class.java) { ToolDiscoveryBatch.command(listOf("node; touch marker")) }
        assertThrows(IllegalArgumentException::class.java) { ToolDiscoveryBatch.command(emptyList()) }
    }
}
