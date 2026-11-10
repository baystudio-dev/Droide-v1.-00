package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class SessionHeaderMetadataTest {
    @Test fun headerReadsMetadataEvenWhenColdHistoryCannotBeRestoredAndPreservesOriginalData() = runBlocking {
        val root = Files.createTempDirectory("agent-header-metadata-").toFile()
        try {
            val manager = SessionManager(root)
            val session = manager.create("Long running task")
            manager.save(session.copy(messages = listOf(AgentMessage("user", "Please continue")),
                forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION,
                forkHistory = listOf(buildJsonObject { put("role", "user"); put("content", "a".repeat(600_000)) })))
            val page = root.resolve(".droide/sessions/.history-pages").listFiles()!!.single { it.extension == "json" }
            page.writeText("corrupted fixture")
            val saved = root.resolve(".droide/sessions/${session.id}.json").readBytes()
            assertEquals("Long running task", manager.getSummary(session.id)!!.title)
            assertEquals(1, manager.getSummary(session.id)!!.messageCount)
            assertThrows(SessionHistoryCorruptionException::class.java) { runBlocking { manager.get(session.id) } }
            assertArrayEquals(saved, root.resolve(".droide/sessions/${session.id}.json").readBytes())
            assertEquals("corrupted fixture", page.readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun summaryReflectsChangedTitleAndRejectsAConversationSymlink() = runBlocking {
        val root = Files.createTempDirectory("agent-header-updated-").toFile()
        try {
            val manager = SessionManager(root)
            val session = manager.create("Before")
            manager.save(session.copy(title = "After"))
            assertEquals("After", manager.getSummary(session.id)!!.title)
            assertNull(manager.getSummary("s-unknown"))
            val source = root.resolve(".droide/sessions/${session.id}.json")
            Files.createSymbolicLink(root.resolve(".droide/sessions/s-link.json").toPath(), source.toPath())
            assertNull(manager.getSummary("s-link"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
