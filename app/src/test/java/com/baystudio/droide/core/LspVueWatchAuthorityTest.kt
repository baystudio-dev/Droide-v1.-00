package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LspVueWatchAuthorityTest {
    private fun fixture(block: suspend CoroutineScope.(File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("vue-watch-").toFile().canonicalFile
        try { block(root) } finally { coroutineContext.cancelChildren(); root.deleteRecursively() }
    }
    @Test fun actualVueAndTsxJsxBytesAreObservedAndFirstDifferenceIsImmediate() = fixture { root ->
        listOf("A.vue", "dep.tsx", "dep.jsx", "data.jsonc", "view.css").forEach { File(root, it).writeText("saved") }
        val poller = LspVueFilePoller(root); val before = poller.scan()
        assertTrue(before.entries.keys.containsAll(listOf("A.vue", "dep.tsx", "dep.jsx", "data.jsonc", "view.css")))
        File(root, "dep.tsx").writeText("dirty persisted")
        var callbacks = 0
        val after = poller.scan(before) { callbacks++; assertNotEquals(it.before, it.after) }
        assertEquals(1, callbacks); assertNotEquals(before.aggregateDigest, after.aggregateDigest)
    }
    @Test fun boundedAndSymlinkCoverageFailClosed() = fixture { root ->
        File(root, "a.vue").writeText("12345")
        assertTrue(runCatching { LspVueFilePoller(root, LspVueFilePoller.Limits(maxFileBytes = 4)).scan() }.isFailure)
        assertTrue(runCatching { LspVueFilePoller(root, LspVueFilePoller.Limits(maxEntries = 1)).scan() }.isFailure)
        Files.createSymbolicLink(File(root, "link.ts").toPath(), File(root, "a.vue").toPath())
        assertTrue(runCatching { LspVueFilePoller(root).scan() }.isFailure)
    }
    @Test(timeout = 10000) fun identicalScansRenewSameTokenWithoutInvalidatingResults() = fixture { root ->
        File(root, "a.vue").writeText("saved")
        val invalidations = AtomicInteger(); val authority = LspVueWatchAuthority(root, this, { invalidations.incrementAndGet() })
        val token = authority.start(); val firstVerified = token.verifiedNanos
        delay(3400)
        assertTrue(authority.isCurrent(token)); assertTrue(token.verifiedNanos > firstVerified)
        assertEquals(0, invalidations.get()); authority.close(); assertFalse(authority.isCurrent(token))
    }
    @Test fun expiryIsPureAndCannotRenewCapturedTokenItself() = fixture { root ->
        val now = AtomicLong(1_000_000); File(root, "a.vue").writeText("saved")
        val authority = LspVueWatchAuthority(root, this, {}, now::get)
        val token = authority.start(); val old = token.verifiedNanos
        now.addAndGet(20_000_000_001L)
        assertFalse(authority.isCurrent(token)); assertEquals(old, token.verifiedNanos)
        authority.close()
    }
    @Test fun callbackRunsOutsideObservationLockAndOnlyOnce() = fixture { root ->
        File(root, "a.vue").writeText("saved")
        val callbacks = AtomicInteger(); lateinit var authority: LspVueWatchAuthority
        lateinit var token: LspVueWatchAuthority.Observation
        authority = LspVueWatchAuthority(root, this, {
            callbacks.incrementAndGet(); val checked = CountDownLatch(1)
            val thread = Thread { assertFalse(authority.isCurrent(token)); checked.countDown() }
            thread.start(); assertTrue(checked.await(1, TimeUnit.SECONDS)); thread.join()
        })
        token = authority.start(); authority.invalidate("authored difference"); authority.invalidate("duplicate")
        assertEquals(1, callbacks.get()); authority.close()
    }
}
