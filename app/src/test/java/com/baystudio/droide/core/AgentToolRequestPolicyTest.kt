package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Authored only; no checks have been executed in this environment. */
class AgentToolRequestPolicyTest {
    private fun request(vararg names: String) = buildJsonObject {
        put("tools", buildJsonArray { names.forEach { name -> add(buildJsonObject {
            put("type", "function"); put("function", buildJsonObject { put("name", name) })
        }) } })
    }

    @Test fun unknownAndKnownButOmittedToolsAreDenied() {
        val names = AgentToolRequestPolicy.snapshot(request("read_file"))
        assertNull(AgentToolRequestPolicy.denial("read_file", names))
        assertNotNull(AgentToolRequestPolicy.denial("not_a_tool", names))
        assertNotNull(AgentToolRequestPolicy.denial("write_file", names))
    }

    @Test fun textOnlyRequestDoesNotInheritAPreviousSurface() {
        val names = AgentToolRequestPolicy.snapshot(buildJsonObject { put("model", "test") })
        assertTrue(names.isEmpty())
        assertNotNull(AgentToolRequestPolicy.denial("read_file", names))
    }

    @Test fun aliasesRequireTheirOwnAdvertisedNameAndRetainCanonicalModeChecks() {
        val pairs = mapOf("read" to "read_file", "write" to "write_file", "edit" to "edit_file",
            "bash" to "run_command", "grep" to "search_files")
        val catalog = AgentRuntimePrompt.toolNames(AgentToolCatalog.definitions).toSet()
        pairs.forEach { (alias, canonical) ->
            assertTrue(alias in catalog)
            assertTrue(canonical in catalog)
            assertEquals(canonical, AgentModePolicy.canonicalTool(alias))
            assertNotNull(AgentToolRequestPolicy.denial(alias, AgentToolRequestPolicy.snapshot(request(canonical))))
            assertNull(AgentToolRequestPolicy.denial(alias, AgentToolRequestPolicy.snapshot(request(alias, canonical))))
        }
        assertNotNull(AgentModePolicy.check(AgentMode.PLAN, "write"))
    }

    @Test fun eachRequestOwnsItsDynamicCatalogSnapshot() {
        val first = AgentToolRequestPolicy.snapshot(request("read_file", "mcp__old_descriptor"))
        val next = AgentToolRequestPolicy.snapshot(request("read_file", "write_file", "mcp__new_descriptor"))
        assertNotNull(AgentToolRequestPolicy.denial("write_file", first))
        assertNull(AgentToolRequestPolicy.denial("write_file", next))
        assertNotNull(AgentToolRequestPolicy.denial("mcp__old_descriptor", next))
        assertNotNull(AgentToolRequestPolicy.denial("mcp__new_descriptor", first))
    }
}
