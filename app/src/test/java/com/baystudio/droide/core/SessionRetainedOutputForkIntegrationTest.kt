package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionRetainedOutputForkIntegrationTest {
    private fun <T> fixture(block: suspend (File, File) -> T): T = runBlocking {
        val directory = Files.createTempDirectory("session-output-fork-").toFile()
        val workspace = directory.resolve("workspace").apply { mkdir() }
        try { block(workspace, directory.resolve("artifacts")) }
        finally { PathSecurity.deleteTreeNoFollow(directory) }
    }
    private fun manager(root: File, store: AgentToolOutputStore) = SessionManager(root).also {
        it.toolOutputStore = { store }
    }
    private fun entry(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
    private fun calls(id: String) = buildJsonObject {
        put("role", "assistant"); put("content", "")
        put("tool_calls", buildJsonArray { add(buildJsonObject {
            put("id", id); put("type", "function")
            put("function", buildJsonObject { put("name", "run_command"); put("arguments", "{}") })
        }) })
    }
    private fun result(id: String, text: String) = buildJsonObject {
        put("role", "tool"); put("tool_call_id", id); put("content", text)
    }
    private fun toolMessage(id: String) = AgentMessage("assistant", "TOOL|run_command|{}|done",
        streamPart = AgentStreamPart("tool:$id", 1, AgentStreamPartKind.TOOL,
            AgentStreamPartState.COMPLETED, toolCallId = id))
    private suspend fun saveHistory(manager: SessionManager, session: DroideSession, outputs: List<String>): DroideSession {
        val exact = mutableListOf(entry("user", "inspect"))
        val visible = mutableListOf(AgentMessage("user", "inspect"))
        outputs.forEachIndexed { index, output ->
            val id = "call-$index"
            exact += calls(id); exact += result(id, output)
            visible += toolMessage(id)
            exact += entry("assistant", "finished-$index")
            visible += AgentMessage("assistant", "finished-$index")
        }
        val saved = session.copy(messages = visible, apiHistory = exact, forkHistory = exact,
            forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)
        manager.save(saved)
        return requireNotNull(manager.get(session.id))
    }
    private fun denied(block: () -> Unit) {
        assertNotNull("Read must not gain another session's ownership", runCatching(block).exceptionOrNull())
    }

    @Test fun actualLatestAndHistoricalForksGrantOnlyTheirSelectedPrefixAndResume() = fixture { root, artifacts ->
        val store = AgentToolOutputStore(artifacts)
        val sessions = manager(root, store)
        val parent = sessions.create("source")
        val first = store.retain(parent.id, "first exact output")
        val later = store.retain(parent.id, "later exact output")
        val saved = saveHistory(sessions, parent, listOf(first.reference, later.reference))
        val latest = requireNotNull(sessions.fork(parent.id))
        assertEquals(saved.forkHistory, latest.forkHistory)
        assertTrue(store.read(latest.id, first.reference).endsWith("first exact output"))
        assertTrue(store.read(latest.id, later.reference).endsWith("later exact output"))

        val historical = requireNotNull(sessions.fork(parent.id, 1))
        assertEquals(saved.forkHistory.take(3), historical.forkHistory)
        assertTrue(store.read(historical.id, first.reference).endsWith("first exact output"))
        denied { store.read(historical.id, later.reference) }
        val reopenedStore = AgentToolOutputStore(artifacts)
        val reopened = manager(root, reopenedStore)
        assertNotNull(reopened.get(latest.id))
        assertTrue(reopenedStore.read(latest.id, later.reference).endsWith("later exact output"))
        val unrelated = sessions.create("unrelated")
        denied { reopenedStore.read(unrelated.id, first.reference) }
        assertEquals(saved.forkHistory, sessions.get(parent.id)!!.forkHistory)
    }

    @Test fun arbitraryTextReferencesNeitherPreventActualForkNorGrantForeignArtifacts() = fixture { root, artifacts ->
        val store = AgentToolOutputStore(artifacts)
        val sessions = manager(root, store)
        val parent = sessions.create("source")
        val own = store.retain(parent.id, "owned")
        val foreign = store.retain("different-owner", "not allowed")
        val forged = AgentToolOutputStore.PREFIX + "0".repeat(32) + "-" + "a".repeat(64)
        saveHistory(sessions, parent, listOf("A file mentions $forged and ${foreign.reference}; actual ${own.reference}"))
        val fork = requireNotNull(sessions.fork(parent.id))
        assertTrue(store.read(fork.id, own.reference).endsWith("owned"))
        denied { store.read(fork.id, forged) }
        denied { store.read(fork.id, foreign.reference) }
        assertNotNull(sessions.get(fork.id))
    }

    @Test fun actualForkRemainsAvailableWhenNewArchiveQuotaIsExhausted() = fixture { root, artifacts ->
        val store = AgentToolOutputStore(artifacts, AgentToolOutputStore.Limits(bytes = 128, entries = 1, outputBytes = 128))
        val sessions = manager(root, store)
        val parent = sessions.create("source")
        val ref = store.retain(parent.id, "original")
        saveHistory(sessions, parent, listOf(ref.reference))
        denied { store.retain(parent.id, "new archive refused") }
        repeat(3) {
            val fork = requireNotNull(sessions.fork(parent.id))
            assertTrue(store.read(fork.id, ref.reference).endsWith("original"))
            assertNotNull(sessions.get(fork.id))
        }
        denied { store.retain(parent.id, "new archives still bounded") }
        assertEquals(3, sessions.children(parent.id).size)
    }

    @Test fun realForkSaveFailureNeverPublishesBrokenSessionAndPreservesParent() = fixture { root, artifacts ->
        val store = AgentToolOutputStore(artifacts)
        val sessions = manager(root, store)
        val parent = sessions.create("source")
        val ref = store.retain(parent.id, "execution evidence")
        saveHistory(sessions, parent, listOf(ref.reference))
        val sessionDir = root.resolve(".droide/sessions")
        val sourceBytes = sessionDir.resolve("${parent.id}.json").readBytes()
        var collision: File? = null

        sessions.textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_WRITE) {
                val temporary = sessionDir.listFiles()?.firstOrNull { it.name.startsWith(".s-") && ".json.tmp-" in it.name }
                if (temporary != null) {
                    val targetName = temporary.name.removePrefix(".").substringBefore(".tmp-")
                    collision = sessionDir.resolve(targetName).also {
                        check(it.mkdir())
                        it.resolve("preserved-collision.txt").writeText("Do not replace this directory")
                    }
                }
            }
        }
        assertNull(sessions.fork(parent.id))
        sessions.textEditIoProbe = null
        assertNotNull("The real save collision must have been injected", collision)
        assertTrue(collision!!.isDirectory)
        assertEquals("Do not replace this directory", collision!!.resolve("preserved-collision.txt").readText())
        assertArrayEquals(sourceBytes, sessionDir.resolve("${parent.id}.json").readBytes())
        assertTrue(store.read(parent.id, ref.reference).endsWith("execution evidence"))
        assertEquals(1, sessionDir.listFiles()!!.count { it.isFile && it.extension == "json" && !it.name.startsWith(".") })
        assertTrue(sessions.children(parent.id).isEmpty())
    }
}
