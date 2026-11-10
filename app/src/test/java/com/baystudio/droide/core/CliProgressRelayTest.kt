package com.baystudio.droide.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CliProgressRelayTest {
    @Test(timeout = 10_000) fun progressArrivesBeforeCompletionAndWriterFinishesBeforeFinalResult() = runBlocking {
        val progressArrived = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val frames = java.util.Collections.synchronizedList(mutableListOf<String>())
        val expected = DroideCliExecutionResult(4, stderr = "Health failed")
        val work = async(Dispatchers.Default) {
            CliProgressRelay.execute(operation = {
                currentCoroutineContext()[PackageProgressObserver]!!.publish(PackageProgressSnapshot("installing", "Unpacking", "real output 日本語\n"))
                finish.await()
                expected
            }, writeProgress = { frames.add(it); progressArrived.complete(Unit) })
        }
        withTimeout(3_000) { progressArrived.await() }
        assertFalse(work.isCompleted)
        finish.complete(Unit)
        assertEquals(expected, withTimeout(3_000) { work.await() })
        assertTrue(frames.joinToString("").contains("real output 日本語"))
        assertFalse(frames.joinToString("").contains("Health failed"))
    }

    @Test(timeout = 10_000) fun slowConsumerDoesNotBlockCallbacksAndKeepsTheNewestSnapshot() = runBlocking {
        val writing = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val produced = CompletableDeferred<Unit>()
        val frames = java.util.Collections.synchronizedList(mutableListOf<String>())
        val work = async(Dispatchers.Default) {
            CliProgressRelay.execute(operation = {
                val observer = currentCoroutineContext()[PackageProgressObserver]!!
                observer.publish(PackageProgressSnapshot("installing", "First"))
                withContext(Dispatchers.IO) { check(writing.await(3, TimeUnit.SECONDS)) }
                repeat(10_000) { observer.publish(PackageProgressSnapshot("installing", "Stage $it")) }
                observer.publish(PackageProgressSnapshot("activating", "Latest"))
                produced.complete(Unit)
                DroideCliExecutionResult(0, "verified final")
            }, writeProgress = {
                frames.add(it)
                if (writing.count > 0) { writing.countDown(); check(releaseWriter.await(3, TimeUnit.SECONDS)) }
            })
        }
        try {
            withTimeout(2_000) { produced.await() }
            assertFalse(work.isCompleted)
        } finally { releaseWriter.countDown() }
        assertEquals(0, withTimeout(3_000) { work.await() }.exitCode)
        assertTrue(frames.size <= 2)
        assertTrue(frames.last().contains("Latest"))
    }

    @Test fun continuousLogUsesDeltasAndRevisedHistoryIsLabeled() {
        val text = CliProgressRelay.ProgressText()
        val initial = PackageProgressSnapshot("installing", "Working", "line one\n")
        assertTrue(text.render(initial).contains("line one"))
        assertEquals("", text.render(initial))
        assertEquals("line two\n", text.render(initial.copy(log = "line one\nline two\n")))
        assertTrue(text.render(initial.copy(log = "changed redacted history\n")).contains("earlier lines may be omitted"))
    }

    @Test fun rollingLogReusesUniqueSubstantialOverlap() {
        val text = CliProgressRelay.ProgressText()
        val log = (1..250).joinToString("\n") { "download line $it" } + "\n"
        text.render(PackageProgressSnapshot("downloading", "Fetching", log))
        val rolling = log.drop(500) + "new bytes\n"
        assertEquals("new bytes\n", text.render(PackageProgressSnapshot("downloading", "Fetching", rolling)))
    }
}
