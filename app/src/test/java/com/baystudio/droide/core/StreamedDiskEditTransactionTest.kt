package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StreamedDiskEditTransactionTest {
    private val direct = object : StreamedDiskCommitAuthority { override suspend fun commitDiskOnly(path: String, action: () -> Unit) = action() }
    private fun fixture(block: suspend (File, SessionManager, String, OwnedResponseContentStore.Session) -> Unit) = runBlocking {
        val parent = Files.createTempDirectory("stream-disk-edit-").toFile()
        val root = File(parent, "workspace").apply { mkdir() }
        try {
            val sessions = SessionManager(root); val id = sessions.create().id
            val store = OwnedResponseContentStore(File(parent,"owned").toPath(), OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0))
            block(root,sessions,id,store.authorize(OwnedResponseContentStore.Owner(id,"00000000-0000-0000-0000-000000000001","call")))
        } finally { PathSecurity.deleteTreeNoFollow(parent) }
    }
    private fun source(session: OwnedResponseContentStore.Session, repetitions: Int = 500): StreamedFileArgumentExtractor.Source =
        session.begin(OwnedResponseContentStore.Kind.TOOL_CONTENT).use { writer ->
            repeat(repetitions) { writer.append("abcdefgh".repeat(1024)) }
            val c=writer.sealCompleted { input -> val b=ByteArray(8192);while(input.read(b)>=0){} }.content
            StreamedFileArgumentExtractor.Source(c.handle,c.byteLength,c.utf16Length,c.sha256,c.kind)
        }
    private fun call(sessions: SessionManager,id:String,current:()->Boolean={true},publish:(SessionTextEditEvidence.Record,AgentTextEditAssociation)->Unit={_,_->}) =
        AgentStreamedTextEditCall(sessions,SessionTextEditEvidence.Owner(id,"00000000-0000-0000-0000-000000000001","call","tool:1","write_file"),current,publish)
    private fun observation(files:FileRepository,before:StreamFileVersion,source:StreamedFileArgumentExtractor.Source,
        barrier:StreamedDiskCommitAuthority=direct,check:()->Unit={}) = StreamedTextEditObservation(files,"a.txt",before,
            StreamContentVersion(true,source.bytes,source.sha256),"a".repeat(64),barrier,check)

    @Test fun largeOverwriteExactUndoAndV2Evidence() = fixture { root,sessions,id,owned ->
        val target=File(root,"a.txt")
        target.outputStream().use { out -> repeat(3000) { out.write(ByteArray(8192){7}) } }
        val files=FileRepository(root);val history=EditHistory(files,streamCommitAuthority=direct)
        val before=StreamFileVersion.capture(root,"a.txt");val pending=history.prepareStream("a.txt",before)
        val source=source(owned,2800);val obs=observation(files,before,source)
        assertTrue(before.bytes > EditorLargeFilePolicy.MAX_EDITABLE_BYTES)
        assertTrue(source.bytes > EditorLargeFilePolicy.MAX_EDITABLE_BYTES)
        StreamedDiskEditTransaction.execute(files,history,pending,obs,call(sessions,id),owned,source)
        assertEquals(source.sha256,StreamFileVersion.capture(root,"a.txt").sha256)
        assertEquals(1,history.depth());assertTrue(history.undo().startsWith("Undone:"))
        assertEquals(before.sha256,StreamFileVersion.capture(root,"a.txt").sha256);assertEquals(0,history.depth())
        val evidence=sessions.readTextEditEvidence(checkNotNull(sessions.get(id)))
        assertEquals(2,evidence.records.single().intent.version)
        assertEquals("OWNED_CURRENT",evidence.records.single().outcome?.disposition)
    }
    @Test fun newlyCreatedLargeFileUndoUsesAttributedDeletion() = fixture { root,sessions,id,owned ->
        val files=FileRepository(root);val history=EditHistory(files,streamCommitAuthority=direct)
        val before=StreamFileVersion.capture(root,"a.txt");val pending=history.prepareStream("a.txt",before);val source=source(owned)
        StreamedDiskEditTransaction.execute(files,history,pending,observation(files,before,source),call(sessions,id),owned,source)
        assertTrue(history.undo().startsWith("Undone:"));assertFalse(File(root,"a.txt").exists());assertEquals(0,history.depth())
    }
    @Test fun changedTargetBeforeWritePreservesNewerBytesAndBackup() = fixture { root,sessions,id,owned ->
        val target=File(root,"a.txt").apply{writeText("before")};val files=FileRepository(root);val history=EditHistory(files,streamCommitAuthority=direct)
        val before=StreamFileVersion.capture(root,"a.txt");val pending=history.prepareStream("a.txt",before);val source=source(owned,1)
        target.writeText("newer")
        assertTrue(runCatching { StreamedDiskEditTransaction.execute(files,history,pending,observation(files,before,source),call(sessions,id),owned,source) }.isFailure)
        assertEquals("newer",target.readText());assertTrue(pending.snapshots.single().backup.exists());assertEquals(0,history.depth())
    }
    @Test fun ownerRevocationAfterDurableIntentNeverPublishes() = fixture { root,sessions,id,owned ->
        File(root,"a.txt").writeText("before");val files=FileRepository(root);val history=EditHistory(files,streamCommitAuthority=direct)
        val before=StreamFileVersion.capture(root,"a.txt");val pending=history.prepareStream("a.txt",before);val source=source(owned,1);var current=true
        val obs=observation(files,before,source,check={check(current)})
        assertTrue(runCatching { StreamedDiskEditTransaction.execute(files,history,pending,obs,call(sessions,id,{current},{_,_->current=false}),owned,source) }.isFailure)
        assertEquals("before",File(root,"a.txt").readText());assertFalse(obs.settlement?.attempted==true)
    }
    @Test fun barrierThrowAfterRenameRemainsAmbiguousNoUndoDiscardOrRetry() = fixture { root,sessions,id,owned ->
        File(root,"a.txt").writeText("before");val files=FileRepository(root);val history=EditHistory(files,streamCommitAuthority=direct)
        val before=StreamFileVersion.capture(root,"a.txt");val pending=history.prepareStream("a.txt",before);val source=source(owned,1)
        val broken=object:StreamedDiskCommitAuthority { override suspend fun commitDiskOnly(path:String,action:()->Unit) {action();error("after rename")} }
        val obs=observation(files,before,source,broken)
        assertTrue(runCatching { StreamedDiskEditTransaction.execute(files,history,pending,obs,call(sessions,id),owned,source) }.isFailure)
        assertTrue(obs.settlement?.attempted==true);assertFalse(obs.settlement?.ownedCurrent==true)
        assertEquals(source.sha256,StreamFileVersion.capture(root,"a.txt").sha256)
        assertTrue(pending.snapshots.single().backup.exists());assertEquals(0,history.depth())
    }
    @Test fun undoRefusesChangedTargetAndKeepsBackup() = fixture { root,sessions,id,owned ->
        File(root,"a.txt").writeText("before");val files=FileRepository(root);val history=EditHistory(files,streamCommitAuthority=direct)
        val before=StreamFileVersion.capture(root,"a.txt");val pending=history.prepareStream("a.txt",before);val source=source(owned,1)
        StreamedDiskEditTransaction.execute(files,history,pending,observation(files,before,source),call(sessions,id),owned,source)
        File(root,"a.txt").writeText("external")
        assertTrue(history.undo().startsWith("Undo failed:"));assertEquals("external",File(root,"a.txt").readText())
        assertEquals(1,history.depth());assertTrue(pending.snapshots.single().backup.exists())
    }
    @Test fun syncFailureHasAttributableReceiptAndExactUndo() = fixture { root,sessions,id,owned ->
        File(root,"a.txt").writeText("before")
        val files=FileRepository(root,onMutation={throw java.io.IOException("sync fixture")})
        val history=EditHistory(files,streamCommitAuthority=direct)
        val before=StreamFileVersion.capture(root,"a.txt");val pending=history.prepareStream("a.txt",before);val source=source(owned,1)
        val obs=observation(files,before,source);val journalCall=call(sessions,id)
        val result=StreamedDiskEditTransaction.execute(files,history,pending,obs,journalCall,owned,source)
        assertTrue(result.synchronizationPending);assertEquals(1,history.depth())
        val proof=journalCall.publicationProof(obs)
        assertEquals("OWNED_CURRENT",proof.record.outcome?.disposition)
        assertEquals("SYNC_FAILED",proof.record.outcome?.repositoryReturn)
        assertEquals(source.sha256,proof.receipt.preparation.sha256)
    }
    @Test fun cancellationBeforeRenameNeverHasCommittedProof() = fixture { root,sessions,id,owned ->
        File(root,"a.txt").writeText("before");val files=FileRepository(root);val history=EditHistory(files,streamCommitAuthority=direct)
        val before=StreamFileVersion.capture(root,"a.txt");val pending=history.prepareStream("a.txt",before);val source=source(owned,1)
        val stop=object:StreamedDiskCommitAuthority { override suspend fun commitDiskOnly(path:String,action:()->Unit) {throw CancellationException("stop")} }
        val obs=observation(files,before,source,stop);val journalCall=call(sessions,id)
        val failure=runCatching { StreamedDiskEditTransaction.execute(files,history,pending,obs,journalCall,owned,source) }.exceptionOrNull()
        assertTrue(failure is CancellationException);assertEquals("before",File(root,"a.txt").readText())
        assertTrue(runCatching {journalCall.publicationProof(obs)}.isFailure)
        assertTrue(pending.snapshots.single().backup.exists())
    }

    @Test fun detachedUiAfterAcknowledgementDoesNotEraseReceiptOrUndo() = deliveryAfterCommit(cancel=false)
    @Test fun cancelledUiDeliveryRetainsUndoAndProofBeforePropagatingStop() = deliveryAfterCommit(cancel=true)
    private fun deliveryAfterCommit(cancel:Boolean) = fixture { root,sessions,id,owned ->
        File(root,"a.txt").writeText("before");val files=FileRepository(root);val history=EditHistory(files,streamCommitAuthority=direct)
        val before=StreamFileVersion.capture(root,"a.txt");val pending=history.prepareStream("a.txt",before);val source=source(owned,1)
        val obs=observation(files,before,source);var deliveries=0
        val journalCall=call(sessions,id,publish={_,_->if(++deliveries==2) {
            if(cancel) throw CancellationException("detached stop") else throw java.io.IOException("detached UI")
        }})
        val result=runCatching {StreamedDiskEditTransaction.execute(files,history,pending,obs,journalCall,owned,source)}
        if(cancel) assertTrue(result.exceptionOrNull() is CancellationException) else assertTrue(result.isSuccess)
        assertEquals(1,history.depth());assertNotNull(journalCall.deliveryFailure)
        assertEquals(source.sha256,journalCall.publicationProof(obs).receipt.preparation.sha256)
    }

    @Test fun corruptBackupBetweenPrepareAndWriteRejectsBeforeCanonicalMutation() = fixture { root,sessions,id,owned ->
        File(root,"a.txt").writeText("before");val files=FileRepository(root);val history=EditHistory(files,streamCommitAuthority=direct)
        val before=StreamFileVersion.capture(root,"a.txt");val pending=history.prepareStream("a.txt",before);val source=source(owned,1)
        pending.snapshots.single().backup.writeText("corupt")
        val obs=observation(files,before,source)
        assertTrue(runCatching {StreamedDiskEditTransaction.execute(files,history,pending,obs,call(sessions,id),owned,source)}.isFailure)
        assertFalse(obs.settlement?.attempted==true);assertEquals("before",File(root,"a.txt").readText())
    }
    @Test fun participantFinishReplacementCannotEarnOwnedCurrentSettlement() = lateFinish(false)
    @Test fun participantFinishFailureKeepsUncertainRecoveryDespiteReceipt() = lateFinish(true)
    private fun lateFinish(throws:Boolean) = fixture { root,sessions,id,owned ->
        File(root,"a.txt").writeText("before")
        val participant=WorkspaceMutationParticipant { guard,mutation -> object:WorkspaceMutationSession(guard,mutation) {
            override fun finish(outcome:WorkspaceMutationOutcome) {
                if(throws) error("finish failed") else File(root,"a.txt").writeText("external-after-receipt")
            }
        }}
        val files=FileRepository(root,newFilePublisher=NewProjectFilePublisher.platform,participant=participant)
        val history=EditHistory(files,streamCommitAuthority=direct);val before=StreamFileVersion.capture(root,"a.txt")
        val pending=history.prepareStream("a.txt",before);val source=source(owned,1);val obs=observation(files,before,source)
        assertTrue(runCatching {StreamedDiskEditTransaction.execute(files,history,pending,obs,call(sessions,id),owned,source)}.isFailure)
        assertNotNull(obs.settlement?.receipt);assertFalse(obs.settlement?.ownedCurrent==true)
        assertEquals(0,history.depth());assertTrue(pending.snapshots.single().backup.exists())
    }

    @Test(timeout=15000) fun stopWhileQueuedForEditorBarrierDoesNotPublish() = fixture { root,sessions,id,owned ->
        File(root,"a.txt").writeText("before");val files=FileRepository(root);val history=EditHistory(files,streamCommitAuthority=direct)
        val before=StreamFileVersion.capture(root,"a.txt");val pending=history.prepareStream("a.txt",before);val source=source(owned,1)
        val entered=kotlinx.coroutines.CompletableDeferred<Unit>();val release=kotlinx.coroutines.CompletableDeferred<Unit>()
        val queued=object:StreamedDiskCommitAuthority { override suspend fun commitDiskOnly(path:String,action:()->Unit) {
            entered.complete(Unit);release.await();action()
        }}
        val obs=observation(files,before,source,queued)
        kotlinx.coroutines.coroutineScope {
            val job=launch {runCatching {StreamedDiskEditTransaction.execute(files,history,pending,obs,call(sessions,id),owned,source)}}
            entered.await();job.cancel();release.complete(Unit);job.join()
        }
        assertFalse(obs.settlement?.attempted==true);assertEquals("before",File(root,"a.txt").readText())
        assertEquals(0,history.depth());assertTrue(pending.snapshots.single().backup.exists())
    }

}
