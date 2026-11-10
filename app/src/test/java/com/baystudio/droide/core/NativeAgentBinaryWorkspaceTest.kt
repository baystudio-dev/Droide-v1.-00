package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Native Agent tool dispatch with real host files/processes; not Android/PRoot execution. */
class NativeAgentBinaryWorkspaceTest {
    private class ProjectTerminal(private val root: File) : ITerminalSession {
        override val output = MutableStateFlow("")
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = execStreaming(cmd, timeoutMs) {}
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = LocalProcessSupervisor.capture(argv, root, timeoutMs = timeoutMs)
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit) =
            LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", cmd), root, timeoutMs = timeoutMs, onOutput = onOutput)
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    private fun fixture(block: suspend (File, File) -> Unit) = runBlocking {
        val sandbox = Files.createTempDirectory("native-binary-workspace-").toFile()
        val root = File(sandbox, "files/projects/default").apply { mkdirs() }
        try { withTimeout(30_000) { block(root, sandbox) } }
        finally { PathSecurity.deleteTreeNoFollow(sandbox) }
    }

    @Test fun nativeCommandCreatesRootAndBuildArtifactsVisibleToRepositoryAndCli() = fixture { root, sandbox ->
        val terminal = ProjectTerminal(root)
        val ctx = agentWorkflowContext(root, terminal = terminal)
        val command = """mkdir -p app/build/outputs/apk/debug; printf '\147\154\124\106\000\377' > kucing.glb; """ +
            "dd if=/dev/zero of=app/build/outputs/apk/debug/app-debug.apk bs=1048576 count=17 2>/dev/null"
        val result = AgentTools.execute("run_command", buildJsonObject { put("command", command) }, ctx)
        assertTrue(result, result.contains("exit=0"))
        val files = FileRepository(root)
        assertTrue(files.listFiles("").any { it.path == "kucing.glb" })
        assertTrue(files.listFiles("app/build/outputs/apk/debug").any { it.path.endsWith("app-debug.apk") })
        assertArrayEquals(byteArrayOf(0x67, 0x6c, 0x54, 0x46, 0, 0xff.toByte()), root.resolve("kucing.glb").readBytes())
        assertEquals(17L * 1024 * 1024, root.resolve("app/build/outputs/apk/debug/app-debug.apk").length())
        val mirror = LocalAgentWorkspaceMirror(root, File(sandbox, "cli-workspace"))
        mirror.refresh()
        assertEquals(StreamFileVersion.capture(root, "app/build/outputs/apk/debug/app-debug.apk").sha256,
            StreamFileVersion.capture(mirror.directory, "app/build/outputs/apk/debug/app-debug.apk").sha256)
    }

    @Test fun nativeAttachmentAboveTextLimitSavesExactProjectBytesAndSurvivesChatExpiry() = fixture { root, sandbox ->
        val store = ChatMediaStore(root)
        val original = File(sandbox, "upload.zip")
        original.outputStream().use { output ->
            val block = ByteArray(64 * 1024) { (it % 256).toByte() }
            repeat(21 * 16) { output.write(block) }
        }
        val ref = original.inputStream().use { store.import("test-session", original.name, "application/zip", it) }
        val permission = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)),
            PermissionPolicyDocument(rules = listOf(PermRule("read", ".droide/chat-media/blobs/*", PermEffect.ALLOW))))
        val base = agentWorkflowContext(root, permissions = permission)
        val ctx = AgentTools.Ctx(base.files, base.terminal, base.git, base.approvals, base.questions, base.history,
            base.perms, AgentMode.BUILD, base.llm, base.llmConfig, root, base.todos, "test-session", base.lsp,
            mediaRunId = "native-artifact-copy", mediaRequestCurrent = { true })
        val result = AgentTools.execute("save_media_to_project", buildJsonObject {
            put("path", ref.path); put("destination", "imports/original.zip")
        }, ctx)
        assertTrue(result, result.startsWith("MEDIA_SAVED_TO_PROJECT "))
        assertEquals(original.length(), root.resolve("imports/original.zip").length())
        val actual = StreamFileVersion.capture(root, "imports/original.zip")
        assertEquals(StreamFileVersion.capture(sandbox, "upload.zip").sha256, actual.sha256)
        assertTrue(base.files.listFiles("imports").any { it.path == "imports/original.zip" })
        store.cleanupExpired(ref.expiresAt)
        assertNull(store.file(ref))
        assertEquals(actual.sha256, StreamFileVersion.capture(root, "imports/original.zip").sha256)
    }

    @Test fun nativeSendMediaSharesBinaryWithoutRemovingCanonicalBuildOutput() = fixture { root, _ ->
        val path = "app/build/outputs/model.glb"
        root.resolve(path).apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0, 0xff.toByte(), 0x67, 0x6c, 0x54, 0x46)) }
        val published = mutableListOf<ChatMediaRef>()
        val base = agentWorkflowContext(root)
        val ctx = AgentTools.Ctx(base.files, base.terminal, base.git, base.approvals, base.questions, base.history,
            base.perms, AgentMode.BUILD, base.llm, base.llmConfig, root, base.todos, "test-session", base.lsp,
            onMediaReady = published::add)
        val result = AgentTools.execute("send_media", buildJsonObject { put("path", path) }, ctx)
        assertTrue(result, result.startsWith("MEDIA_SENT "))
        val store = ChatMediaStore(root)
        assertArrayEquals(root.resolve(path).readBytes(), store.file(published.single())!!.readBytes())
        store.releaseOwner("test-session")
        assertEquals(6L, root.resolve(path).length())
        assertTrue(base.files.listFiles("app/build/outputs").any { it.path == path })
    }
}
