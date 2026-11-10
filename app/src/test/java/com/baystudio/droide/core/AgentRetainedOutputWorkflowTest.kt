package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises production dispatch/read permissions, not a stand-in reader. */
class AgentRetainedOutputWorkflowTest {
    @Test fun readAndAliasResolveOwnedOutputWithoutWorkspaceFile() = runBlocking {
        val root = Files.createTempDirectory("retained-workflow-").toFile()
        val store = AgentToolOutputStore.forWorkspace(root)
        val ref = store.retain("test-session", "original\n" + "x".repeat(9000))
        for (tool in listOf("read_file", "read")) {
            val output = AgentTools.execute(tool, buildJsonObject { put("path", ref.reference) }, agentWorkflowContext(root))
            assertTrue(output, output.startsWith("[DROIDE_READ_RESULT returned=true]"))
            assertTrue(output.contains("next_offset=8000")); assertTrue(output.contains("original\n"))
        }
        val other = store.retain("another-session", "hidden")
        val denied = AgentTools.execute("read_file", buildJsonObject { put("path", other.reference) }, agentWorkflowContext(root))
        assertTrue(denied, denied.startsWith("ERROR")); assertFalse(denied.contains("hidden"))
        assertFalse(root.resolve("droide-tool-output:").exists())
    }
    @Test fun retainedReadCannotBypassReadDenyOrPagingValidation() = runBlocking {
        val root = Files.createTempDirectory("retained-permissions-").toFile()
        val ref = AgentToolOutputStore.forWorkspace(root).retain("test-session", "not disclosed")
        val denied = AgentTools.execute("read_file", buildJsonObject { put("path", ref.reference) },
            agentWorkflowContext(root, permissions = PermissionEngine(listOf(PermRule("read", "*", PermEffect.DENY)))))
        assertTrue(denied, denied.startsWith("DENIED")); assertFalse(denied.contains("not disclosed"))
        val invalid = AgentTools.execute("read_file", buildJsonObject {
            put("path", ref.reference); put("start", 1); put("offset", 0)
        }, agentWorkflowContext(root))
        assertTrue(invalid, invalid.startsWith("ERROR"))
    }
}
