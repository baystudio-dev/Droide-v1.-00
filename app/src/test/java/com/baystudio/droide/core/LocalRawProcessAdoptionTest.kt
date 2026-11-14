package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual child handles; no Android/PRoot or descendant-quiescence claim. */
class LocalRawProcessAdoptionTest {
    private fun child() = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test fun wrapperFailureReapsRawChildButNeverConfirmsWorkspaceCustody() = runBlocking {
        val root = Files.createTempDirectory("raw-adoption-").toFile()
        val scope = scope(); val child = child(); var fallback: HostedStdioProcess? = null
        val closes = AtomicInteger(); val leaseCleanup = AtomicInteger()
        val custody = object : WorkspaceProcessCustody() {
            override fun acquire(root: File) = object : Receipt() {
                override fun enterFactory() = Unit
                override fun confirmCleanupOrAbort() { closes.incrementAndGet() }
            }
        }
        try {
            assertTrue(runCatching {
                WorkspaceProcessWriteAdmission.create(root, custody) {
                    createOwnedLocalHostedProcess({ child }, scope, { leaseCleanup.incrementAndGet() },
                        wrap = { _, _, _ -> error("wrapper failed after spawn") }, retainFailure = { fallback = it })
                }
            }.isFailure)
            assertTrue(child.isAlive)
            assertTrue(runCatching { fallback!!.awaitExit() }.isFailure)
            assertFalse(child.isAlive)
            assertEquals(0, leaseCleanup.get()) // partial wrapper alone owns lease cleanup
            assertEquals(0, closes.get())
            assertTrue(runCatching { ExportWriterAdmission.beginCapture(root).close() }.isFailure)
            var entered = false
            assertTrue(runCatching { WorkspaceProcessWriteAdmission.create(root) { entered = true; error("not reached") } }.isFailure)
            assertFalse(entered)
            val siblingRoot = Files.createTempDirectory("raw-adoption-sibling-").toFile()
            try {
                val sibling = WorkspaceProcessWriteAdmission.create(siblingRoot) {
                    createOwnedLocalHostedProcess(::child, scope, {})
                }
                assertTrue(sibling.isAlive)
                sibling.close(); sibling.awaitExit()
                WorkspaceProcessWriteAdmission.confirmCompletion(sibling)
                ExportWriterAdmission.beginCapture(siblingRoot).close()
                assertTrue(runCatching { ExportWriterAdmission.beginCapture(root).close() }.isFailure)
            } finally { siblingRoot.deleteRecursively() }
        } finally { child.destroyForcibly(); child.waitFor(); scope.cancel(); root.deleteRecursively() }
    }

    @Test fun failedNativeTerminationCanRetrySameHandleWithoutTouchingSibling() = runBlocking {
        val raw = child(); val sibling = child(); val scope = scope(); var fallback: HostedStdioProcess? = null
        val attempts = AtomicInteger()
        val process = object : Process() {
            override fun getOutputStream() = raw.outputStream
            override fun getInputStream() = raw.inputStream
            override fun getErrorStream() = raw.errorStream
            override fun waitFor() = raw.waitFor()
            override fun waitFor(timeout: Long, unit: TimeUnit) = raw.waitFor(timeout, unit)
            override fun exitValue() = raw.exitValue()
            override fun isAlive() = raw.isAlive
            override fun destroy() { if (attempts.incrementAndGet() == 1) error("injected termination failure"); raw.destroy() }
            override fun destroyForcibly(): Process { raw.destroyForcibly(); return this }
        }
        try {
            runCatching { createOwnedLocalHostedProcess({ process }, scope, {},
                wrap = { _, _, _ -> error("wrapper") }, retainFailure = { fallback = it }) }
            assertTrue(runCatching { fallback!!.awaitExit() }.isFailure)
            assertTrue(raw.isAlive); assertTrue(sibling.isAlive)
            assertTrue(runCatching { fallback!!.awaitExit() }.isFailure)
            assertFalse(raw.isAlive); assertTrue(sibling.isAlive)
            assertEquals(2, attempts.get())
            assertTrue(runCatching { fallback!!.awaitExit() }.isFailure)
            assertEquals(2, attempts.get())
        } finally { raw.destroyForcibly(); sibling.destroyForcibly(); raw.waitFor(); sibling.waitFor(); scope.cancel() }
    }

    @Test fun cancellationAfterSpawnRetainsCleanupOutsideCancelledCaller() = runBlocking {
        val raw = child(); val scope = scope(); var fallback: HostedStdioProcess? = null
        try {
            val job = launch {
                runCatching { createOwnedLocalHostedProcess({ raw }, scope, {},
                    wrap = { _, _, _ -> throw CancellationException("cancelled wrapper") }, retainFailure = { fallback = it }) }
                cancel()
                assertTrue(runCatching { fallback!!.awaitExit() }.isFailure)
            }
            job.join()
            assertFalse(raw.isAlive)
        } finally { raw.destroyForcibly(); raw.waitFor(); scope.cancel() }
    }

    @Test fun concurrentRetriesSerializeNativeCleanup() = runBlocking {
        val raw = child(); val scope = scope(); var fallback: HostedStdioProcess? = null
        try {
            runCatching { createOwnedLocalHostedProcess({ raw }, scope, {},
                wrap = { _, _, _ -> error("wrapper") }, retainFailure = { fallback = it }) }
            coroutineScope { (1..12).map { launch(Dispatchers.Default) {
                assertTrue(runCatching { fallback!!.awaitExit() }.isFailure)
            } }.joinAll() }
            assertFalse(raw.isAlive)
        } finally { raw.destroyForcibly(); raw.waitFor(); scope.cancel() }
    }

    @Test fun failureAfterWrapperWorkersStartStillNeverCertifiesFallback() = runBlocking {
        val raw = child(); val scope = scope(); var fallback: HostedStdioProcess? = null
        var partial: LocalHostedProcess? = null
        val leaseCalls = AtomicInteger()
        try {
            runCatching { createOwnedLocalHostedProcess({ raw }, scope, { leaseCalls.incrementAndGet() },
                wrap = { process, ownerScope, cleanup ->
                    partial = LocalHostedProcess(process, ownerScope, cleanup)
                    error("failure after lifecycle startup")
                }, retainFailure = { fallback = it }) }
            assertTrue(runCatching { fallback!!.awaitExit() }.isFailure)
            assertFalse(raw.isAlive)
            withTimeout(5_000) { partial!!.awaitExit() }
            assertEquals(1, leaseCalls.get())
            assertTrue(runCatching { fallback!!.awaitExit() }.isFailure)
            assertEquals(1, leaseCalls.get())
        } finally { partial?.close(); raw.destroyForcibly(); raw.waitFor(); scope.cancel() }
    }

    @Test fun blockedNativeAttemptRemainsTrackedWithoutDuplicateRetry() = runBlocking {
        val raw = child(); val scope = scope(); var fallback: HostedStdioProcess? = null
        val release = java.util.concurrent.CountDownLatch(1)
        val attempts = AtomicInteger()
        val process = object : Process() {
            override fun getOutputStream() = raw.outputStream
            override fun getInputStream() = raw.inputStream
            override fun getErrorStream() = raw.errorStream
            override fun waitFor() = raw.waitFor()
            override fun waitFor(timeout: Long, unit: TimeUnit) = raw.waitFor(timeout, unit)
            override fun exitValue() = raw.exitValue()
            override fun isAlive() = raw.isAlive
            override fun destroy() { attempts.incrementAndGet(); release.await(); raw.destroy() }
            override fun destroyForcibly(): Process { raw.destroyForcibly(); return this }
        }
        try {
            runCatching { createOwnedLocalHostedProcess({ process }, scope, {},
                wrap = { _, _, _ -> error("wrapper") }, retainFailure = { fallback = it }) }
            assertTrue(runCatching { withTimeout(4_000) { fallback!!.awaitExit() } }.exceptionOrNull() is TimeoutCancellationException)
            assertTrue(raw.isAlive)
            assertTrue(runCatching { withTimeout(4_000) { fallback!!.awaitExit() } }.exceptionOrNull() is TimeoutCancellationException)
            assertEquals(1, attempts.get())
            release.countDown()
            assertTrue(runCatching { fallback!!.awaitExit() }.isFailure)
            assertFalse(raw.isAlive)
            assertEquals(1, attempts.get())
        } finally { release.countDown(); raw.destroyForcibly(); raw.waitFor(); scope.cancel() }
    }

    @Test fun successfulWrapperDoesNotPublishFallbackOrChangeLifecycle() = runBlocking {
        val raw = child(); val scope = scope(); var retained = false
        try {
            val hosted = createOwnedLocalHostedProcess({ raw }, scope, {}, retainFailure = { retained = true })
            assertFalse(retained); assertTrue(raw.isAlive)
            hosted.close(); hosted.awaitExit(); assertFalse(raw.isAlive)
        } finally { raw.destroyForcibly(); raw.waitFor(); scope.cancel() }
    }

    @Test fun startFailurePreservesOriginalWithoutConstructingOrPublishingFallback() {
        val scope = scope()
        val original = IllegalStateException("start failed before returning a handle")
        val starts = AtomicInteger()
        val wraps = AtomicInteger()
        val retained = AtomicInteger()
        try {
            val failure = runCatching {
                createOwnedLocalHostedProcess(
                    start = { starts.incrementAndGet(); throw original },
                    scope = scope,
                    cleanup = { error("cleanup must not run without a returned handle") },
                    wrap = { _, _, _ -> wraps.incrementAndGet(); error("wrapper must not run") },
                    retainFailure = { retained.incrementAndGet() },
                )
            }.exceptionOrNull()
            assertSame(original, failure)
            assertEquals(1, starts.get())
            assertEquals(0, wraps.get())
            assertEquals(0, retained.get())
        } finally { scope.cancel() }
    }

    @Test fun wrapperFailurePreservesExactErrorAndAdoptsSingleReturnedChild() = runBlocking {
        val scope = scope()
        val original = IllegalStateException("reviewed wrapper construction failed")
        val starts = AtomicInteger()
        val wraps = AtomicInteger()
        val retained = AtomicInteger()
        val cleanupCalls = AtomicInteger()
        val cleanup: () -> Unit = { cleanupCalls.incrementAndGet(); Unit }
        var raw: Process? = null
        var fallback: HostedStdioProcess? = null
        try {
            val failure = runCatching {
                createOwnedLocalHostedProcess(
                    start = { starts.incrementAndGet(); child().also { raw = it } },
                    scope = scope,
                    cleanup = cleanup,
                    wrap = { returned, ownerScope, ownerCleanup ->
                        wraps.incrementAndGet()
                        assertSame(raw, returned)
                        assertSame(scope, ownerScope)
                        assertSame(cleanup, ownerCleanup)
                        throw original
                    },
                    retainFailure = { retained.incrementAndGet(); fallback = it },
                )
            }.exceptionOrNull()
            assertSame(original, failure)
            assertEquals(1, starts.get())
            assertEquals(1, wraps.get())
            assertEquals(1, retained.get())
            assertTrue(raw!!.isAlive)
            assertTrue(runCatching { fallback!!.awaitExit() }.isFailure)
            assertFalse(raw!!.isAlive)
            assertTrue(runCatching { fallback!!.awaitExit() }.isFailure)
            assertEquals(1, starts.get())
            assertEquals(0, cleanupCalls.get())
        } finally { raw?.destroyForcibly(); raw?.waitFor(); scope.cancel() }
    }
}
