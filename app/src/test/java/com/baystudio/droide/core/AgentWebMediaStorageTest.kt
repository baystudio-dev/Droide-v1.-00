package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class AgentWebMediaStorageTest {
    @org.junit.Before fun openAuthoredUserLaunch() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    private suspend fun fixture(block: suspend (File, AgentWebResearch, WebFixtureTransport) -> Unit) {
        val root = Files.createTempDirectory("web-private-media-").toFile()
        val transport = WebFixtureTransport { url ->
            when {
                url.endsWith(".pdf") -> AgentWebResponse(200, "%PDF-1.4\nfixture".toByteArray(), url, "application/pdf")
                url.endsWith(".png") -> AgentWebResponse(200, webFixturePng, url, "image/png")
                else -> webHtml(url, "<h1>Text source</h1><p>Evidence without media</p>")
            }
        }
        val web = AgentWebResearch(root, transport, WebFixturePdf())
        try { block(root, web, transport) } finally {
            web.close()
            PathSecurity.deleteTreeNoFollow(ChatMediaStoragePaths.directory(root))
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun imageDownloadStagesActualRegisteredPixelsWithoutWritingProjectFiles() = runBlocking {
        fixture { root, web, _ ->
            val user = root.resolve("user-image.png").apply { writeText("original project bytes") }
            val images = AgentToolImages()
            val context = agentWorkflowContext(root, imageSink = images::add, webResearch = web)
            val result = Json.parseToJsonElement(AgentTools.execute("web", buildJsonObject {
                put("operation", "open"); put("url", "https://docs.example/image.png")
            }, context)).jsonObject
            val alias = result.getValue("path").jsonPrimitive.content
            val store = ChatMediaStore(root)
            val source = requireNotNull(store.resolvePath(alias))
            assertTrue(alias.startsWith(".droide/chat-media/blobs/"))
            assertFalse(PathSecurity.contains(root, source))
            assertArrayEquals(webFixturePng, source.readBytes())
            assertEquals(alias, images.refs().single().path)
            assertNotNull(images.refs().single().mediaId)
            assertEquals(alias, store.ownerRefs("test-session").single().path)
            assertFalse(root.resolve(".droide/web-cache").exists())
            web.close()
            assertTrue(source.isFile)
            store.releaseOwner("test-session")
            assertFalse(source.exists())
            assertEquals("original project bytes", user.readText())
        }
    }

    @Test(timeout = 20_000) fun downloadedLogoCanBecomeProjectAssetWithoutAnotherDownloadOrExpiryDependency() = runBlocking {
        fixture { root, web, transport ->
            val permission = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)),
                PermissionPolicyDocument(rules = listOf(PermRule("read", ".droide/chat-media/blobs/*", PermEffect.ALLOW))))
            val base = agentWorkflowContext(root, webResearch = web, permissions = permission)
            val context = AgentTools.Ctx(base.files, base.terminal, base.git, base.approvals, base.questions, base.history,
                base.perms, base.mode, base.llm, base.llmConfig, root, base.todos, base.sessionId, base.lsp,
                webResearch = web, mediaRunId = java.util.UUID.randomUUID().toString(), mediaRequestCurrent = { true })
            val opened = Json.parseToJsonElement(AgentTools.execute("web", buildJsonObject {
                put("operation", "open"); put("url", "https://docs.example/logo.png")
            }, context)).jsonObject
            val saved = AgentTools.execute("save_media_to_project", buildJsonObject {
                put("path", opened.getValue("path")); put("destination", "assets/logo.png")
            }, context)
            assertTrue(saved, saved.startsWith("MEDIA_SAVED_TO_PROJECT "))
            assertEquals(1, transport.requests.size)
            web.close()
            ChatMediaStore(root).releaseDeletedOwner("test-session")
            assertArrayEquals(webFixturePng, root.resolve("assets/logo.png").readBytes())
        }
    }

    @Test fun cachedTextOnlyImageKeepsOneOwnedCopyAndDoesNotClaimPixelDelivery() = runBlocking {
        fixture { root, web, transport ->
            val first = web.open("references", "https://docs.example/image.png", null, 0, 20, false,
                { _, _ -> }, null, mediaOwnerId = "conversation")
            val again = web.open("references", null, first.getValue("ref_id").jsonPrimitive.content, 0, 20, false,
                { _, _ -> }, null, mediaOwnerId = "conversation")
            assertEquals(first["path"], again["path"])
            assertEquals(JsonPrimitive(true), again["cached"])
            assertEquals(JsonPrimitive(false), again["image_input_attached"])
            assertEquals(1, transport.requests.size)
            assertEquals(1, ChatMediaStore(root).ownerRefs("conversation").size)
            assertFalse(root.resolve(".droide").exists())
        }
    }

    @Test fun privatePdfCacheStillSupportsReadAndScreenshotWithManagedImageOutput() = runBlocking {
        fixture { root, web, _ ->
            val pdf = web.open("references", "https://docs.example/manual.pdf", null, 0, 20, false,
                { _, _ -> }, null, mediaOwnerId = "conversation")
            val id = pdf.getValue("ref_id").jsonPrimitive.content
            val page = web.readPdf("references", id, 0, 0, 20, { _, _ -> })
            assertTrue(page.toString().contains("selectable evidence"))
            val screenshot = web.screenshotPdf("references", id, 0, { _, _ -> }, null, mediaOwnerId = "conversation")
            val store = ChatMediaStore(root)
            val image = requireNotNull(store.resolvePath(screenshot.getValue("path").jsonPrimitive.content))
            assertArrayEquals(webFixturePng, image.readBytes())
            assertFalse(PathSecurity.contains(root, image))
            assertEquals(2, store.ownerRefs("conversation").size)
            assertFalse(root.resolve(".droide/web-cache").exists())
            web.close()
            assertTrue(image.exists())
            assertTrue(File(store.storageDirectory, "scratch/web").walkTopDown().none { it.extension in setOf("pdf", "png") })
        }
    }

    @Test fun textResearchDoesNotAllocatePersistentMediaOrScratchDirectories() = runBlocking {
        fixture { root, web, _ ->
            val opened = web.open("references", "https://docs.example/text", null, 0, 20, false,
                { _, _ -> }, null, mediaOwnerId = "conversation")
            assertEquals(JsonPrimitive("page"), opened["kind"])
            assertFalse(ChatMediaStoragePaths.directory(root).exists())
            assertFalse(root.resolve(".droide").exists())
        }
    }
}
