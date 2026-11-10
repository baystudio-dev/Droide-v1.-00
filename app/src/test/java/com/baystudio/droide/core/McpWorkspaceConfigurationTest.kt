package com.baystudio.droide.core

import com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority
import com.baystudio.droide.ui.EditorWorkspaceState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpWorkspaceConfigurationTest {
    private fun fixture(block: suspend (File, McpWorkspaceConfiguration) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("mcp-config-").toFile()
        try { block(root, McpWorkspaceConfiguration(FileRepository(root))) }
        finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun write(root: File, text: String) {
        root.resolve(".droide").mkdirs()
        root.resolve(McpWorkspaceConfiguration.PATH).writeText(text)
    }

    @Test fun createsRenamesDisablesAndRemovesWithoutLosingOtherConfiguration() = fixture { root, store ->
        write(root, """{"schemaVersion":7,"servers":{"one":{"command":"node","description":"retain me"},"other":{"command":"python3"}}}""")
        val loaded = store.load()
        val renamed = store.save(loaded, McpServerDraft("renamed", "node", "[\"file with spaces.js\",\"\"]"), "one")
        assertEquals(listOf("renamed", "other").sorted(), renamed.servers.map { it.name }.sorted())
        assertEquals(listOf("node", "file with spaces.js", ""), renamed.servers.first { it.name == "renamed" }.argv)
        val json = Json.parseToJsonElement(root.resolve(McpWorkspaceConfiguration.PATH).readText()).jsonObject
        assertEquals(JsonPrimitive(7), json["schemaVersion"])
        assertEquals(JsonPrimitive("retain me"), json["servers"]!!.jsonObject["renamed"]!!.jsonObject["description"])
        val disabled = store.setEnabled(renamed, "renamed", false)
        assertFalse(disabled.servers.first { it.name == "renamed" }.enabled)
        val removed = store.remove(disabled, "renamed")
        assertEquals(listOf("other"), removed.servers.map { it.name })
    }

    @Test fun addFromAbsentSnapshotCannotOverwriteConcurrentlyCreatedFile() = fixture { root, store ->
        val absent = store.load()
        val concurrent = """{"servers":{"external":{"command":"node"}}}"""
        write(root, concurrent)
        assertTrue(runCatching { store.save(absent, McpServerDraft("new", "node"), null) }.isFailure)
        assertEquals(concurrent, root.resolve(McpWorkspaceConfiguration.PATH).readText())
    }

    @Test fun staleEditCannotOverwriteAnotherWriter() = fixture { root, store ->
        val first = store.save(store.load(), McpServerDraft("one", "node"), null)
        val next = store.save(first, McpServerDraft("two", "python3"), null)
        val before = root.resolve(McpWorkspaceConfiguration.PATH).readText()
        assertTrue(runCatching { store.remove(first, "one") }.isFailure)
        assertEquals(before, root.resolve(McpWorkspaceConfiguration.PATH).readText())
        assertEquals(2, next.servers.size)
    }

    @Test fun duplicateNamesInvalidArgsAndLimitsDoNotTouchDisk() = fixture { root, store ->
        val first = store.save(store.load(), McpServerDraft("one", "node"), null)
        val before = root.resolve(McpWorkspaceConfiguration.PATH).readText()
        val bad = listOf(McpServerDraft("one", "node"), McpServerDraft("bad name", "node"),
            McpServerDraft("two", "node", "[42]"), McpServerDraft("two", "node", "server.js"),
            McpServerDraft("two", "node;echo"), McpServerDraft("two", "node", JsonArray(List(65) { JsonPrimitive("arg") }).toString()),
            McpServerDraft("two", "node", JsonArray(listOf(JsonPrimitive("x".repeat(4097)))).toString()))
        bad.forEach { assertTrue(it.name, runCatching { store.save(first, it, null) }.isFailure) }
        assertEquals(before, root.resolve(McpWorkspaceConfiguration.PATH).readText())
    }

    @Test fun invalidExistingConfigurationIsNeverReplacedWithAnEmptyOne() = fixture { root, store ->
        for (invalid in listOf("{", "{}", "{\"servers\":[]}", " ".repeat(100_001))) {
            write(root, invalid)
            assertTrue(runCatching { store.load() }.isFailure)
            assertEquals(invalid, root.resolve(McpWorkspaceConfiguration.PATH).readText())
        }
    }

    @Test fun serverEnablementAndTransportUseStrictTypes() = fixture { root, store ->
        for (entry in listOf("\"enabled\":\"false\"", "\"enabled\":1", "\"type\":\"http\"", "\"args\":[null]")) {
            write(root, """{"servers":{"local":{"command":"node",$entry}}}""")
            assertTrue(entry, runCatching { store.load() }.isFailure)
            assertTrue(McpManager(root).list().isEmpty())
        }
        write(root, """{"servers":{"local":{"type":"stdio","transport":"stdio","command":"node","enabled":false}}}""")
        assertFalse(store.load().servers.single().enabled)
    }

    @Test fun dirtyEditorConfigurationBlocksManagementWriteAndKeepsBuffer() = fixture { root, _ ->
        val text = """{"servers":{"local":{"command":"node"}}}"""
        write(root, text)
        val state = EditorWorkspaceState()
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val files = FileRepository(root, beforeMutation = authority::validatePersistedMutation)
        val store = McpWorkspaceConfiguration(files)
        val document = state.document(McpWorkspaceConfiguration.PATH)
        document.ensureLoaded(files)
        val snapshot = store.load()
        document.applyWorkspaceText("unsaved editor text")
        assertTrue(runCatching { store.remove(snapshot, "local") }.isFailure)
        assertEquals("unsaved editor text", document.content)
        assertTrue(document.dirty)
        assertEquals(text, root.resolve(McpWorkspaceConfiguration.PATH).readText())
    }

    @Test fun snapshotsCannotBeReusedAcrossWorkspaceControllers() = fixture { root, store ->
        val snapshot = store.load()
        val other = McpWorkspaceConfiguration(FileRepository(root))
        assertTrue(runCatching { other.save(snapshot, McpServerDraft("wrong", "node"), null) }.isFailure)
        assertFalse(root.resolve(McpWorkspaceConfiguration.PATH).exists())
    }
}
