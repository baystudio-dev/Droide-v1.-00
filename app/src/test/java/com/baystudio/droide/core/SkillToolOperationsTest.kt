package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SkillToolOperationsTest {
    @get:Rule val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private suspend fun fixture(block: suspend (File, SkillManagement) -> Unit) {
        val root = Files.createTempDirectory("skill-tool-").toFile()
        try { block(root, SkillManagement(FileRepository(root))) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private suspend fun call(management: SkillManagement, operation: String, mode: AgentMode = AgentMode.BUILD,
        arguments: JsonObjectBuilder.() -> Unit = {}, permission: suspend (String, String) -> String? = { _, _ -> null }) =
        SkillToolOperations.execute(buildJsonObject { put("operation", operation); arguments() }, management, mode, permission)

    @Test fun listSearchesMetadataWithoutReturningInstructionsAndSaveUsesEditPermission() = runBlocking { fixture { _, management ->
        val gates = mutableListOf<Pair<String, String>>()
        val result = call(management, "save", arguments = { put("content", skillMarkdown(body = "PRIVATE_BODY")) },
            permission = { action, resource -> gates += action to resource; null })
        assertTrue(Json.parseToJsonElement(result).jsonObject.getValue("saved").jsonPrimitive.boolean)
        assertTrue(gates.contains("edit" to ".droide/skills/review-code/SKILL.md"))
        val list = call(management, "list", arguments = { put("query", "changes") })
        assertTrue(list.contains("review-code")); assertFalse(list.contains("PRIVATE_BODY"))
    } }

    @Test fun planModeAndPermissionDenialCannotWriteSkills() = runBlocking { fixture { _, management ->
        assertTrue(runCatching { call(management, "save", AgentMode.PLAN, { put("content", skillMarkdown()) }) }.isFailure)
        val denied = call(management, "save", arguments = { put("content", skillMarkdown()) },
            permission = { action, _ -> if (action == "edit") "DENIED: editing skills" else null })
        assertTrue(denied.startsWith("DENIED")); assertTrue(management.catalog().none { it.sourceLabel != "builtin" })
        management.save(skillMarkdown(body = "PRIVATE_BODY"), "workspace")
        val hidden = call(management, "load", arguments = { put("name", "review-code") }, permission = { _, _ -> "DENIED" })
        assertFalse(hidden.contains("PRIVATE_BODY"))
    } }

    @Test fun importingFolderAsksForReadAndEditBeforePublishingAnySkill() = runBlocking { fixture { root, management ->
        val source = root.resolve("downloaded").apply { mkdir() }
        source.resolve("SKILL.md").writeText(skillMarkdown())
        source.resolve("guide.md").writeText("Reference")
        val gates = mutableListOf<Pair<String, String>>()
        call(management, "import", arguments = { put("path", "downloaded") }, permission = { action, resource -> gates += action to resource; null })
        assertTrue(gates.indexOf("read" to "downloaded") < gates.indexOf("edit" to ".droide/skills/review-code/SKILL.md"))
        assertEquals(listOf("guide.md"), management.resources("review-code"))
    } }

    @Test fun explicitManualSkillRemainsLoadableButIsNotOfferedAutomatically() = runBlocking { fixture { _, management ->
        management.save(skillMarkdown(flags = "disable-model-invocation: true\n"), "workspace")
        assertFalse(call(management, "list").contains("review-code"))
        assertTrue(call(management, "load", AgentMode.PLAN, { put("name", "review-code") }).contains("Review the code carefully"))
    } }

    @Test fun resourceReadsAndCopiesHaveSeparatePermissionChecks() = runBlocking { fixture { root, management ->
        management.save(skillMarkdown(), "workspace")
        root.resolve(".droide/skills/review-code/guide.md").writeText("PRIVATE_GUIDE")
        val denied = call(management, "copy_resource", arguments = {
            put("name", "review-code"); put("path", "guide.md"); put("destination", "copied.md")
        }, permission = { action, _ -> if (action == "read") "DENIED" else null })
        assertEquals("DENIED", denied); assertFalse(root.resolve("copied.md").exists())
        assertFalse(denied.contains("PRIVATE_GUIDE"))
    } }

    @Test fun invalidFieldsScopesAndOffsetsCannotMutateAnything() = runBlocking { fixture { _, management ->
        for (args in listOf(
            buildJsonObject { put("operation", "save"); put("content", skillMarkdown()); put("scope", "../../other") },
            buildJsonObject { put("operation", "save"); put("content", skillMarkdown()); put("overwrite", true) },
            buildJsonObject { put("operation", "list"); put("offset", "0") },
            buildJsonObject { put("operation", "list"); put("offset", -1) },
        )) assertTrue(runCatching { SkillToolOperations.execute(args, management, AgentMode.BUILD) { _, _ -> null } }.isFailure)
        assertTrue(management.catalog().none { it.sourceLabel != "builtin" })
    } }

    @Test fun continuationRequiresTheOriginalVersionAndPreservesUnicodeBoundaries() = runBlocking { fixture { _, management ->
        val body = "x".repeat(7999) + "😀" + "remaining instructions"
        val saved = management.save(skillMarkdown(body = body), "workspace")
        val first = call(management, "load", arguments = { put("name", "review-code") })
        assertTrue(first.contains("next_offset=7999")); assertFalse(first.contains("😀"))
        val second = call(management, "load", arguments = {
            put("name", "review-code"); put("offset", 7999); put("expected_sha256", saved.version.sha256)
        })
        assertTrue(second.contains("😀remaining instructions"))
        assertTrue(runCatching { call(management, "load", arguments = { put("name", "review-code"); put("offset", 7999) }) }.isFailure)
        management.save(skillMarkdown(body = "Changed instructions"), "workspace", saved.version.sha256)
        assertTrue(runCatching { call(management, "load", arguments = {
            put("name", "review-code"); put("offset", 7999); put("expected_sha256", saved.version.sha256)
        }) }.isFailure)
    } }

    @Test fun explicitScopeLoadsAndUpdatesThePersonalCopyHiddenByAProjectOverride() = runBlocking { fixture { root, _ ->
        val management = SkillManagement(FileRepository(root), personalRoot = root.resolve("personal"))
        val personal = management.save(skillMarkdown(body = "Personal instructions"), "user")
        management.save(skillMarkdown(body = "Project instructions"), "workspace")
        val loaded = call(management, "load", arguments = { put("name", "review-code"); put("scope", "user") })
        assertTrue(loaded.contains("Personal instructions")); assertFalse(loaded.contains("Project instructions"))
        assertTrue(loaded.contains(personal.version.sha256))
        call(management, "save", arguments = {
            put("scope", "user"); put("content", skillMarkdown(body = "Updated personal instructions")); put("expected_sha256", personal.version.sha256)
        })
        assertEquals("Updated personal instructions", management.document("review-code", "user").skill.body)
        assertEquals("Project instructions", management.document("review-code").skill.body)
        root.resolve("personal/review-code/guide.md").writeText("Personal reference")
        val resource = call(management, "read_resource", arguments = { put("name", "review-code"); put("path", "guide.md"); put("scope", "user") })
        assertTrue(resource.contains("Personal reference"))
    } }

    @Test fun catalogDescriptionStaysWithinItsByteBudgetWithoutSplittingUnicode() {
        val catalog = (1..80).map { SkillSummary("review-$it", "x".repeat(119) + "😀".repeat(100), "workspace") }
        val schema = SkillToolOperations.definition(catalog)
        val description = schema["function"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(description.contains("more available via operation=list"))
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(description))
        assertTrue(encoded.remaining() < 9_500)
        assertFalse(description.contains("review-41:"))
    }
}
