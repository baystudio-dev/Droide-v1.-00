package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionPublicContentIntegrationTest {
    private fun fixture(block: suspend (File, File) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("public-session-integration-").toFile()
        val workspace = directory.resolve("workspace").apply { mkdir() }
        try { block(workspace, directory.resolve("owned")) }
        finally { PathSecurity.deleteTreeNoFollow(directory) }
    }
    private fun manager(workspace: File, owned: File) = SessionManager(workspace).also { sessions ->
        sessions.publicContentRegistryOverride = AgentPublicContentRegistry(File(owned.parentFile, "registry"))
        val disk = OwnedResponseContentStore.HeadroomAdmission({ Long.MAX_VALUE }, 0)
        sessions.publicContentArchiveFactory = { id, cancelled ->
            AgentPublicContentArchive(OwnedResponseContentStore(owned.toPath(), disk, 128, cancelled), id)
        }
    }
    private fun entry(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }
    private fun export(archive: AgentPublicContentArchive, ref: AgentPublicContentRef) = ByteArrayOutputStream().also { archive.export(ref, it) }.toByteArray()

    @Test fun actualSessionSaveReopenAndForkPreserveLargePublicAnswerAndNeverLeakRefToApiHistory() = fixture { workspace, owned ->
        val sessions = manager(workspace, owned)
        val parent = sessions.create("public answer")
        val original = "line 🌏\n".repeat(25_000)
        assertTrue(original.length > 100_000)
        val value = sessions.publicContentArchive(parent.id).begin("run", "answer").use { capture ->
            original.chunked(4096).forEach(capture::append)
            capture.finishAfterProviderCompletion()
        }
        val history = listOf(entry("user", "hello"), entry("assistant", original))
        val messages = listOf(AgentMessage("user", "hello", forkHistoryEnd = 1),
            AgentMessage("assistant", value.preview, forkHistoryEnd = 2, publicContent = value.reference))
        sessions.save(parent.copy(messages = messages, apiHistory = history, forkHistory = history,
            forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
        val manifest = File(workspace, ".droide/sessions/${parent.id}.json").readText()
        assertFalse(manifest.contains(value.reference.opaqueHandle))
        assertFalse(manifest.contains("opaqueHandle"))
        assertTrue(manifest.contains("publicContentId"))
        val reopenedManager = manager(workspace, owned)
        val reopened = requireNotNull(reopenedManager.get(parent.id))
        val ref = requireNotNull(reopened.messages.last().publicContent)
        assertEquals(value.preview, reopened.messages.last().content)
        assertArrayEquals(original.toByteArray(), export(reopenedManager.publicContentArchive(parent.id), ref))
        assertFalse(reopened.apiHistory.toString().contains(ref.opaqueHandle))
        val fork = requireNotNull(reopenedManager.fork(parent.id))
        val forkRef = requireNotNull(fork.messages.last().publicContent)
        assertNotEquals(ref.opaqueHandle, forkRef.opaqueHandle)
        assertThrows(IllegalStateException::class.java) { reopenedManager.publicContentArchive(fork.id).reopen(ref) }
        assertArrayEquals(original.toByteArray(), export(reopenedManager.publicContentArchive(fork.id), forkRef))
        assertEquals(history, fork.apiHistory)
    }

    @Test fun abortStatusSurvivesActualSaveResumeAndFork() = fixture { workspace, owned ->
        val sessions = manager(workspace, owned)
        val parent = sessions.create("partial")
        var cancelled = false
        val aborted = sessions.publicContentArchive(parent.id) { cancelled }.begin("run", "partial").use { capture ->
            capture.append("saved prefix")
            cancelled = true
            assertThrows(java.util.concurrent.CancellationException::class.java) { capture.append("not accepted") }
            capture.abortToIncomplete()
        }
        val preview = sessions.publicContentArchive(parent.id).reopen(aborted.reference)
        val history = listOf(entry("user", "hello"), entry("assistant", preview.preview))
        sessions.save(parent.copy(messages = listOf(AgentMessage("user", "hello", forkHistoryEnd = 1),
            AgentMessage("assistant", preview.preview, forkHistoryEnd = 2, publicContent = aborted.reference, publicAbort = aborted)),
            apiHistory = history, forkHistory = history, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
        val diskJson = File(workspace, ".droide/sessions/${parent.id}.json").readText()
        assertFalse(diskJson.contains(aborted.reference.opaqueHandle))
        assertFalse(diskJson.contains("opaqueHandle"))
        assertFalse(diskJson.contains("publicAbort"))
        val restored = requireNotNull(sessions.get(parent.id)).messages.last()
        assertEquals(aborted, restored.publicAbort)
        val fork = requireNotNull(sessions.fork(parent.id))
        val evidence = requireNotNull(fork.messages.last().publicAbort)
        assertTrue(evidence.inputHadFailed)
        assertEquals(PublicAnswerAbortTailStatus.FAILED_INPUT_PREFIX_ONLY, evidence.tailStatus)
        assertFalse(evidence.reference.complete)
        assertEquals(fork.messages.last().publicContent, evidence.reference)
        assertArrayEquals("saved prefix".toByteArray(), export(sessions.publicContentArchive(fork.id), evidence.reference))
    }

    @Test fun wrongOwnerRefAndForgedPreviewCannotReplaceExistingManifest() = fixture { workspace, owned ->
        val sessions = manager(workspace, owned)
        val source = sessions.create("source")
        val target = sessions.create("target")
        val value = sessions.publicContentArchive(source.id).begin("run", "answer").use { it.append("real"); it.finishAfterProviderCompletion() }
        assertNotNull(runCatching { sessions.save(target.copy(messages = listOf(AgentMessage("assistant", value.preview, publicContent = value.reference)))) }.exceptionOrNull())
        assertTrue(requireNotNull(sessions.get(target.id)).messages.isEmpty())
        assertNotNull(runCatching { sessions.save(source.copy(messages = listOf(AgentMessage("assistant", "forged", publicContent = value.reference)))) }.exceptionOrNull())
        assertTrue(requireNotNull(sessions.get(source.id)).messages.isEmpty())
    }

    @Test fun legacyMessagesWithNoDescriptorRoundTripUnchanged() = fixture { workspace, owned ->
        val sessions = manager(workspace, owned)
        val parent = sessions.create("legacy")
        val messages = listOf(AgentMessage("user", "hello"), AgentMessage("assistant", "legacy answer"))
        sessions.save(parent.copy(messages = messages))
        assertEquals(messages, requireNotNull(sessions.get(parent.id)).messages)
    }
    @Test fun registryRejectsReboundLocatorAndChangedMessage() = fixture { workspace, owned ->
        val sessions = manager(workspace, owned)
        val session = sessions.create("registry")
        val answer = sessions.publicContentArchive(session.id).begin("r", "i").use {
            it.append("safe public answer"); it.finishAfterProviderCompletion()
        }
        val registry = requireNotNull(sessions.publicContentRegistryOverride)
        val original = AgentMessage("assistant", answer.preview, publicContent = answer.reference)
        val published = registry.publish(session.id, original)
        val public = published.copy(publicContent = null)
        assertEquals(answer.reference, registry.restore(session.id, public).publicContent)
        val nul = registry.publish(session.id, original.copy(content = "a\u0000b", presentationId = "c"))
        assertThrows(IllegalArgumentException::class.java) { registry.restore(session.id, nul.copy(publicContent = null, content = "a", presentationId = "b\u0000c")) }
        assertThrows(IllegalArgumentException::class.java) { registry.restore("other-owner", public) }
        assertThrows(IllegalArgumentException::class.java) { registry.restore(session.id, public.copy(content = "forged")) }
        assertThrows(IllegalArgumentException::class.java) { registry.restore(session.id, public.copy(publicContentId = "../escape")) }
    }
    @Test fun twentyBlobRepeatedSaveBenchmarkAndColdCorruptionCheck() = fixture { workspace, owned ->
        val sessions = manager(workspace, owned)
        val session = sessions.create("benchmark")
        val messages = (1..20).map { index ->
            val answer = sessions.publicContentArchive(session.id).begin("run", "item$index").use {
                it.append("public-$index-".repeat(15000)); it.finishAfterProviderCompletion()
            }
            AgentMessage("assistant", answer.preview, publicContent = answer.reference)
        }
        val factory = requireNotNull(sessions.publicContentArchiveFactory)
        var verificationArchives = 0
        sessions.publicContentArchiveFactory = { id, cancel -> verificationArchives++; factory(id, cancel) }
        val state = session.copy(messages = messages)
        val firstStart = System.nanoTime(); sessions.save(state); val firstMs = (System.nanoTime()-firstStart)/1000000
        assertEquals(20, verificationArchives)
        val warmStart = System.nanoTime(); repeat(5) { sessions.save(state.copy(title = "metadata-$it")) }
        val warmMs = (System.nanoTime()-warmStart)/1000000
        assertEquals(20, verificationArchives)
        println("PUBLIC_CACHE_BENCHMARK blobs=20 firstSaveMs=$firstMs fiveWarmSavesMs=$warmMs fullVerificationCalls=20")
        val oldManifest = File(workspace, ".droide/sessions/${session.id}.json").readBytes()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            sessions.save(state.copy(messages = messages.dropLast(1) + messages.last().copy(content = "tampered")))
        } }
        val lastRef = requireNotNull(messages.last().publicContent)
        listOf(lastRef.copy(sha256 = "0".repeat(64)), lastRef.copy(runId = "wrong-owner"), lastRef.copy(complete = false)).forEach { changed ->
            assertThrows(Exception::class.java) { runBlocking {
                sessions.save(state.copy(messages = messages.dropLast(1) + messages.last().copy(publicContent = changed)))
            } }
        }
        assertArrayEquals(oldManifest, File(workspace, ".droide/sessions/${session.id}.json").readBytes())
        val blob = owned.walkTopDown().first { it.isFile && it.extension == "blob" }
        java.io.RandomAccessFile(blob, "rw").use { it.seek(0); it.write(0) }
        assertThrows(Exception::class.java) { runBlocking { sessions.get(session.id) } }
        assertThrows(Exception::class.java) { runBlocking { manager(workspace, owned).get(session.id) } }
    }
}
