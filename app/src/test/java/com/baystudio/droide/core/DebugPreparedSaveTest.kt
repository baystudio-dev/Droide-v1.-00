package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DebugPreparedSaveTest {
    private fun state(vararg watches: String) = DebugPersistentState(emptyMap(), watches.toList())
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("debug-prepared-save-").toFile()
        try { withTimeout(10_000) { block(root) } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun store(root: File, id: String = "p", encrypt: (ByteArray, ByteArray) -> ByteArray = { b, _ -> b }) =
        DebugStateStore(root, id, encrypt, { b, _ -> b })

    @Test fun reversedWritesAcrossStoreInstancesKeepLatestIntent() = fixture { root ->
        var writes = 0; val first = store(root, encrypt = { b, _ -> writes++; b }); val second = store(root, encrypt = { b, _ -> writes++; b })
        first.load()
        val old = first.prepareSave(state("obsolete")); val newest = second.prepareSave(state())
        second.savePrepared(newest); first.savePrepared(old)
        assertTrue(first.load()!!.watches.isEmpty()); assertEquals(1, writes)
    }

    @Test fun manySupersededSnapshotsDoNotEncryptOrPublish() = fixture { root ->
        var writes = 0; val store = store(root, encrypt = { b, _ -> writes++; b }); store.load()
        val requests = (0..99).map { store.prepareSave(state("w$it")) }
        for (r in requests.reversed()) store.savePrepared(r)
        assertEquals(listOf("w99"), store.load()!!.watches); assertEquals(1, writes)
    }

    @Test fun snapshotOwnsCopiesOfMutableCollections() = fixture { root ->
        val store = store(root); store.load()
        val lines = mutableListOf(1); val breakpoints = mutableMapOf<String, List<Int>>("main.kt" to lines); val watches = mutableListOf("original")
        val request = store.prepareSave(DebugPersistentState(breakpoints, watches))
        lines[0] = 9; breakpoints.clear(); watches[0] = "changed"
        store.savePrepared(request)
        assertEquals(DebugPersistentState(mapOf("main.kt" to listOf(1)), listOf("original")), store.load())
    }

    @Test fun foreignProjectRequestIsRejectedWithoutChangingEitherFile() = fixture { root ->
        val first = store(root, "first"); val second = store(root, "second")
        first.save(state("first")); second.save(state("second"))
        val a = root.resolve("first.bin").readBytes(); val b = root.resolve("second.bin").readBytes()
        val request = first.prepareSave(state("new"))
        try { second.savePrepared(request); fail("Foreign request must fail") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(a, root.resolve("first.bin").readBytes()); assertArrayEquals(b, root.resolve("second.bin").readBytes())
    }

    @Test fun independentProjectSavesDoNotSupersedeEachOther() = fixture { root ->
        val first = store(root, "first"); val second = store(root, "second")
        val a = first.prepareSave(state("a")); val b = second.prepareSave(state("b"))
        first.savePrepared(a); second.savePrepared(b)
        assertEquals(listOf("a"), first.load()!!.watches); assertEquals(listOf("b"), second.load()!!.watches)
    }

    @Test fun failedNewestWriteNeverAuthorizesOlderFallback() = fixture { root ->
        var failWrite = false; var writes = 0
        val store = store(root, encrypt = { b, _ -> writes++; if (failWrite) throw IOException("Unavailable"); b })
        store.save(state("baseline")); val original = root.resolve("p.bin").readBytes()
        val old = store.prepareSave(state("obsolete")); val latest = store.prepareSave(state())
        failWrite = true
        try { store.savePrepared(latest); fail("Write must fail") } catch (_: IOException) { }
        failWrite = false; store.savePrepared(old)
        assertEquals(2, writes); assertArrayEquals(original, root.resolve("p.bin").readBytes())
        store.savePrepared(latest); assertTrue(store.load()!!.watches.isEmpty())
    }

    @Test fun observedUnreadableStateBlocksAnAlreadyPreparedWrite() = fixture { root ->
        val store = store(root); store.save(state("baseline")); val pending = store.prepareSave(state("new"))
        val corrupt = "unreadable".toByteArray(); root.resolve("p.bin").writeBytes(corrupt)
        try { store.load(); fail("Load must fail closed") } catch (_: DebugStateUnavailableException) { }
        try { store.savePrepared(pending); fail("Prepared write must stay blocked") } catch (_: DebugStateUnavailableException) { }
        assertArrayEquals(corrupt, root.resolve("p.bin").readBytes())
    }

    @Test fun newAdmissionDuringEncryptionCommitsAfterTheExistingWriter() = fixture { root -> coroutineScope {
        val started = CountDownLatch(1); val release = CountDownLatch(1); var hold = true
        val store = store(root, encrypt = { b, _ ->
            if (hold) { hold = false; started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            b
        })
        store.load(); val old = store.prepareSave(state("old"))
        val older = async(Dispatchers.IO) { store.savePrepared(old) }
        try {
            check(started.await(5, TimeUnit.SECONDS))
            val newest = store.prepareSave(state("new")); val later = async(Dispatchers.IO) { store.savePrepared(newest) }
            release.countDown(); older.await(); later.await()
            assertEquals(listOf("new"), store.load()!!.watches)
        } finally { release.countDown() }
    } }

    @Test fun cancelledPreparedWriterDoesNotReviveSupersededStateAndCanBeRetried() = fixture { root -> coroutineScope {
        val store = store(root); store.save(state("baseline")); val old = store.prepareSave(state("old")); val latest = store.prepareSave(state("new"))
        val cancelled = launch(start = CoroutineStart.LAZY) { store.savePrepared(latest) }; cancelled.cancelAndJoin()
        store.savePrepared(old); assertEquals(listOf("baseline"), store.load()!!.watches)
        store.savePrepared(latest); assertEquals(listOf("new"), store.load()!!.watches)
    } }

    @Test fun normalPublicSaveSupersedesAnEarlierPreparedRequest() = fixture { root ->
        val store = store(root); store.load(); val old = store.prepareSave(state("old"))
        store.save(state("new")); store.savePrepared(old)
        assertEquals(listOf("new"), store.load()!!.watches)
    }
}
