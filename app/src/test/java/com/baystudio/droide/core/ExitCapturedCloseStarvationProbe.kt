package com.baystudio.droide.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExitCapturedCloseStarvationProbe {
    @Test(timeout = 15_000) fun capturedProcessCancellationMustNotStarveSiblingStops() = runBlocking {
        listOf(ExitHostedProcessOwners::class.java, CapturedWorkspaceProcess::class.java, OwnedResourceCleanup::class.java).forEach {
            println("ORIGIN ${it.name} ${it.protectionDomain.codeSource.location}")
        }
        val registry = ExitHostedProcessOwners(isOpen = { false }, isCurrentOpen = { false })
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(3)
        val children = (1..3).map { ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start() }
        val captures = mutableListOf<Job>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val peers = children.map { child ->
            lateinit var peer: CapturedWorkspaceProcess
            val capture = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation {
                        entered.countDown()
                        gate.await(8, TimeUnit.SECONDS)
                        child.destroyForcibly()
                        check(child.waitFor(2, TimeUnit.SECONDS))
                        peer.completed(null)
                    }
                }
            }
            captures += capture
            peer = CapturedWorkspaceProcess(child, capture)
            peer
        }
        peers.forEach(registry::retain)
        val exit = async(Dispatchers.IO) { registry.stopAndAwaitAll() }
        try {
            val allDispatched = entered.await(2, TimeUnit.SECONDS)
            println("CLOSE_DISPATCH dispatched=${3 - entered.count} remaining=${entered.count} childrenAlive=${children.count { it.isAlive }}")
            assertTrue("All 3 captured real children must receive cancellation before any blocked cancellation handler returns; still not dispatched=${entered.count}", allDispatched)
            assertFalse("Held close receipts cannot prove Exit completion", exit.isCompleted)
        } finally {
            gate.countDown()
            peers.forEach { it.close() }
            children.forEach { it.destroyForcibly(); it.waitFor(3, TimeUnit.SECONDS) }
            peers.forEach { it.completed(null) }
            withTimeout(3_000) { exit.join(); captures.joinAll() }
            scope.cancel()
            children.forEach { it.outputStream.close(); it.inputStream.close(); it.errorStream.close() }
        }
    }
}
