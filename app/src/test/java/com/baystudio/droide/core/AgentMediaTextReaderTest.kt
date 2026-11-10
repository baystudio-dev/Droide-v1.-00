package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentMediaTextReaderTest {
    private fun fixture(block: suspend (java.io.File, ChatMediaStore) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-media-read").toFile()
        val store = ChatMediaStore(root)
        try { block(root, store) }
        finally { PathSecurity.deleteTreeNoFollow(store.storageDirectory); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun textAliasReadsPrivateBackingWithoutCreatingProjectFiles() = fixture { root, store ->
        val ref = ByteArrayInputStream("first\nsecond\nthird".toByteArray()).use { store.import("session", "notes.md", "text/markdown", it) }
        assertFalse(root.resolve(ref.path).exists())
        val text = AgentMediaTextReader.read(root, ref.path, 2, 2, null, "session")
        assertTrue(text.contains("second")); assertFalse(text.contains("first"))
        assertFalse(root.resolve(".droide/chat-media").exists())
    }

    @Test fun characterPagingIsLosslessForUnicodeAndDoesNotReadWholeLargeAttachment() = fixture { root, store ->
        val original = "a".repeat(9_999) + "😀" + "b".repeat(7_000)
        val ref = ByteArrayInputStream(original.toByteArray()).use { store.import("session", "source.kt", "text/plain", it) }
        var offset = 0
        val returned = StringBuilder()
        do {
            val result = AgentMediaTextReader.read(root, ref.path, null, null, offset, "session")
            val paging = result.substringAfter('\n').substringBefore('\n')
            returned.append(result.substringAfter('\n').substringAfter('\n'))
            offset = Regex("next_offset=(\\d+)").find(paging)!!.groupValues[1].toInt()
        } while ("has_more=true" in paging)
        assertEquals(original, returned.toString())
    }

    @Test fun foreignExpiredAndForgedAliasesNeverBecomeWorkspaceReads() = fixture { root, store ->
        val ref = ByteArrayInputStream("hello".toByteArray()).use { store.import("session", "notes.txt", "text/plain", it) }
        assertTrue(runCatching { AgentMediaTextReader.read(root, ref.path, null, null, null, "other") }.isFailure)
        assertTrue(runCatching { AgentMediaTextReader.read(root, ref.path + "/../../source.kt", null, null, null, "session") }.isFailure)
        store.cleanupExpired(ref.expiresAt)
        assertTrue(runCatching { AgentMediaTextReader.read(root, ref.path, null, null, null, "session") }.isFailure)
        assertFalse(root.resolve(ref.path).exists())
    }

    @Test fun binaryAttachmentIsNotPretendedToBeDecodedText() = fixture { root, store ->
        val ref = ByteArrayInputStream(byteArrayOf(0, 1, 2)).use { store.import("session", "clip.mp4", "video/mp4", it) }
        val failure = runCatching { AgentMediaTextReader.read(root, ref.path, null, null, null, "session") }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("binary attachment"))
    }

    @Test fun readonlyRepositoryDoesNotRecreateADeletedCacheDirectory() = fixture { _, store ->
        val missing = store.storageDirectory.resolve("missing")
        assertThrows(IllegalStateException::class.java) { FileRepository(missing, createRoot = false) }
        assertFalse(missing.exists())
    }

    @Test fun cleanupPreservesSourceAndDependencyTreesByteForByte() = fixture { root, store ->
        val paths = listOf("src/main.kt", "node_modules/lib/index.js", ".venv/bin/python", "target/debug/program", ".gradle/cache.bin")
        paths.forEach { root.resolve(it).apply { parentFile.mkdirs(); writeText("original:$it") } }
        val originals = paths.associateWith { root.resolve(it).readBytes().toList() }
        val ref = ByteArrayInputStream("chat copy".toByteArray()).use { store.import("session", "clip.mp4", "video/mp4", it) }
        store.releaseOwner("session")
        store.cleanupExpired(ref.expiresAt)
        paths.forEach { assertEquals(originals[it], root.resolve(it).readBytes().toList()) }
        assertFalse(root.resolve(".droide/chat-media").exists())
    }
}
