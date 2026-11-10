package com.baystudio.droide.core

import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentVisionWorkflowTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII=")
    private val config = AgentConfig(providerId = "openai", model = "gpt-4o")

    @Test fun workspaceImageIsSnapshottedAndOnlyMaterializedAtRequestTime() = runBlocking {
        val root = Files.createTempDirectory("droide-vision-snapshot-").toFile()
        try {
            root.resolve("screenshot.png").writeBytes(png)
            val refs = VisionSupport.stage(root, listOf("screenshot.png"), config)
            val persisted = VisionSupport.userMessage("inspect", refs, 1_000)
            assertFalse(persisted.toString().contains("base64"))
            root.resolve("screenshot.png").writeText("source can change without changing the snapshot")
            val request = VisionSupport.materializeHistory(listOf(persisted), root).single()
            assertTrue(request.toString().contains("data:image/png;base64,"))
            root.resolve(refs.single().path).writeText("tampered")
            assertTrue(runCatching { VisionSupport.materializeHistory(listOf(persisted), root) }.isFailure)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun invalidOversizedAndOutOfWorkspaceImagesFailBeforeTransport() = runBlocking {
        val root = Files.createTempDirectory("droide-vision-limits-").toFile()
        try {
            root.resolve("invalid.png").writeText("not an image")
            assertTrue(runCatching { VisionSupport.stage(root, listOf("invalid.png"), config) }.isFailure)
            assertTrue(runCatching { VisionSupport.stage(root, listOf("../escape.png"), config) }.isFailure)
            assertTrue(runCatching { VisionSupport.import(root, ByteArrayInputStream(ByteArray((VisionSupport.MAX_IMAGE_BYTES + 1).toInt()))) }.isFailure)
            assertTrue(runCatching { VisionSupport.stage(root, List(5) { "invalid.png" }, config) }.isFailure)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun viewImageDispatchRequiresPermissionAndAnActualDeliverySink() = runBlocking {
        val root = Files.createTempDirectory("droide-view-image-tool-").toFile()
        try {
            root.resolve("image.png").writeBytes(png)
            val images = AgentToolImages()
            val args = buildJsonObject { put("path", "image.png") }
            val ctx = agentWorkflowContext(root, imageSink = images::add)
            assertTrue(AgentTools.execute("view_image", args, ctx).startsWith("IMAGE_ATTACHED"))
            assertEquals(1, images.refs().size)
            assertTrue(AgentTools.execute("view_image", args, agentWorkflowContext(root)).startsWith("ERROR"))
            val deny = PermissionEngine(listOf(PermRule("read", "*", PermEffect.DENY)))
            assertTrue(AgentTools.execute("view_image", args, agentWorkflowContext(root, permissions = deny, imageSink = images::add)).startsWith("DENIED"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun turnImagesDeduplicateAndLimitsNeverLeaveAPartialCollection() {
        val images = AgentToolImages()
        val ref = AgentImageRef(".droide/vision/a.png", "a", "image/png", 10)
        images.add(listOf(ref, ref)); assertEquals(1, images.refs().size)
        assertThrows(IllegalArgumentException::class.java) { images.add((1..4).map { ref.copy(sha256 = "$it") }) }
        assertEquals(listOf(ref), images.refs())
        assertThrows(IllegalArgumentException::class.java) { images.add(listOf(ref.copy(sha256 = "huge", bytes = VisionSupport.MAX_TOTAL_BYTES))) }
        assertEquals(listOf(ref), images.refs())
    }

    @Test fun completeToolGroupThenRealImageIsAcceptedByAllFourProviderFormats() = runBlocking {
        val root = Files.createTempDirectory("droide-vision-protocols-").toFile()
        try {
            val ref = VisionSupport.import(root, ByteArrayInputStream(png))
            val native = """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"a","name":"view_image","args":{"path":"image.png"}},"thoughtSignature":"signed-a"},{"functionCall":{"id":"b","name":"read_file","args":{"path":"README.md"}}}]},"finishReason":"STOP"}]}"""
            val message = Json.parseToJsonElement(GoogleGenerateContentAdapter.normalizeBuffered(native, Json)).jsonObject
                .getValue("choices").jsonArray[0].jsonObject.getValue("message").jsonObject
            val history = listOf(JsonObject(message + ("role" to JsonPrimitive("assistant"))),
                buildJsonObject { put("role", "tool"); put("tool_call_id", "a"); put("content", "IMAGE_ATTACHED") },
                buildJsonObject { put("role", "tool"); put("tool_call_id", "b"); put("content", "readme") },
                VisionSupport.userMessage("Tool image context", listOf(ref), 1_000))
            assertEquals(history, AgentHistory.completeGroups(history).flatten())
            val body = buildJsonObject { put("model", "gpt-4o"); put("messages", JsonArray(VisionSupport.materializeHistory(history, root))) }.toString()
            val openAi = ProviderToolSchema.openAiRequest(body, Json)
            val responses = OpenAiResponsesAdapter.encodeRequest(body, false, Json)
            val anthropic = AnthropicMessagesAdapter.encodeRequest(body, false, Json)
            val google = GoogleGenerateContentAdapter.encodeRequest(body.replace("gpt-4o", "gemini-3.5-flash"), Json)
            listOf(openAi, responses, anthropic, google).forEach { encoded ->
                val parsed = Json.parseToJsonElement(encoded).jsonObject
                assertTrue(parsed.toString().contains(Base64.getEncoder().encodeToString(png)))
                assertFalse(parsed.toString().contains("droide_image_ref"))
            }
            assertTrue(google.contains("signed-a")); assertTrue(google.indexOf("functionResponse") < google.indexOf("inlineData"))
            assertTrue(responses.indexOf("function_call_output") < responses.indexOf("input_image"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun imageToolsAreHiddenUntilVisionIsAvailableAndUnknownTextProviderRejectsImageHistory() {
        val root = Files.createTempDirectory("droide-vision-surface-").toFile()
        try {
            fun names(vision: Boolean) = AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root),
                visionAvailable = vision).map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
            assertFalse("view_image" in names(false)); assertTrue("view_image" in names(true))
            val history = listOf(buildJsonObject { put("role", "user"); put("content", buildJsonArray {
                add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/png;base64,x") }) })
            }) })
            assertThrows(IllegalArgumentException::class.java) { AgentRequestSupport.build(AgentConfig(providerId = "unknown-text-provider", model = "text-model"), "", history, null) }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
