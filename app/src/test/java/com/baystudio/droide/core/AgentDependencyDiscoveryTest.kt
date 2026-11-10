package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentDependencyDiscoveryTest {
    @Test fun listingDefaultsAndExplicitPathKeepDiscoverySeparateFromAccess() = runBlocking {
        val root = fixture()
        try {
            val context = agentWorkflowContext(root)
            val defaults = AgentTools.execute("list_files", buildJsonObject {}, context)
            assertFalse(defaults.contains("node_modules"))
            assertTrue(defaults.contains("src"))
            val included = AgentTools.execute("list_files", buildJsonObject { put("include_dependencies", true) }, context)
            assertTrue(included.contains("DIR  node_modules"))
            val explicit = AgentTools.execute("list_files", buildJsonObject { put("path", "node_modules/pkg") }, context)
            assertTrue(explicit.contains("FILE node_modules/pkg/index.js"))
            val read = AgentTools.execute("read_file", buildJsonObject { put("path", "node_modules/pkg/index.js") }, context)
            assertTrue(read.contains("needle"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun globSearchAndGrepAliasHonorExplicitDependencyOptIn() = runBlocking {
        val root = fixture()
        try {
            val context = agentWorkflowContext(root)
            for (name in listOf("glob", "search_files", "grep")) {
                fun args(include: Boolean?) = buildJsonObject {
                    if (name == "glob") put("pattern", "**/*.js") else put("query", "needle")
                    include?.let { put("include_dependencies", it) }
                }
                val defaults = AgentTools.execute(name, args(null), context)
                assertTrue("$name keeps authored source", defaults.contains("src/main.js"))
                assertFalse("$name defaults exclude dependencies", defaults.contains("node_modules/pkg/index.js"))
                assertTrue("$name explicit opt-in includes dependencies",
                    AgentTools.execute(name, args(true), context).contains("node_modules/pkg/index.js"))
                assertEquals(defaults, AgentTools.execute(name, args(false), context))
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun malformedOptInIsRejectedAndPermissionsStillGateDiscovery() = runBlocking {
        val root = fixture()
        try {
            val context = agentWorkflowContext(root)
            for (name in listOf("list_files", "glob", "search_files", "grep")) {
                val invalid = buildJsonObject {
                    put("path", ""); put("pattern", "**/*.js"); put("query", "needle")
                    put("include_dependencies", "true")
                }
                assertTrue(AgentTools.execute(name, invalid, context).contains("include_dependencies must be a boolean"))
            }
            val denied = agentWorkflowContext(root, permissions = PermissionEngine(listOf(
                PermRule("*", "*", PermEffect.ALLOW),
                PermRule("read", "node_modules/pkg", PermEffect.DENY),
                PermRule("glob", "**/*.js", PermEffect.DENY),
                PermRule("grep", "needle", PermEffect.DENY),
            )))
            assertTrue(AgentTools.execute("list_files", buildJsonObject {
                put("path", "node_modules/pkg"); put("include_dependencies", true)
            }, denied).startsWith("DENIED"))
            assertTrue(AgentTools.execute("glob", buildJsonObject {
                put("pattern", "**/*.js"); put("include_dependencies", true)
            }, denied).startsWith("DENIED"))
            assertTrue(AgentTools.execute("grep", buildJsonObject {
                put("query", "needle"); put("include_dependencies", true)
            }, denied).startsWith("DENIED"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun canonicalAndAliasSchemasExposeOptionalBooleanOptIn() {
        val functions = AgentTools.definitions.map { it.jsonObject.getValue("function").jsonObject }
        for (name in listOf("list_files", "glob", "search_files", "grep")) {
            val parameters = functions.single { it.getValue("name").jsonPrimitive.content == name }
                .getValue("parameters").jsonObject
            val option = parameters.getValue("properties").jsonObject.getValue("include_dependencies").jsonObject
            assertEquals(JsonPrimitive("boolean"), option["type"])
            assertTrue(option.getValue("description").jsonPrimitive.content.contains("false by default"))
            assertFalse(parameters.getValue("required").jsonArray.contains(JsonPrimitive("include_dependencies")))
        }
    }

    private fun fixture(): File = Files.createTempDirectory("droide-agent-dependency-").toFile().also { root ->
        listOf("src/main.js", "node_modules/pkg/index.js").forEach {
            root.resolve(it).apply { parentFile.mkdirs(); writeText("needle\n") }
        }
    }
}
