package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentSkillCatalogWorkflowTest {
    private suspend fun fixture(block: suspend (File, File) -> Unit) {
        val root = Files.createTempDirectory("droide-skill-workflow-").toFile()
        val skill = root.resolve(".droide/skills/alpha/SKILL.md").apply {
            parentFile.mkdirs(); writeText("---\nname: alpha\ndescription: First\n---\nInitial body")
        }
        try { block(root, skill) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun definition(root: File, permissions: PermissionEngine? = null) =
        AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root), permissions = permissions)
            .map { it.jsonObject.getValue("function").jsonObject }.firstOrNull { it["name"] == JsonPrimitive("skill") }

    @Test fun bodyIsLoadedOnExplicitToolCallAndRecheckedAfterCatalogDiscovery() = runBlocking { fixture { root, file ->
        val first = definition(root)!!.toString()
        assertTrue(first.contains("First")); assertFalse(first.contains("Initial body"))
        file.writeText(file.readText().replace("First", "Fresh").replace("Initial body", "Updated body"))
        val result = AgentTools.execute("skill", buildJsonObject { put("name", "alpha") }, agentWorkflowContext(root))
        assertTrue(result.contains("Fresh")); assertTrue(result.contains("Updated body")); assertFalse(result.contains("Initial body"))
        assertTrue(definition(root)!!.toString().contains("Fresh"))
    } }

    @Test fun skillPermissionIsCheckedEvenIfTheSkillWasPreviouslyAdvertised() = runBlocking { fixture { root, _ ->
        assertNotNull(definition(root))
        val deny = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("skill", "*", PermEffect.DENY)))
        assertNull(definition(root, deny))
        val result = AgentTools.execute("skill", buildJsonObject { put("name", "alpha") }, agentWorkflowContext(root, permissions = deny))
        assertTrue(result.startsWith("DENIED")); assertFalse(result.contains("Initial body"))
    } }

    @Test fun advertisedSkillRemovedBeforeInvocationDoesNotReturnOldInstructions() = runBlocking { fixture { root, file ->
        assertNotNull(definition(root))
        assertTrue(file.delete())
        val result = AgentTools.execute("skill", buildJsonObject { put("name", "alpha") }, agentWorkflowContext(root))
        assertTrue(result.startsWith("ERROR:")); assertFalse(result.contains("Initial body"))
    } }

    @Test fun discoveryDoesNotRemoveOtherToolsOrChangeSchemaWithLargeSkillBodies() = runBlocking { fixture { root, file ->
        fun definitions() = AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root))
        val before = definitions()
        file.appendText("x".repeat(150_000))
        assertEquals(before, definitions())
        val result = AgentTools.execute("skill", buildJsonObject { put("name", "alpha") }, agentWorkflowContext(root))
        assertTrue(result.startsWith("# alpha: First\nSource: workspace\n"))
        assertTrue(result.length < 10_100)
    } }
}
