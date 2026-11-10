package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CatalogProcessObservationTest {
    private open class Peer : HostedStdioProcess {
        override val stdin=ByteArrayOutputStream()
        override val stdout=ByteArrayInputStream(byteArrayOf())
        override val stderr=ByteArrayInputStream(byteArrayOf())
        val done=CompletableDeferred<Unit>()
        val reads=AtomicInteger();val closes=AtomicInteger();val waits=AtomicInteger()
        @Volatile var alive=true
        override val isAlive:Boolean get(){reads.incrementAndGet();return alive}
        override val exitCode:Int? get()=error("Observation must not inspect exitCode")
        override fun close(){closes.incrementAndGet();alive=false;done.complete(Unit)}
        override suspend fun awaitExit():Int {waits.incrementAndGet();done.await();return 0}
        fun complete(){alive=false;done.complete(Unit)}
    }
    private fun fixture(action:suspend CoroutineScope.(File)->Unit)=runBlocking {
        val root=Files.createTempDirectory("catalog-process-observe").toFile()
        try {withTimeout(10000){action(root)}} finally {root.deleteRecursively()}
    }
    private suspend fun eventually(condition:()->Boolean){withTimeout(5000){while(!condition())delay(5)}}
    @Test fun emptyParticipatingObservationsNeverCertifyCoverage()=fixture {root->
        val observation=CatalogProcessObservation.capture(root);observation.validate();val evidence=observation.evidence()
        assertTrue(evidence.unknownCoverage.isNotEmpty());assertTrue(evidence.unknownDescendants.isNotEmpty());assertTrue(evidence.unknownDescriptors.isNotEmpty())
    }
    @Test fun sameLabelNestedCountsAndAbaInvalidate()=fixture {root->
        val first=ExportWriterAdmission.beginWriter(root,"same")
        try {
            val observed=ExportWriterAdmission.observe(root);val second=ExportWriterAdmission.beginWriter(root,"same")
            assertEquals(2,ExportWriterAdmission.observe(root).writers.single().count)
            second.close();assertEquals(1,ExportWriterAdmission.observe(root).writers.single().count)
            assertTrue(runCatching{observed.validate()}.isFailure)
        } finally {first.close()}
        val empty=ExportWriterAdmission.observe(root);ExportWriterAdmission.beginWriter(root,"transient").close()
        assertTrue(runCatching{empty.validate()}.isFailure)
    }
    @Test fun managedObservationDoesNotPrepareOrReserve()=fixture {root->
        var callbacks=0
        val participant=object:ExportCaptureParticipant{override suspend fun <T> withQuiescence(action:suspend()->T):T{callbacks++;return action()}}
        val ticket=ExportWriterAdmission.beginManagedWriter(root,"managed",participant)
        try {val observed=ExportWriterAdmission.observe(root);assertEquals("managed",observed.managed.single().label);observed.validate();assertEquals(0,callbacks)} finally {ticket.close()}
    }
    @Test fun activeCaptureIsReportedWithoutEndingIt()=fixture {root->
        val capture=ExportWriterAdmission.beginCapture(root)
        try {val observed=ExportWriterAdmission.observe(root);assertEquals(1,observed.captures.size);observed.validate();assertTrue(runCatching{ExportWriterAdmission.beginWriter(root,"blocked")}.isFailure)} finally {capture.close()}
    }
    @Test fun rootMappedTicketObservationDoesNotProbeHandle()=fixture {root->
        val retainedBefore=ExitHostedProcessRegistry.observe().retainedHandles
        val peer=WorkspaceProcessWriteAdmission.create(root){Peer()};val other=File(root.parentFile,root.name+"-other").apply{mkdir()}
        try {
            eventually{peer.waits.get()>0}
            val beforeReads=peer.reads.get();val beforeWaits=peer.waits.get()
            val observed=WorkspaceProcessWriteAdmission.observe(root)
            assertEquals(1,observed.registeredHandles);assertEquals(0,WorkspaceProcessWriteAdmission.observe(other).registeredHandles)
            repeat(3){observed.validate();WorkspaceProcessWriteAdmission.observe(root)}
            assertEquals(beforeReads,peer.reads.get());assertEquals(beforeWaits,peer.waits.get());assertEquals(0,peer.closes.get())
            peer.complete();eventually{WorkspaceProcessWriteAdmission.observe(root).registeredHandles==0}
            assertTrue(runCatching{observed.validate()}.isFailure)
        } finally {peer.complete();eventually{WorkspaceProcessWriteAdmission.observe(root).registeredHandles==0 && ExitHostedProcessRegistry.observe().retainedHandles==retainedBefore};other.deleteRecursively()}
    }
    @Test fun inFlightFactoryAppearsAsWriterBeforeHandlePublication()=fixture {root->
        val retainedBefore=ExitHostedProcessRegistry.observe().retainedHandles
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val peer=Peer()
        val launch=async(Dispatchers.IO){WorkspaceProcessWriteAdmission.create(root){entered.countDown();check(release.await(5,TimeUnit.SECONDS));peer}}
        try {
            assertTrue(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
            val observed=CatalogProcessObservation.capture(root)
            assertEquals(0,WorkspaceProcessWriteAdmission.observe(root).registeredHandles)
            assertTrue(observed.evidence().participatingWriters.any{it.contains("Running or unconfirmed workspace process")})
            assertTrue(observed.evidence().unknownCoverage.isNotEmpty())
            release.countDown();launch.await();assertTrue(runCatching{observed.validate()}.isFailure)
        } finally {release.countDown();launch.join();peer.complete();eventually{WorkspaceProcessWriteAdmission.observe(root).registeredHandles==0 && ExitHostedProcessRegistry.observe().retainedHandles==retainedBefore}}
    }
    @Test fun failedFactoryRetainsExplicitUnknownRootAndFence()=fixture {root->
        val before=WorkspaceProcessWriteAdmission.observe(root)
        assertTrue(runCatching{WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(root){error("fixture uncertain creation")}}.isFailure)
        val after=WorkspaceProcessWriteAdmission.observe(root)
        assertEquals(listOf(root.canonicalPath),after.unknownFactoryRoots)
        assertTrue(runCatching{before.validate()}.isFailure)
        assertTrue(CatalogProcessObservation.capture(root).evidence().unknownDescendants.any{it.contains(root.canonicalPath)})
        assertTrue(runCatching{ExportWriterAdmission.beginCapture(root)}.isFailure)
    }
    @Test fun retainedHandleWithThrowingPropertiesCanBeObserved()=fixture {_->
        val owners=ExitHostedProcessOwners({true},{true});val peer=object:Peer(){override val isAlive:Boolean get()=error("not an observation API")}
        owners.retain(peer);eventually{peer.waits.get()>0}
        try {val observed=owners.observe();assertEquals(1,observed.retainedHandles);repeat(3){observed.validate();owners.observe()};assertEquals(0,peer.closes.get())} finally {peer.complete()}
    }
    @Test fun sameHandleReretainInvalidatesPriorObservation()=fixture {_->
        val owners=ExitHostedProcessOwners({true},{true});val peer=Peer();owners.retain(peer)
        try {val before=owners.observe();owners.retain(peer);assertEquals(1,owners.observe().retainedHandles);assertTrue(runCatching{before.validate()}.isFailure)} finally {peer.complete()}
    }
    @Test fun exitOwnerRegistrationAndRemovalAbaInvalidatesEmptyObservation()=fixture {_->
        val owners=ExitHostedProcessOwners({true},{true});val empty=owners.observe();val peer=Peer();owners.retain(peer);peer.complete()
        eventually{owners.observe().retainedHandles==0};assertTrue(runCatching{empty.validate()}.isFailure)
    }
    @Test fun failedCleanupRemainsRetainedAfterNaturalCompletion()=fixture {_->
        val owners=ExitHostedProcessOwners({false},{false});val peer=object:Peer(){override fun close(){if(closes.incrementAndGet()==1)error("fixture close failure");complete()}}
        owners.retain(peer);val before=owners.observe()
        assertTrue(runCatching{owners.stopAndAwaitAll()}.isFailure)
        val failed=owners.observe();assertEquals(1,failed.failedCleanup);assertEquals(1,failed.retainedHandles)
        assertTrue(runCatching{before.validate()}.isFailure)
        peer.complete();eventually{owners.observe().completionObserved==1}
        assertEquals(1,owners.observe().retainedHandles);assertEquals(1,owners.observe().failedCleanup)
        assertTrue(runCatching{failed.validate()}.isFailure)
        owners.stopAndAwaitAll();assertEquals(0,owners.observe().retainedHandles)
    }
    @Test fun pendingCleanupObservationDoesNotDispatchOrRepeatStop()=fixture {_->
        val queued=CompletableDeferred<()->Unit>();val owners=ExitHostedProcessOwners({false},{false},dispatchClose={queued.complete(it)})
        val peer=Peer();owners.retain(peer)
        val stop=async{owners.stopAndAwaitAll()};val dispatch=queued.await()
        try {
            val pending=owners.observe();assertEquals(1,pending.pendingCleanup);pending.validate();assertEquals(0,peer.closes.get())
            peer.complete();eventually{owners.observe().completionObserved==1}
            assertEquals(1,owners.observe().pendingCleanup);assertEquals(1,owners.observe().retainedHandles)
            dispatch();stop.await();assertEquals(1,peer.closes.get());assertTrue(runCatching{pending.validate()}.isFailure)
        } finally {peer.complete();if(!stop.isCompleted){dispatch();stop.join()}}
    }
    @Test fun cancellationOfReviewNeverStartsCleanup()=fixture {root->
        val retainedBefore=ExitHostedProcessRegistry.observe().retainedHandles
        val peer=WorkspaceProcessWriteAdmission.create(root){Peer()}
        try {val observed=CatalogProcessObservation.capture(root);val job=Job().apply{cancel()};assertTrue(runCatching{withContext(job){observed.validate()}}.exceptionOrNull() is CancellationException);assertEquals(0,peer.closes.get())} finally {peer.complete();eventually{WorkspaceProcessWriteAdmission.observe(root).registeredHandles==0 && ExitHostedProcessRegistry.observe().retainedHandles==retainedBefore}}
    }
    @Test fun observationsOwnReadOnlyCollections()=fixture {root->
        val ticket=ExportWriterAdmission.beginWriter(root,"writer")
        try {val observed=ExportWriterAdmission.observe(root);assertTrue(runCatching{(observed.writers as MutableList).clear()}.exceptionOrNull() is UnsupportedOperationException)} finally {ticket.close()}
    }
}
