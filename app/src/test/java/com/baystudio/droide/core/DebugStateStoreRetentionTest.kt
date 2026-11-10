package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DebugStateStoreRetentionTest {
    private val original = """{"version":1,"breakpoints":{"Main.kt":[4]},"watches":["kept"]}""".toByteArray()
    private val next = DebugPersistentState(mapOf("Main.kt" to listOf(8)), listOf("updated"))

    private fun fixture(block: suspend CoroutineScope.(File, File) -> Unit) = runBlocking<Unit> {
        val root = Files.createTempDirectory("droide-debug-retention-").toFile()
        try { block(root, File(root, "project.bin")) }
        finally { root.deleteRecursively() }
    }

    private fun store(
        root: File,
        id: String = "project",
        encrypt: (ByteArray, ByteArray) -> ByteArray = { bytes, _ -> bytes },
        decrypt: (ByteArray, ByteArray) -> ByteArray = { bytes, _ -> bytes },
    ) = DebugStateStore(root, id, encrypt, decrypt)

    @Test fun transientDecryptFailurePreservesBytesAndBlocksAutosave() = fixture { root, file ->
        file.writeBytes(original)
        val encrypted = AtomicInteger()
        val value = store(root, encrypt = { bytes, _ -> encrypted.incrementAndGet(); bytes },
            decrypt = { _, _ -> throw IOException("temporary cipher failure") })
        assertTrue(runCatching { value.load() }.exceptionOrNull() is DebugStateUnavailableException)
        assertFalse(value.status.value.writable)
        assertNotNull(value.status.value.message)
        assertTrue(runCatching { value.save(next) }.exceptionOrNull() is DebugStateUnavailableException)
        assertEquals(0, encrypted.get())
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun failedLoadIsLatchedUntilExplicitRetry() = fixture { root, file ->
        file.writeBytes(original)
        val calls = AtomicInteger()
        val value = store(root, decrypt = { bytes, _ ->
            if (calls.incrementAndGet() == 1) throw IOException("temporary cipher failure")
            bytes
        })
        assertTrue(runCatching { value.load() }.isFailure)
        assertTrue(runCatching { value.load() }.isFailure)
        assertEquals(1, calls.get())
        var restored: DebugPersistentState? = null
        value.restore(retry = true) { saved ->
            assertFalse(value.status.value.writable)
            restored = saved
        }
        assertEquals(listOf("kept"), restored?.watches)
        assertEquals(mapOf("Main.kt" to listOf(4)), restored?.breakpoints)
        assertTrue(value.status.value.writable)
        assertNull(value.status.value.message)
        value.save(next)
        assertEquals(next, value.load())
    }

    @Test fun malformedAndUnsupportedStateRemainUnavailable() = fixture { root, _ ->
        listOf("not-json", "[]", "{\"version\":9}", "{\"version\":1,\"watches\":[{}]}",
            "{\"version\":1,\"breakpoints\":[],\"watches\":{}}",
            "{\"version\":1,\"breakpoints\":{\"Main.kt\":{}},\"watches\":[]}",
            "{\"version\":1,\"breakpoints\":{\"Main.kt\":[\"4\"]},\"watches\":[]}",
            "{\"version\":1,\"breakpoints\":{},\"watches\":[true]}",
            "{\"version\":1,\"breakpoints\":{},\"watches\":[\"\"]}").forEachIndexed { index, text ->
            val id = "invalid-$index"
            val file = File(root, "$id.bin").apply { writeText(text) }
            val before = file.readBytes()
            val value = store(root, id)
            assertTrue(runCatching { value.load() }.isFailure)
            assertTrue(runCatching { value.save(next) }.isFailure)
            assertFalse(value.status.value.writable)
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun firstAbsenceCanInitializeAndReadNormalState() = fixture { root, file ->
        val value = store(root)
        assertNull(value.load())
        assertTrue(value.status.value.writable)
        value.save(next)
        assertTrue(file.isFile)
        assertEquals(next, value.load())
    }

    @Test fun saveBeforeLoadValidatesExistingUnreadableState() = fixture { root, file ->
        file.writeBytes(original)
        val value = store(root, decrypt = { _, _ -> throw IOException("unavailable") })
        assertTrue(runCatching { value.save(next) }.isFailure)
        assertFalse(value.status.value.writable)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun liveInstancesShareFailureLatchButDifferentProjectsDoNot() = fixture { root, file ->
        file.writeBytes(original)
        val first = store(root)
        assertNotNull(first.load())
        val second = store(root, decrypt = { _, _ -> throw IOException("transient") })
        assertTrue(runCatching { second.load() }.isFailure)
        assertFalse(first.status.value.writable)
        assertTrue(runCatching { first.save(next) }.isFailure)
        assertArrayEquals(original, file.readBytes())
        val other = store(root, "other")
        other.save(next)
        assertEquals(next, other.load())
    }

    @Test fun queuedSaveCannotPublishAfterConcurrentLoadFailure() = fixture { root, file ->
        file.writeBytes(original)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val encrypted = AtomicInteger()
        val reader = store(root, decrypt = { _, _ ->
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            throw IOException("transient")
        })
        val writer = store(root, encrypt = { bytes, _ -> encrypted.incrementAndGet(); bytes })
        val reading = async(Dispatchers.Default) { runCatching { reader.load() } }
        var saving: Deferred<Result<Unit>>? = null
        val writerStarted = AtomicBoolean()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val queued = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                writerStarted.set(true)
                runCatching { writer.save(next) }
            }
            saving = queued
            assertTrue(writerStarted.get())
            assertFalse(queued.isCompleted)
            release.countDown()
            assertTrue(reading.await().isFailure)
            assertTrue(queued.await().isFailure)
            assertEquals(0, encrypted.get())
            assertArrayEquals(original, file.readBytes())
        } finally {
            release.countDown()
            reading.cancel(); saving?.cancel()
            withTimeout(4_000) { listOfNotNull(reading, saving).joinAll() }
        }
    }

    @Test fun failedReadDoesNotDeleteAReplacementAtTheSamePath() = fixture { root, file ->
        file.writeBytes(original)
        val replacement = original + " ".toByteArray()
        val value = store(root, decrypt = { _, _ ->
            val staged = File(root, "replacement.bin").apply { writeBytes(replacement) }
            Files.move(staged.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            throw IOException("read of previous bytes failed")
        })
        assertTrue(runCatching { value.load() }.isFailure)
        assertArrayEquals(replacement, file.readBytes())
        assertTrue(runCatching { value.save(next) }.isFailure)
        assertArrayEquals(replacement, file.readBytes())
    }

    @Test fun missingAfterFailedLoadDoesNotBecomeFirstAbsence() = fixture { root, file ->
        file.writeBytes(original)
        val value = store(root, decrypt = { _, _ -> throw IOException("transient") })
        assertTrue(runCatching { value.load() }.isFailure)
        val retained = File(root, "retained.bin")
        Files.move(file.toPath(), retained.toPath())
        assertTrue(runCatching { value.restore(retry = true) {} }.isFailure)
        assertTrue(runCatching { value.save(next) }.isFailure)
        assertFalse(file.exists())
        assertArrayEquals(original, retained.readBytes())
    }

    @Test fun cancellationPreservesBytesAndDoesNotPoisonFutureLoad() = fixture { root, file ->
        file.writeBytes(original)
        val cancel = AtomicBoolean(true)
        val value = store(root, decrypt = { bytes, _ ->
            if (cancel.getAndSet(false)) throw CancellationException("cancelled restore")
            bytes
        })
        assertTrue(runCatching { value.load() }.exceptionOrNull() is CancellationException)
        assertArrayEquals(original, file.readBytes())
        assertNotNull(value.load())
        assertTrue(value.status.value.writable)
    }

    @Test fun fatalReadFailureIsRethrownWithoutDeletingOrAdmittingSave() = fixture { root, file ->
        file.writeBytes(original)
        val fatal = AssertionError("simulated codec failure")
        val value = store(root, decrypt = { _, _ -> throw fatal })
        assertSame(fatal, runCatching { value.load() }.exceptionOrNull())
        assertTrue(runCatching { value.save(next) }.isFailure)
        assertFalse(value.status.value.writable)
        assertArrayEquals(original, file.readBytes())
    }
    @Test fun valuesOutsideWriterLimitsRemainUnreadableAndRetained() = fixture { root, _ ->
        val cases = listOf(
            "{\"version\":1,\"breakpoints\":{\"Main.kt\":[0]},\"watches\":[]}",
            "{\"version\":1,\"breakpoints\":{\"../outside.kt\":[1]},\"watches\":[]}",
            "{\"version\":1,\"breakpoints\":{},\"watches\":[" + List(201) { "\"watch$it\"" }.joinToString() + "]}",
            "{\"version\":1,\"breakpoints\":{},\"watches\":[\"" + "x".repeat(20_001) + "\"]}",
            "{\"version\":1,\"breakpoints\":{\"Main.kt\":[" + (1..257).joinToString() + "]},\"watches\":[]}",
            "{\"version\":1,\"breakpoints\":{" + (1..257).joinToString { "\"file$it.kt\":[1]" } + "},\"watches\":[]}",
            "{\"version\":1,\"breakpoints\":{" + (1..8).joinToString { "\"file$it.kt\":[" + (1..251).joinToString() + "]" } + "},\"watches\":[]}",
        )
        cases.forEachIndexed { index, text ->
            val id = "out-of-bounds-$index"
            val file = File(root, "$id.bin").apply { writeText(text) }
            val before = file.readBytes()
            val value = store(root, id)
            assertTrue(runCatching { value.load() }.isFailure)
            assertTrue(runCatching { value.save(next) }.isFailure)
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun unknownFieldsDoNotInvalidateNormalVersionOneState() = fixture { root, file ->
        file.writeText(original.toString(Charsets.UTF_8).dropLast(1) + ",\"extra\":{\"future\":true}}")
        val value = store(root)
        assertEquals(listOf("kept"), value.load()?.watches)
        assertTrue(value.status.value.writable)
    }

}
