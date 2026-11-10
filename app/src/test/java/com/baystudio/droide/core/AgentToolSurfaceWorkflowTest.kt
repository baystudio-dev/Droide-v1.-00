package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentToolSurfaceWorkflowTest {
    @Test fun commandSessionsAreVisibleOnlyWhenManagerExistsAndModePermitsExecution() {
        val root = Files.createTempDirectory("droide-command-surface-").toFile()
        try {
            fun names(mode: AgentMode, available: Boolean, perms: PermissionEngine? = null) = AgentTools.definitionsWithCustom(
                SkillManager(root), CustomToolManager(root), mode = mode, permissions = perms,
                commandSessionsAvailable = available, visionAvailable = true).map {
                    it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
                }.toSet()
            assertFalse("exec_command" in names(AgentMode.BUILD, false))
            assertTrue(names(AgentMode.BUILD, true).containsAll(listOf("exec_command", "write_stdin", "view_image", "project_instructions")))
            listOf(AgentMode.PLAN, AgentMode.REVIEW, AgentMode.EXPLORE).forEach {
                assertFalse("exec_command" in names(it, true)); assertFalse("write_stdin" in names(it, true))
                assertTrue("project_instructions" in names(it, true))
            }
            val deny = PermissionEngine(listOf(PermRule("shell", "*", PermEffect.DENY), PermRule("read", "*", PermEffect.ALLOW)))
            assertFalse("exec_command" in names(AgentMode.BUILD, true, deny))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun newToolArgumentsAreTypedRequiredAndSearchAliasesExposeTheSameOptions() {
        val functions = AgentToolCatalog.definitions.map { it.jsonObject.getValue("function").jsonObject }
            .associateBy { it.getValue("name").jsonPrimitive.content }
        fun schema(name: String) = functions.getValue(name).getValue("parameters").jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("cmd"))), schema("exec_command")["required"])
        assertEquals(JsonArray(listOf(JsonPrimitive("session_id"))), schema("write_stdin")["required"])
        assertEquals(JsonArray(listOf(JsonPrimitive("path"))), schema("view_image")["required"])
        assertEquals(schema("search_files").getValue("properties").jsonObject.keys, schema("grep").getValue("properties").jsonObject.keys)
        assertEquals(schema("read_file").getValue("properties").jsonObject.keys, schema("read").getValue("properties").jsonObject.keys)
        assertEquals(JsonPrimitive("integer"), schema("read_file").getValue("properties").jsonObject.getValue("offset").jsonObject["type"])
        assertEquals(JsonPrimitive("boolean"), schema("write_stdin").getValue("properties").jsonObject.getValue("interrupt").jsonObject["type"])
        assertEquals("shell", AgentTools.actionOf("write_stdin")); assertEquals("read", AgentTools.actionOf("view_image"))
        val operations = schema("mcp").getValue("properties").jsonObject.getValue("operation").jsonObject.getValue("enum").jsonArray
        assertTrue(operations.containsAll(listOf("list_resources", "read_resource", "list_resource_templates", "list_prompts", "get_prompt").map(::JsonPrimitive)))
    }
}
