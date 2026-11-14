package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class AgentMediaToolsTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII=")

    @Test fun mediaToolSurfaceRequiresBoundCapabilitiesAndModeAuthority() {
        withWorkspace { root ->
            fun names(mode: AgentMode, vision: Boolean, media: Boolean, permissions: PermissionEngine? = null) =
                AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root), mode = mode,
                    permissions = permissions, visionAvailable = vision, mediaAvailable = media)
                    .map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }.toSet()
            for (mode in AgentMode.entries) for (vision in listOf(false, true)) for (media in listOf(false, true)) {
                val surface = names(mode, vision, media)
                assertEquals(vision && media, "view_video" in surface)
                assertEquals(media && mode == AgentMode.BUILD, "send_media" in surface)
            }
            val readDenied = PermissionEngine(listOf(PermRule("read", "*", PermEffect.DENY), PermRule("edit", "*", PermEffect.ALLOW)))
            assertFalse("view_video" in names(AgentMode.BUILD, true, true, readDenied))
            assertFalse("send_media" in names(AgentMode.BUILD, true, true, readDenied))
            val editDenied = PermissionEngine(listOf(PermRule("edit", "*", PermEffect.DENY), PermRule("read", "*", PermEffect.ALLOW)))
            assertTrue("view_video" in names(AgentMode.BUILD, true, true, editDenied))
            assertFalse("send_media" in names(AgentMode.BUILD, true, true, editDenied))
        }
    }

    @Test fun schemasAndPermissionClassesMakePublishingAnExplicitMutation() {
        val functions = AgentTools.definitions.map { it.jsonObject.getValue("function").jsonObject }
            .associateBy { it.getValue("name").jsonPrimitive.content }
        for (name in listOf("view_video", "send_media")) {
            val parameters = functions.getValue(name).getValue("parameters").jsonObject
            assertEquals("string", parameters.getValue("properties").jsonObject.getValue("path").jsonObject.getValue("type").jsonPrimitive.content)
            assertTrue(parameters.getValue("required").toString().contains("path"))
        }
        assertEquals("read", AgentTools.actionOf("view_video"))
        assertEquals("edit", AgentTools.actionOf("send_media"))
        for (mode in AgentMode.entries) {
            assertNull(AgentModePolicy.check(mode, "view_video"))
            assertEquals(mode != AgentMode.BUILD, AgentModePolicy.check(mode, "send_media") != null)
        }
    }

    @Test fun publishingMakesDurableOwnedCopyAndNeverDeletesWorkspaceOriginal() = runBlocking {
        withWorkspaceSuspend { root ->
            val original = root.resolve("report.md").apply { writeText("important report") }
            val published = mutableListOf<ChatMediaRef>()
            val result = AgentTools.execute("send_media", path("report.md"), context(root, published::add))
            assertTrue(result.startsWith("MEDIA_SENT "))
            assertTrue(result.contains("current_conversation"))
            val ref = published.single()
            assertTrue(result.contains("\"path\":\"${ref.path}\""))
            val store = ChatMediaStore(root)
            assertEquals(listOf(ref), store.ownerRefs("test-session"))
            assertEquals("text/markdown", ref.mediaType)
            assertEquals(ChatMediaStore.RETENTION_MS, ref.expiresAt - ref.createdAt)
            assertEquals("important report", store.file(ref)!!.readText())
            original.writeText("user edits original")
            assertEquals("important report", store.file(ref)!!.readText())
            store.releaseOwner("test-session")
            assertNull(store.file(ref))
            assertEquals("user edits original", original.readText())
        }
    }

    @Test fun eachReadAndEditDenialBlocksCopyAndCallback() = runBlocking {
        withWorkspaceSuspend { root ->
            root.resolve("report.txt").writeText("report")
            for (action in listOf("read", "edit")) {
                val published = mutableListOf<ChatMediaRef>()
                val permissions = PermissionEngine(listOf(PermRule(action, "*", PermEffect.DENY), PermRule("*", "*", PermEffect.ALLOW)))
                val result = AgentTools.execute("send_media", path("report.txt"), context(root, published::add, permissions = permissions))
                assertTrue(result.startsWith("DENIED"))
                assertTrue(published.isEmpty())
                assertTrue(ChatMediaStore(root).ownerRefs("test-session").isEmpty())
            }
            assertEquals("report", root.resolve("report.txt").readText())
        }
    }

    @Test fun nonBuildModeBlocksEvenProviderInjectedPublishingCall() = runBlocking {
        withWorkspaceSuspend { root ->
            root.resolve("report.txt").writeText("report")
            for (mode in AgentMode.entries.filter { it != AgentMode.BUILD }) {
                val published = mutableListOf<ChatMediaRef>()
                assertTrue(AgentTools.execute("send_media", path("report.txt"), context(root, published::add, mode = mode)).startsWith("DENIED"))
                assertTrue(published.isEmpty())
            }
        }
    }

    @Test fun missingSessionOrDeliverySinkDoesNotLeaveAnOwnedCopy() = runBlocking {
        withWorkspaceSuspend { root ->
            root.resolve("report.txt").writeText("report")
            assertTrue(AgentTools.execute("send_media", path("report.txt"), context(root, null)).startsWith("ERROR"))
            val published = mutableListOf<ChatMediaRef>()
            assertTrue(AgentTools.execute("send_media", path("report.txt"), context(root, published::add, sessionId = null)).startsWith("ERROR"))
            assertTrue(published.isEmpty())
            assertTrue(root.resolve(".droide/chat-media/blobs").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun traversalAbsoluteSymlinkEscapeAndRemoteUrlsCannotBePublished() = runBlocking {
        withWorkspaceSuspend { root ->
            val outside = Files.createTempFile("droide-media-private-", ".txt").toFile()
            try {
                outside.writeText("outside private data")
                Files.createSymbolicLink(root.resolve("escape.txt").toPath(), outside.toPath())
                val published = mutableListOf<ChatMediaRef>()
                for (name in listOf("../${outside.name}", outside.absolutePath, "escape.txt", "https://example.com/file.png")) {
                    assertTrue(AgentTools.execute("send_media", path(name), context(root, published::add)).startsWith("ERROR"))
                }
                assertTrue(published.isEmpty())
                assertEquals("outside private data", outside.readText())
            } finally { outside.delete() }
        }
    }

    @Test fun invalidArgumentsDirectoriesEmptyAndOversizedFilesFailBeforePublication() = runBlocking {
        withWorkspaceSuspend { root ->
            root.resolve("empty.bin").createNewFile()
            root.resolve("directory").mkdir()
            RandomAccessFile(root.resolve("large.bin"), "rw").use { it.setLength(ChatMediaStore.MAX_FILE_BYTES + 1) }
            val published = mutableListOf<ChatMediaRef>()
            for (args in listOf(path("empty.bin"), path("directory"), path("large.bin"), path(" "), buildJsonObject { put("path", 42) }, buildJsonObject {})) {
                assertTrue(AgentTools.execute("send_media", args, context(root, published::add)).startsWith("ERROR"))
            }
            assertTrue(published.isEmpty())
        }
    }

    @Test fun callbackFailureOrCancellationReclaimsOnlyItsNewCopy() = runBlocking {
        withWorkspaceSuspend { root ->
            root.resolve("report.txt").writeText("preserved")
            val failure = AgentTools.execute("send_media", path("report.txt"), context(root, { error("publication rejected") }))
            assertTrue(failure.startsWith("ERROR"))
            assertTrue(ChatMediaStore(root).ownerRefs("test-session").isEmpty())
            var cancelled = false
            try {
                AgentTools.execute("send_media", path("report.txt"), context(root, { throw CancellationException("stopped") }))
            } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertTrue(ChatMediaStore(root).ownerRefs("test-session").isEmpty())
            assertEquals("preserved", root.resolve("report.txt").readText())
        }
    }

    @Test fun imageTypeUsesActualHeaderInsteadOfTrustingOnlyTheSuffix() = runBlocking {
        withWorkspaceSuspend { root ->
            root.resolve("photo.bin").writeBytes(png)
            root.resolve("fake.png").writeText("not image data")
            val published = mutableListOf<ChatMediaRef>()
            assertTrue(AgentTools.execute("send_media", path("photo.bin"), context(root, published::add)).startsWith("MEDIA_SENT"))
            assertTrue(AgentTools.execute("send_media", path("fake.png"), context(root, published::add)).startsWith("MEDIA_SENT"))
            assertEquals("image/png", published[0].mediaType)
            assertEquals("application/octet-stream", published[1].mediaType)
        }
    }

    @Test fun videoInspectionRequiresRuntimeAndDeliveryRatherThanPretendingTextIsVision() = runBlocking {
        withWorkspaceSuspend { root ->
            root.resolve("clip.mp4").writeText("not a video")
            val result = AgentTools.execute("view_video", path("clip.mp4"), context(root, null))
            assertTrue(result.startsWith("ERROR"))
            assertTrue(result.contains("unavailable"))
            assertTrue(ChatMediaStore(root).ownerRefs("test-session").isEmpty())
        }
    }

    @Test fun workspaceViewImageNowUsesSessionOwnedRetention() = runBlocking {
        withWorkspaceSuspend { root ->
            root.resolve("image.png").writeBytes(png)
            val images = mutableListOf<AgentImageRef>()
            assertTrue(AgentTools.execute("view_image", path("image.png"), context(root, null, imageSink = images::addAll)).startsWith("IMAGE_ATTACHED"))
            val refs = ChatMediaStore(root).ownerRefs("test-session")
            assertEquals(1, refs.size)
            assertEquals(refs.single().id, images.single().mediaId)
            ChatMediaStore(root).releaseOwner("test-session")
            assertTrue(root.resolve("image.png").exists())
        }
    }

    @Test(timeout = 10_000) fun visualAliasFromAnotherConversationCannotBeViewedOrRepublished() = runBlocking {
        withWorkspaceSuspend { root ->
            val store = ChatMediaStore(root)
            val ref = png.inputStream().use { store.import("s-other", "private.png", "image/png", it) }
            val published = mutableListOf<ChatMediaRef>()
            val images = mutableListOf<AgentImageRef>()
            for (tool in listOf("view_image", "send_media")) {
                val result = AgentTools.execute(tool, path(ref.path), context(root, published::add, imageSink = { images.addAll(it) }))
                assertTrue(result, result.startsWith("ERROR"))
            }
            assertTrue(published.isEmpty()); assertTrue(images.isEmpty())
            assertTrue(store.ownerRefs("test-session").isEmpty())
            assertArrayEquals(png, store.file(ref)!!.readBytes())
        }
    }

    @Test fun largeBuildOutputCanBeReferencedWithoutCopyingBytesIntoChat() = runBlocking {
        withWorkspaceSuspend { root ->
            val file = root.resolve("app-release.apk")
            val bytes = 80L * 1024 * 1024
            RandomAccessFile(file, "rw").use { it.setLength(bytes) }
            val progress = mutableListOf<AgentToolProgress>()
            val media = mutableListOf<ChatMediaRef>()
            val result = AgentTools.execute("send_media", buildJsonObject {
                put("path", file.name); put("reference_only", true)
            }, context(root, media::add, progress = progress::add))
            assertTrue(result, result.startsWith("PROJECT_RESULT "))
            assertEquals(listOf(file.name), progress.single().producedFiles)
            assertTrue(media.isEmpty()); assertEquals(bytes, file.length())
            assertFalse(root.resolve(".droide/chat-media/blobs").exists())
        }
    }

    @Test(timeout = 10_000) fun referenceStillRequiresPermissionsAndRejectsSymlinksAndInternalState() = runBlocking {
        withWorkspaceSuspend { root ->
            root.resolve("report.txt").writeText("retained")
            Files.createSymbolicLink(root.resolve("linked.txt").toPath(), root.resolve("report.txt").toPath())
            val progress = mutableListOf<AgentToolProgress>()
            fun reference(path: String) = buildJsonObject { put("path", path); put("reference_only", true) }
            for (path in listOf("../outside", "linked.txt", ".droide/private.json")) {
                val result = AgentTools.execute("send_media", reference(path), context(root, {}, progress = progress::add))
                assertTrue(result, result.startsWith("ERROR"))
            }
            for (action in listOf("read", "edit")) {
                val permission = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule(action, "*", PermEffect.DENY)))
                val result = AgentTools.execute("send_media", reference("report.txt"), context(root, {}, permissions = permission, progress = progress::add))
                assertTrue(result, result.startsWith("DENIED"))
            }
            assertTrue(progress.isEmpty())
        }
    }

    @Test fun malformedReferenceFlagCannotFallBackToCopyingTheFile() = runBlocking {
        withWorkspaceSuspend { root ->
            root.resolve("report.txt").writeText("retained")
            val media = mutableListOf<ChatMediaRef>()
            val result = AgentTools.execute("send_media", buildJsonObject {
                put("path", "report.txt"); put("reference_only", "true")
            }, context(root, media::add))
            assertTrue(result.startsWith("ERROR")); assertTrue(media.isEmpty())
        }
    }

    private fun path(value: String) = buildJsonObject { put("path", value) }

    private fun context(
        root: File,
        mediaSink: ((ChatMediaRef) -> Unit)?,
        permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))),
        mode: AgentMode = AgentMode.BUILD,
        sessionId: String? = "test-session",
        imageSink: ((List<AgentImageRef>) -> Unit)? = null,
        progress: (AgentToolProgress) -> Unit = {},
    ): AgentTools.Ctx {
        val base = agentWorkflowContext(root, permissions = permissions, mode = mode)
        return AgentTools.Ctx(base.files, base.terminal, base.git, base.approvals, base.questions, base.history,
            base.perms, base.mode, base.llm, base.llmConfig, root, base.todos, sessionId, base.lsp,
            onImageReady = imageSink, onMediaReady = mediaSink, onProgress = progress)
    }

    private inline fun withWorkspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-media-tools-").toFile()
        try { block(root) } finally {
            PathSecurity.deleteTreeNoFollow(ChatMediaStore(root).storageDirectory)
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private suspend fun withWorkspaceSuspend(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("droide-media-tools-").toFile()
        try { block(root) } finally {
            PathSecurity.deleteTreeNoFollow(ChatMediaStore(root).storageDirectory)
            PathSecurity.deleteTreeNoFollow(root)
        }
    }
}
