package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExternalIdeSkillTest {
    @get:Rule val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test fun mcpAndTerminalCliShareSkillDiscoveryLoadingAndGuardedSaving() = runBlocking {
        ExternalIdeFixture(includeSkills = true).use { f ->
            val definitions = f.rpc("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
            val tool = definitions.single { it.jsonObject["name"] == JsonPrimitive("droide_skill") }.jsonObject
            assertEquals(JsonPrimitive(false), tool["annotations"]!!.jsonObject["readOnlyHint"])
            assertEquals(JsonPrimitive(2000), tool["inputSchema"]!!.jsonObject["properties"]!!.jsonObject["content"]!!.jsonObject["maxLength"])
            val saved = f.rpc("tools/call", buildJsonObject {
                put("name", "droide_skill"); put("arguments", buildJsonObject {
                    put("operation", "save"); put("scope", "user"); put("content", skillMarkdown(body = "PERSONAL_INSTRUCTIONS"))
                })
            })["result"]!!.jsonObject
            assertEquals(JsonPrimitive(false), saved["isError"])
            assertTrue(f.root.resolve("personal/review-code/SKILL.md").readText().contains("PERSONAL_INSTRUCTIONS"))
            val args = buildJsonObject { put("operation", "load"); put("name", "review-code") }
            val direct = DroideCliTool(unusedIdePackages(), f.binding.workspaceId).executeResult(listOf("ide", "call", "droide_skill", args.toString()))
            assertEquals(0, direct.exitCode); assertTrue(direct.stdout.contains("PERSONAL_INSTRUCTIONS"))
            val rpc = f.rpc("tools/call", buildJsonObject { put("name", "droide_skill"); put("arguments", args) })["result"]!!.jsonObject
            assertEquals(direct.stdout, rpc["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
            val listed = f.call("droide_skill", buildJsonObject { put("operation", "list") })
            assertTrue(listed.contains("review-code")); assertFalse(listed.contains("PERSONAL_INSTRUCTIONS"))
        }
    }

    @Test fun externalSkillEditsRespectDroidePermissionDenialAndOwnerRetirement() = runBlocking {
        val policy = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("edit", "*", PermEffect.DENY)))
        ExternalIdeFixture(policy, includeSkills = true).use { f ->
            val args = buildJsonObject { put("operation", "save"); put("content", skillMarkdown()) }
            val result = f.rpc("tools/call", buildJsonObject { put("name", "droide_skill"); put("arguments", args) })["result"]!!.jsonObject
            assertEquals(JsonPrimitive(true), result["isError"])
            assertFalse(f.root.resolve(".droide/skills").exists())
            f.ownerOpen = false
            assertTrue(runCatching { f.call("droide_skill", buildJsonObject { put("operation", "list") }) }.isFailure)
        }
    }

    @Test fun externalRequestsStayBoundedAndLargeBundlesCanBeImportedByPath() = runBlocking {
        ExternalIdeFixture(includeSkills = true).use { f ->
            val source = f.root.resolve("downloaded-skill").apply { mkdir() }
            source.resolve("SKILL.md").writeText(skillMarkdown(body = "Instructions ".repeat(1500)))
            source.resolve("guide.md").writeText("Supporting reference")
            val imported = f.rpc("tools/call", buildJsonObject {
                put("name", "droide_skill"); put("arguments", buildJsonObject { put("operation", "import"); put("path", "downloaded-skill") })
            })["result"]!!.jsonObject
            assertEquals(imported.toString(), JsonPrimitive(false), imported["isError"])
            assertEquals("Supporting reference", f.root.resolve(".droide/skills/review-code/guide.md").readText())
            assertTrue(f.rpc("initialize")["result"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.contains("/skill/name"))
            val oversized = f.rpc("tools/call", buildJsonObject {
                put("name", "droide_skill"); put("arguments", buildJsonObject { put("operation", "save"); put("content", "x".repeat(2001)) })
            })["result"]!!.jsonObject
            assertEquals(JsonPrimitive(true), oversized["isError"])
        }
    }
}
