package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExitRejectedEpochRetainRaceF153Test {
    @Test(timeout = 12_000) fun replacementRecordDuringEpochCheckPreservesItsOwnPendingReceipt() = runBlocking {
        val checking = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        val owners = ExitHostedProcessOwners(isOpen = { false }, isCurrentOpen = {
            checking.countDown(); check(releaseCheck.await(6, TimeUnit.SECONDS)); false
        })
        val entered = List(4) { CompletableDeferred<Unit>() }
        val release = List(4) { CompletableDeferred<Unit>() }
        val sequence = AtomicInteger()
        val peer = object : HostedStdioProcess {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(byteArrayOf())
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive = false
            override val exitCode: Int? = 0
            override fun close() = Unit
            override suspend fun awaitExit(): Int {
                val index = sequence.getAndIncrement()
                if (index < 4) { entered[index].complete(Unit); release[index].await() }
                return 0
            }
        }
        val admission = async(Dispatchers.Default) { runCatching { owners.admit(peer, "old-epoch") } }
        try {
            assertTrue(checking.await(2, TimeUnit.SECONDS))
            withTimeout(3_000) { entered[0].await() }
            val initialExit = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
            withTimeout(3_000) { entered[1].await() }
            release[0].complete(Unit); release[1].complete(Unit)
            withTimeout(3_000) { initialExit.await().getOrThrow() }
            owners.retain(peer)
            withTimeout(3_000) { entered[2].await() }
            releaseCheck.countDown()
            withTimeout(3_000) { entered[3].await() }
            release[3].complete(Unit)
            assertTrue(withTimeout(3_000) { admission.await() }.exceptionOrNull() is CancellationException)
            val exit = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
            delay(100)
            assertFalse("An older rejected admission cannot claim the replacement record's pending observation",
                exit.isCompleted && exit.await().isSuccess)
            release[2].complete(Unit)
            withTimeout(3_000) { exit.join() }
            withTimeout(3_000) { while (runCatching { owners.stopAndAwaitAll() }.isFailure) delay(5) }
        } finally { releaseCheck.countDown(); release.forEach { it.complete(Unit) }; withTimeout(3_000) { admission.join() } }
    }

    @Test(timeout = 12_000) fun newerRetainDuringEpochCheckCannotBeRetiredByRejectedOlderAdmission() = runBlocking {
        val checking = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        val owners = ExitHostedProcessOwners(isOpen = { false }, isCurrentOpen = {
            checking.countDown(); check(releaseCheck.await(6, TimeUnit.SECONDS)); false
        })
        val entered = List(3) { CompletableDeferred<Unit>() }
        val release = List(3) { CompletableDeferred<Unit>() }
        val sequence = AtomicInteger()
        val peer = object : HostedStdioProcess {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(byteArrayOf())
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive = false
            override val exitCode: Int? = 0
            override fun close() = Unit
            override suspend fun awaitExit(): Int {
                val index = sequence.getAndIncrement()
                if (index < 3) { entered[index].complete(Unit); release[index].await() }
                return 0
            }
        }
        val admission = async(Dispatchers.Default) { runCatching { owners.admit(peer, "old-epoch") } }
        try {
            assertTrue(checking.await(2, TimeUnit.SECONDS))
            withTimeout(3_000) { entered[0].await() }
            owners.retain(peer)
            withTimeout(3_000) { entered[1].await() }
            releaseCheck.countDown()
            withTimeout(3_000) { entered[2].await() }
            release[0].complete(Unit); release[2].complete(Unit)
            assertTrue(withTimeout(3_000) { admission.await() }.exceptionOrNull() is CancellationException)
            val exit = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
            delay(100)
            assertFalse("Older rejected admission cannot clear the newer still-pending retain published during epoch check",
                exit.isCompleted && exit.await().isSuccess)
            release[1].complete(Unit)
            withTimeout(3_000) { exit.join() }
            withTimeout(3_000) { while (runCatching { owners.stopAndAwaitAll() }.isFailure) delay(5) }
        } finally { releaseCheck.countDown(); release.forEach { it.complete(Unit) }; withTimeout(3_000) { admission.join() } }
    }
}
