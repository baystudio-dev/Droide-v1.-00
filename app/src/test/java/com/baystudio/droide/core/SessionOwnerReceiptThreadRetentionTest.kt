package com.baystudio.droide.core
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionOwnerReceiptThreadRetentionTest {
    @Test(timeout = 10_000)
    fun suspendedReceiptsDoNotRetainNativeCloseThreadsOrProveCompletion() = runBlocking {
        val before = Thread.getAllStackTraces().keys.toSet()
        val owner = SessionHostedProcessOwner()
        val gate = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val closes = AtomicInteger()
        val count = 128
        val peers = List(count) { object : HostedStdioProcess {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(byteArrayOf())
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive = false
            override val exitCode = 0
            override fun close() { closes.incrementAndGet() }
            override suspend fun awaitExit(): Int { entered.incrementAndGet(); gate.await(); return 0 }
        } }
        peers.forEach(owner::retain)
        var observer: Deferred<Unit>? = null
        fun retainedThreads() = Thread.getAllStackTraces().keys.count { it !in before && it.isAlive && it.name == "droide-cli-stop" }
        try {
            observer = async { owner.stopAndAwait() }
            // Natural completion is now observed before stop too. Wait for the stop
            // requests themselves, not for the first set of receipt observers.
            withTimeout(3_000) { while (entered.get() < count || closes.get() != count) delay(5) }
            assertEquals(count, closes.get())
            assertFalse("Stop dispatch and dead state do not replace pending receipts", observer.isCompleted)
            val threadsReleased = withTimeoutOrNull(1_000) { while (retainedThreads() > 0) delay(5); true } == true
            println("THREAD_RECEIPT exact_handles=$count close_returned=${closes.get()} suspended_receipts=${entered.get()} dedicated_threads=${retainedThreads()}")
            assertTrue("Suspended receipts must not retain one native close thread per handle", threadsReleased)
            assertFalse(observer.isCompleted)
            observer.cancelAndJoin()
            observer = async { owner.stopAndAwait() }
            yield()
            assertFalse("A new observer must still await the same pending receipts", observer.isCompleted)
            assertEquals("Observer cancellation must not replay completed stop requests", count, closes.get())
            gate.complete(Unit)
            withTimeout(2_000) { observer.await() }
            peers.forEach(owner::retain)
            owner.stopAndAwait()
            assertEquals("Completed identities must remain deduplicated", count, closes.get())
        } finally { gate.complete(Unit); withTimeout(3_000) { observer?.await() } }
    }

    @Test(timeout = 5_000)
    fun receiptDispatcherFailureIsRetainedAndExplicitlyRetryable() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val closes = AtomicInteger()
        val awaits = AtomicInteger()
        val process = object : HostedStdioProcess {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(byteArrayOf())
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive = false
            override val exitCode = 0
            override fun close() { closes.incrementAndGet() }
            override suspend fun awaitExit(): Int { awaits.incrementAndGet(); return 0 }
        }
        val rejection = java.util.concurrent.RejectedExecutionException("fixture receipt dispatcher rejected")
        val rejectedScope = CoroutineScope(SupervisorJob() + object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { throw rejection }
        })
        // Test-only failure injection into the exact owner, not a substitute receipt implementation.
        val field = SessionHostedProcessOwner::class.java.getDeclaredField("receiptScope").apply { isAccessible = true }
        val normalScope = field.get(owner) as CoroutineScope
        try {
            field.set(owner, rejectedScope)
            owner.retain(process)
            val failure = withTimeout(2_000) { runCatching { owner.stopAndAwait() }.exceptionOrNull() }
            assertNotNull("Dispatcher failure must not certify completion", failure)
            assertTrue("Dispatcher failure must retain its original evidence", generateSequence(failure) { it.cause }.any { it === rejection })
            assertEquals(1, closes.get()); assertEquals(0, awaits.get())
            field.set(owner, normalScope)
            withTimeout(2_000) { owner.stopAndAwait() }
            assertEquals(2, closes.get()); assertEquals(1, awaits.get())
            owner.stopAndAwait()
            assertEquals(2, closes.get())
        } finally { field.set(owner, normalScope); rejectedScope.cancel(); normalScope.cancel() }
    }
}
