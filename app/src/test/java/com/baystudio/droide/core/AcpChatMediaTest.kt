package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AcpChatMediaTest {
    private val root = Files.createTempDirectory("acp-chat-media-").toFile()
    private val mapper = WorkspacePathMapper(root, "/workspace")
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aYd0AAAAASUVORK5CYII=")
    private fun ref(type: String = "image/png") = ChatMediaRef("m-123", "attachment.png", "attachment.png", type, png.size.toLong(), 1, Long.MAX_VALUE)
    @After fun cleanup() { assertTrue(PathSecurity.deleteTreeNoFollow(root)) }

    @Test fun onlyBooleanAdvertisedInitializeCapabilitiesAreAccepted() {
        fun caps(raw: String) = AcpPromptCapabilities.fromInitialize(Json.parseToJsonElement(raw).jsonObject)
        assertEquals(AcpPromptCapabilities(), caps("{}"))
        assertEquals(AcpPromptCapabilities(), caps("""{"agentCapabilities":{"promptCapabilities":{"image":"true","embeddedContext":1}}}"""))
        assertEquals(AcpPromptCapabilities(true, true), caps("""{"agentCapabilities":{"promptCapabilities":{"image":true,"embeddedContext":true}}}"""))
        assertNotNull(AcpPromptCapabilities().admissionError(listOf(ref())))
        assertNull(AcpPromptCapabilities(image = true).admissionError(listOf(ref("video/mp4"))))
        assertNotNull(AcpPromptCapabilities(image = true).admissionError(listOf(ref("application/pdf"))))
        assertNotNull(AcpPromptCapabilities(true, true).admissionError(listOf(ref().copy(expiresAt = 10)), now = 10))
    }

    @Test fun visualPromptCarriesActualBase64PixelsAndEmbeddedFileContextWithoutInventedModelFlags() {
        File(root, "attachment.png").writeBytes(png)
        val file = File(root, "notes.txt").apply { writeText("A file attachment") }
        val fileRef = ref("text/plain").copy(path = "notes.txt", name = "notes.txt", bytes = file.length())
        val blocks = AcpChatMedia.prompt("Describe these", listOf("attachment.png"), listOf(fileRef to file), root, AcpPromptCapabilities(true, true))
        assertEquals(listOf("text", "image", "resource"), blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertTrue(blocks[0].jsonObject["text"]!!.jsonPrimitive.content.startsWith("[Droide client conversation guidance]"))
        assertTrue(blocks[0].jsonObject["text"]!!.jsonPrimitive.content.contains("Describe these"))
        val image = blocks[1].jsonObject
        assertArrayEquals(png, Base64.getDecoder().decode(image["data"]!!.jsonPrimitive.content))
        assertEquals("image/png", image["mimeType"]!!.jsonPrimitive.content)
        assertFalse(image.toString().contains("attachment.png"))
        assertEquals("droide-media:m-123", blocks[2].jsonObject["resource"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
        assertTrue(blocks[2].jsonObject["resource"]!!.jsonObject["text"]!!.jsonPrimitive.content.contains("A file attachment"))
    }

    @Test fun unsupportedOrOversizedVisualInputFailsBeforeAnyProtocolPromptCanBeBuilt() {
        File(root, "attachment.png").writeBytes(png)
        assertTrue(runCatching { AcpChatMedia.prompt("describe", listOf("attachment.png"), emptyList(), root, AcpPromptCapabilities()) }.isFailure)
        val oversized = File(root, "large.png")
        oversized.outputStream().use { output -> repeat(4_097) { output.write(ByteArray(1_024)) } }
        assertTrue(runCatching { AcpChatMedia.prompt("describe", listOf("large.png"), emptyList(), root, AcpPromptCapabilities(image = true)) }.isFailure)
        assertTrue(runCatching { AcpChatMedia.prompt("describe", List(5) { "attachment.png" }, emptyList(), root, AcpPromptCapabilities(image = true)) }.isFailure)
    }

    @Test fun onlyTypedMediaBlocksCanCreateOutgoingAttachmentsAndTheirFormatMustMatch() {
        assertNull(AcpChatMedia.typedOutput(JsonPrimitive("https://example.com/image.png")))
        assertNull(AcpChatMedia.typedOutput(buildJsonObject { put("type", "text"); put("text", "file:///workspace/photo.png") }))
        val block = buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", Base64.getEncoder().encodeToString(png)) }
        assertEquals(block, AcpChatMedia.typedOutput(block))
        assertArrayEquals(png, AcpChatMedia.decodeImage(block).second)
        assertTrue(runCatching { AcpChatMedia.decodeImage(JsonObject(block + ("mimeType" to JsonPrimitive("image/jpeg")))) }.isFailure)
        assertTrue(runCatching { AcpChatMedia.decodeImage(JsonObject(block + ("data" to JsonPrimitive("invalid base64")))) }.isFailure)
    }

    @Test fun missingFilesAndEscapedImagePathsAreNotProjectedAsImages() {
        assertTrue(runCatching { AcpChatMedia.prompt("describe", listOf("missing.png"), emptyList(), root, AcpPromptCapabilities(image = true)) }.isFailure)
        assertTrue(runCatching { AcpChatMedia.prompt("describe", listOf("../outside.png"), emptyList(), root, AcpPromptCapabilities(image = true)) }.isFailure)
    }

    @Test fun outgoingResourcesOnlyMapRealWorkspaceFileUrisWithoutNetworkAuthoritiesOrTraversal() {
        fun block(uri: String) = buildJsonObject { put("type", "resource_link"); put("uri", uri) }
        val file = File(root, "result.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(file.canonicalFile, AcpChatMedia.localResource(block("file:///workspace/result.mp4"), mapper))
        for (uri in listOf("https://example.com/result.mp4", "file://server/workspace/result.mp4", "file:///workspace/../secret.mp4", "file:///outside/result.mp4", "file:///workspace/result.mp4?token=secret")) {
            assertNull(uri, AcpChatMedia.localResource(block(uri), mapper))
        }
    }

    @Test fun binaryResourcesCarryBytesWithinOneSharedBudgetAndNeverPretendAnExternalCacheIsAWorkspacePath() {
        val file = File(root, "attachment.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val binary = ref("application/octet-stream").copy(name = file.name, path = "virtual-attachment.bin", bytes = file.length())
        val blocks = AcpChatMedia.prompt("Inspect", emptyList(), listOf(binary to file), root, AcpPromptCapabilities(embeddedContext = true))
        val resource = blocks[1].jsonObject["resource"]!!.jsonObject
        assertArrayEquals(file.readBytes(), Base64.getDecoder().decode(resource["blob"]!!.jsonPrimitive.content))
        assertFalse(resource.toString().contains(root.absolutePath))
        assertTrue(resource["uri"]!!.jsonPrimitive.content.startsWith("droide-media:"))
        assertNotNull(AcpPromptCapabilities(embeddedContext = true).admissionError(listOf(binary.copy(bytes = 9L * 1024 * 1024))))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
    }

    @Test fun largeTextAttachmentsUseBoundedHonestExcerptsAndPreserveOriginalFiles() {
        val file = File(root, "large.txt").apply { writeText("a".repeat(12_000) + "🚀tail") }
        val text = ref("text/plain").copy(name = file.name, path = "virtual-large.txt", bytes = file.length())
        val blocks = AcpChatMedia.prompt("Read", emptyList(), listOf(text to file), root, AcpPromptCapabilities(embeddedContext = true))
        val excerpt = blocks[1].jsonObject["resource"]!!.jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(excerpt.contains("excerpt truncated")); assertFalse(excerpt.contains("🚀tail"))
        assertTrue(excerpt.length < 12_200); assertTrue(file.readText().endsWith("🚀tail"))
    }
}
