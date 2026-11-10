package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentMediaVideoDispatchTest {
    @Test fun readDenialPreventsImportAndDecodeOnAvailableNativeContext() = runBlocking {
        val root = Files.createTempDirectory("droide-video-read-denied-").toFile()
        try {
            root.resolve("clip.mp4").writeText("not decoded when permission is denied")
            var called = false
            val permissions = PermissionEngine(listOf(PermRule("read", "*", PermEffect.DENY)))
            val result = AgentTools.execute("view_video", path("clip.mp4"), context(root, permissions = permissions) { called = true })
            assertTrue(result.startsWith("DENIED"))
            assertFalse(called)
            assertTrue(ChatMediaStore(root).ownerRefs("test-session").isEmpty())
        } finally {
            PathSecurity.deleteTreeNoFollow(ChatMediaStore(root).storageDirectory)
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun unsupportedAndOversizedVideosFailBeforeAnyPixelsOrOwnedCopies() = runBlocking {
        val root = Files.createTempDirectory("droide-video-source-rejected-").toFile()
        try {
            root.resolve("report.txt").writeText("not a video")
            RandomAccessFile(root.resolve("huge.mp4"), "rw").use { it.setLength(ChatMediaStore.MAX_FILE_BYTES + 1) }
            var called = false
            for (name in listOf("report.txt", "huge.mp4")) {
                val result = AgentTools.execute("view_video", path(name), context(root) { called = true })
                assertTrue(result.startsWith("ERROR"))
                assertFalse(called)
                assertTrue(ChatMediaStore(root).ownerRefs("test-session").isEmpty())
            }
        } finally {
            PathSecurity.deleteTreeNoFollow(ChatMediaStore(root).storageDirectory)
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun unknownTextModelCannotDecodeVideoEvenWithAnImageDeliverySink() = runBlocking {
        val root = Files.createTempDirectory("droide-video-text-model-").toFile()
        try {
            root.resolve("clip.mp4").writeText("not decoded for a text-only model")
            var called = false
            val result = AgentTools.execute("view_video", path("clip.mp4"), context(root,
                config = AgentConfig(providerId = "unknown-text-provider", model = "text-model")) { called = true })
            assertTrue(result.startsWith("ERROR"))
            assertTrue(result.contains("does not support image input"))
            assertFalse(called)
            assertTrue(ChatMediaStore(root).ownerRefs("test-session").isEmpty())
        } finally {
            PathSecurity.deleteTreeNoFollow(ChatMediaStore(root).storageDirectory)
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private fun path(value: String) = buildJsonObject { put("path", value) }

    private fun context(
        root: File,
        permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))),
        config: AgentConfig = AgentConfig(providerId = "openai", model = "gpt-4o"),
        imageSink: (List<AgentImageRef>) -> Unit,
    ): AgentTools.Ctx {
        val base = agentWorkflowContext(root, permissions = permissions)
        return AgentTools.Ctx(base.files, base.terminal, base.git, base.approvals, base.questions, base.history,
            base.perms, base.mode, base.llm, config, root, base.todos, base.sessionId, base.lsp,
            onImageReady = imageSink, appContext = RuntimeEnvironment.getApplication())
    }
}
