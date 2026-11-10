package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AcpMediaOutputImporterTest {
    private val fixture = Files.createTempDirectory("acp-media-output-").toFile()
    private val root = File(fixture, "files/projects/project").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val host = LocalAgentWorkspaceProcessHost(root, File(fixture, "cache"), scope)
    private val store = ChatMediaStore(root)
    @After fun cleanup() { scope.cancel(); assertTrue(PathSecurity.deleteTreeNoFollow(fixture)) }
    private fun block(uri: String) = buildJsonObject { put("type", "resource_link"); put("uri", uri); put("name", "result.png"); put("mimeType", "image/png") }

    @Test fun newAndChangedRuntimeFilesAreCopiedBeforeReconciliationWithoutOverwritingSource() = runBlocking {
        File(root, "changed.png").writeText("source-before-agent")
        val mapper = host.pathMapper()
        File(mapper.remoteRoot, "changed.png").writeText("changed-in-runtime")
        File(mapper.remoteRoot, "new.png").writeText("created-in-runtime")
        for (name in listOf("changed.png", "new.png")) {
            val imported = AcpMediaOutputImporter.read(block(mapper.remoteUriForRelative(name)), store, "s-cli", mapper, host.workspaceIo) { true }!!
            assertEquals(if (name == "new.png") "created-in-runtime" else "changed-in-runtime", store.file(imported)!!.readText())
        }
        assertEquals("source-before-agent", File(root, "changed.png").readText())
        assertFalse(File(root, "new.png").exists())
    }

    @Test fun deniedReadNeverImportsRuntimeBytes() = runBlocking {
        val mapper = host.pathMapper()
        File(mapper.remoteRoot, "private.png").writeText("private")
        var asked = false
        assertNull(AcpMediaOutputImporter.read(block(mapper.remoteUriForRelative("private.png")), store, "s-cli", mapper, host.workspaceIo) {
            asked = true; assertEquals("private.png", it); false
        })
        assertTrue(asked)
        assertTrue(store.ownerRefs("s-cli").isEmpty())
    }

    @Test fun foreignAliasesUrlsAndLinksCannotExposeAnotherConversationOrOutsideFile() = runBlocking {
        val mapper = host.pathMapper()
        val foreign = ByteArrayInputStream("foreign".toByteArray()).use { store.import("s-other", "foreign.png", "image/png", it) }
        assertNull(AcpMediaOutputImporter.read(block(mapper.remoteUriForRelative(foreign.path)), store, "s-cli", mapper, host.workspaceIo) { true })
        assertNull(AcpMediaOutputImporter.read(block("https://example.test/image.png"), store, "s-cli", mapper, host.workspaceIo) { true })
        val outside = File(fixture, "outside.png").apply { writeText("keep private") }
        val link = File(mapper.remoteRoot, "linked.png")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(runCatching { AcpMediaOutputImporter.read(block(mapper.remoteUriForRelative("linked.png")), store, "s-cli", mapper, host.workspaceIo) { true } }.isFailure)
        assertEquals("keep private", outside.readText())
        assertTrue(store.ownerRefs("s-cli").isEmpty())
    }
}
