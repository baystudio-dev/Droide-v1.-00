package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceFileFingerprintCacheTest {
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("workspace-fingerprint-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    // Clock injection lets unit tests exercise eligibility without sleeps. The integration
    // measurement separately uses the real clock and actual timestamps.
    private fun oldEnoughClock() = System.currentTimeMillis() + 30_000L

    @Test fun unchangedFileReusesHashButForcedMutationGuardReadsAgain() = fixture { root ->
        val file = File(root, "app.apk").apply { writeBytes(ByteArray(256 * 1024) { it.toByte() }) }
        var bytes = 0L
        val cache = WorkspaceFileFingerprintCache(nowMillis = { oldEnoughClock() },
            onHashRead = { _, count -> bytes += count })
        val first = cache.read(file.toPath(), currentCoroutineContext())
        assertEquals(file.length(), bytes)
        assertEquals(first.sha256, cache.read(file.toPath(), currentCoroutineContext()).sha256)
        assertEquals("Warm inventory must not reread content", file.length(), bytes)
        cache.read(file.toPath(), currentCoroutineContext(), allowReuse = false)
        assertEquals("Publication guard must still read content", 2 * file.length(), bytes)
    }

    @Test fun sameSizeEditWithRestoredMtimeInvalidatesUsingCtime() = fixture { root ->
        val file = File(root, "asset.glb").apply { writeText("first!") }
        val modified = Files.getLastModifiedTime(file.toPath())
        var reads = 0
        val cache = WorkspaceFileFingerprintCache(nowMillis = { oldEnoughClock() }, onHashRead = { _, _ -> reads++ })
        val original = cache.read(file.toPath(), currentCoroutineContext())
        file.writeText("second")
        Files.setLastModifiedTime(file.toPath(), modified)
        val changed = cache.read(file.toPath(), currentCoroutineContext())
        assertNotEquals(original.sha256, changed.sha256)
        assertEquals(2, reads)
    }

    @Test fun sameTickObservationCannotBecomeTrustedJustByAging() = fixture { root ->
        val file = File(root, "same-size").apply { writeText("before") }
        val modified = FileTime.fromMillis(1_000)
        Files.setLastModifiedTime(file.toPath(), modified)
        val change = FileTime.fromMillis(10_000)
        var clock = 10_500L
        var reads = 0
        val cache = WorkspaceFileFingerprintCache(nowMillis = { clock }, changedTime = { change },
            onHashRead = { _, _ -> reads++ })
        val first = cache.read(file.toPath(), currentCoroutineContext())
        file.writeText("after!")
        Files.setLastModifiedTime(file.toPath(), modified)
        clock = 20_000L
        val after = cache.read(file.toPath(), currentCoroutineContext())
        assertNotEquals(first.sha256, after.sha256)
        assertEquals("Racy first observation must be hashed again after aging", 2, reads)
        assertEquals(after.sha256, cache.read(file.toPath(), currentCoroutineContext()).sha256)
        assertEquals(2, reads)
    }

    @Test fun longHashCannotPromoteRecentTimestampUsingItsFinishTime() = fixture { root ->
        val file = File(root, "file").apply { writeText("before") }
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_000))
        var clock = 10_500L
        var reads = 0
        val cache = WorkspaceFileFingerprintCache(nowMillis = { clock }, changedTime = { FileTime.fromMillis(10_000) },
            onHashRead = { _, _ -> reads++; clock = 20_000 })
        cache.read(file.toPath(), currentCoroutineContext())
        cache.read(file.toPath(), currentCoroutineContext())
        assertEquals(2, reads)
    }

    @Test fun unavailableCtimeNeverUsesSizeAndMtimeAsAuthority() = fixture { root ->
        val file = File(root, "file").apply { writeText("before") }
        val modified = Files.getLastModifiedTime(file.toPath())
        var reads = 0
        val cache = WorkspaceFileFingerprintCache(nowMillis = { oldEnoughClock() }, changedTime = { null },
            onHashRead = { _, _ -> reads++ })
        val first = cache.read(file.toPath(), currentCoroutineContext())
        file.writeText("after!")
        Files.setLastModifiedTime(file.toPath(), modified)
        val after = cache.read(file.toPath(), currentCoroutineContext())
        assertNotEquals(first.sha256, after.sha256)
        cache.read(file.toPath(), currentCoroutineContext())
        assertEquals(3, reads)
    }

    @Test fun atomicReplacementAndExecutableChangeInvalidateCachedStamp() = fixture { root ->
        val file = File(root, "main").apply { writeText("first!") }
        assertTrue(file.setExecutable(false, false))
        val modified = Files.getLastModifiedTime(file.toPath())
        var reads = 0
        val cache = WorkspaceFileFingerprintCache(nowMillis = { oldEnoughClock() }, onHashRead = { _, _ -> reads++ })
        val first = cache.read(file.toPath(), currentCoroutineContext())
        val replacement = File(root, "new").apply { writeText("second") }
        Files.setLastModifiedTime(replacement.toPath(), modified)
        Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val second = cache.read(file.toPath(), currentCoroutineContext())
        assertNotEquals(first.sha256, second.sha256)
        assertTrue(file.setExecutable(true, true))
        val executable = cache.read(file.toPath(), currentCoroutineContext())
        assertTrue(executable.executable)
        assertEquals(second.sha256, executable.sha256)
        assertEquals(3, reads)
    }

    @Test fun clockRollbackDiscardsPreviouslyCachedObservation() = fixture { root ->
        val file = File(root, "file").apply { writeText("content") }
        var clock = oldEnoughClock()
        var reads = 0
        val cache = WorkspaceFileFingerprintCache(nowMillis = { clock }, onHashRead = { _, _ -> reads++ })
        cache.read(file.toPath(), currentCoroutineContext())
        clock -= 100_000
        cache.read(file.toPath(), currentCoroutineContext())
        assertEquals(2, reads)
    }

    @Test fun cancelledVerificationCannotRetainAnEarlierCacheEntry() = fixture { root ->
        val file = File(root, "file").apply { writeBytes(ByteArray(128 * 1024)) }
        val job = Job()
        var cancelDuringRead = false
        var bytes = 0L
        val cache = WorkspaceFileFingerprintCache(nowMillis = { oldEnoughClock() }, onHashRead = { _, count ->
            bytes += count
            if (cancelDuringRead) job.cancel()
        })
        cache.read(file.toPath(), currentCoroutineContext())
        cancelDuringRead = true
        try {
            cache.read(file.toPath(), currentCoroutineContext() + job, allowReuse = false)
            fail("Cancellation was ignored")
        } catch (_: CancellationException) { }
        cancelDuringRead = false
        val before = bytes
        cache.read(file.toPath(), currentCoroutineContext())
        assertEquals(file.length(), bytes - before)
    }

    @Test fun changingFileDuringHashRejectsAndDoesNotCacheIt() = fixture { root ->
        val file = File(root, "file").apply { writeBytes(ByteArray(128 * 1024)) }
        var mutate = true
        var bytes = 0L
        val cache = WorkspaceFileFingerprintCache(nowMillis = { oldEnoughClock() }, onHashRead = { _, count ->
            bytes += count
            if (mutate) { mutate = false; file.writeBytes(ByteArray(128 * 1024) { 7 }) }
        })
        try { cache.read(file.toPath(), currentCoroutineContext()); fail("Concurrent writer was ignored") }
        catch (_: IllegalStateException) { }
        val before = bytes
        cache.read(file.toPath(), currentCoroutineContext())
        assertEquals(file.length(), bytes - before)
    }

    @Test fun boundedCacheEvictsWithoutLosingCorrectness() = fixture { root ->
        val paths = (1..3).map { File(root, "file-$it").apply { writeText("value-$it") }.toPath() }
        val reads = mutableMapOf<Path, Int>()
        val cache = WorkspaceFileFingerprintCache(maxEntries = 2, nowMillis = { oldEnoughClock() },
            onHashRead = { path, _ -> reads[path] = (reads[path] ?: 0) + 1 })
        paths.forEach { cache.read(it, currentCoroutineContext()) }
        cache.read(paths[2], currentCoroutineContext())
        cache.read(paths[0], currentCoroutineContext())
        assertEquals(1, reads[paths[2]])
        assertEquals(2, reads[paths[0]])
    }

    @Test fun cachedFileReplacedBySymlinkCannotBypassOrdinaryFileCheck() = fixture { root ->
        val file = File(root, "file").apply { writeText("original") }
        val other = File(root, "other").apply { writeText("private") }
        val cache = WorkspaceFileFingerprintCache(nowMillis = { oldEnoughClock() })
        cache.read(file.toPath(), currentCoroutineContext())
        Files.delete(file.toPath())
        Files.createSymbolicLink(file.toPath(), other.toPath())
        try { cache.read(file.toPath(), currentCoroutineContext()); fail("Symlink reused a cached fingerprint") }
        catch (_: IllegalArgumentException) { }
        assertEquals("private", other.readText())
        assertTrue(Files.exists(file.toPath(), NOFOLLOW_LINKS))
    }
}
