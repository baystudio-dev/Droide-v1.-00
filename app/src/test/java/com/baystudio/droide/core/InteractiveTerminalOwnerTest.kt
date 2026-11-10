package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InteractiveTerminalOwnerTest {
    @Test fun staleProcessGenerationCannotRestartReplacement() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val epochs = CopyOnWriteArrayList<Long>()
        val first = CountDownLatch(1); val second = CountDownLatch(1)
        val terminal = InteractiveTerminalProcess(scope, { epoch ->
            epochs += epoch
            (if (epochs.size == 1) first else second).countDown()
            ProcessBuilder("/bin/sh", "-c", "while read line; do printf '%s\\n' \"\$line\"; done").start()
        }, {}, {})
        try {
            terminal.start(); assertTrue(first.await(5, TimeUnit.SECONDS))
            val old = epochs.first(); terminal.restart(); assertTrue(second.await(5, TimeUnit.SECONDS))
            assertFalse(terminal.restartIfCurrent(old) { true })
            assertEquals(2, epochs.size)
        } finally { terminal.destroyAndJoinForExit(); scope.cancel() }
    }
    @Test fun changedCommandGuardDoesNotDetachCurrentProcess() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val epochs = CopyOnWriteArrayList<Long>(); val started = CountDownLatch(1)
        val terminal = InteractiveTerminalProcess(scope, { epoch ->
            epochs += epoch; started.countDown(); ProcessBuilder("/bin/sh", "-c", "exec cat").start()
        }, {}, {})
        try {
            terminal.start(); assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(terminal.restartIfCurrent(epochs.single()) { false })
            terminal.send("still alive")
            assertEquals(1, epochs.size)
        } finally { terminal.destroyAndJoinForExit(); scope.cancel() }
    }
    @Test fun matchingOwnerRestartsOnceAndOldEpochThenRejects() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val epochs = CopyOnWriteArrayList<Long>(); val started = CountDownLatch(1);val next=CountDownLatch(1)
        val terminal = InteractiveTerminalProcess(scope, { epoch ->
            epochs += epoch; (if(epochs.size==1)started else next).countDown(); ProcessBuilder("/bin/sh", "-c", "exec cat").start()
        }, {}, {})
        try {
            terminal.start(); assertTrue(started.await(5,TimeUnit.SECONDS));val old=epochs.single()
            assertTrue(terminal.restartIfCurrent(old) { true });assertTrue(next.await(5,TimeUnit.SECONDS))
            assertFalse(terminal.restartIfCurrent(old) { true });assertEquals(2,epochs.size)
        } finally { terminal.destroyAndJoinForExit(); scope.cancel() }
    }
    @Test fun claimedDetachCleanupCannotRestartAfterClosedAdmission() = runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val epochs=CopyOnWriteArrayList<Long>();val started=CountDownLatch(1)
        val terminal=InteractiveTerminalProcess(scope,{epoch -> epochs+=epoch;started.countDown();ProcessBuilder("/bin/sh","-c","exec cat").start()},{},{})
        try {
            terminal.start();assertTrue(started.await(5,TimeUnit.SECONDS))
            val claim=checkNotNull(terminal.claimRestartIfCurrent(epochs.single()){true})
            var admissions=0
            claim.complete { admissions++ /* Closed admission deliberately does not invoke restart. */ }
            claim.complete { error("duplicate completion") }
            assertEquals(1,admissions);assertEquals(1,epochs.size)
            assertThrows(IllegalStateException::class.java){terminal.send("must reject")}
        } finally {terminal.destroyAndJoinForExit();scope.cancel()}
    }
    @Test fun delayedClaimCannotRestartOrStopAlreadyReplacedShell() = runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val epochs=CopyOnWriteArrayList<Long>();val first=CountDownLatch(1);val next=CountDownLatch(1)
        val terminal=InteractiveTerminalProcess(scope,{epoch -> epochs+=epoch;(if(epochs.size==1)first else next).countDown();ProcessBuilder("/bin/sh","-c","exec cat").start()},{},{})
        try {
            terminal.start();assertTrue(first.await(5,TimeUnit.SECONDS))
            val claim=checkNotNull(terminal.claimRestartIfCurrent(epochs.single()){true})
            terminal.start();assertTrue(next.await(5,TimeUnit.SECONDS))
            claim.complete();assertEquals(2,epochs.size);terminal.send("replacement remains writable")
        } finally {terminal.destroyAndJoinForExit();scope.cancel()}
    }
    @Test fun claimDoesNotInvokeCleanupOrAdmissionWhileCommandLockIsHeld() = runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val epochs=CopyOnWriteArrayList<Long>();val started=CountDownLatch(1)
        val terminal=InteractiveTerminalProcess(scope,{epoch -> epochs+=epoch;started.countDown();ProcessBuilder("/bin/sh","-c","exec cat").start()},{},{})
        try {
            terminal.start();assertTrue(started.await(5,TimeUnit.SECONDS))
            val tracker=TerminalCommandActivityTracker();tracker.submitted();val generation=tracker.state.value.generation
            var claim:InteractiveTerminalProcess.RestartClaim?=null
            tracker.withActiveGeneration(generation){claim=terminal.claimRestartIfCurrent(epochs.single()){true};true}
            checkNotNull(claim).complete { restart ->
                val other=java.util.concurrent.Executors.newSingleThreadExecutor()
                try {other.submit { tracker.closed() }.get(2,TimeUnit.SECONDS)} finally {other.shutdownNow()}

            }
        } finally {terminal.destroyAndJoinForExit();scope.cancel()}
    }

}
