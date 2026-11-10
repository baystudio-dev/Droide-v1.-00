package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolHistoryPublicationCleanupTest {
    @Test fun stopTokenAfterRenameOnlyAllowsAttributedJournalCleanup() = runBlocking {
        val parent=Files.createTempDirectory("tool-cleanup-").toFile()
        try {
            val root=File(parent,"workspace").apply { mkdir() }
            val sessions=SessionManager(root);val id=sessions.create().id
            val run=java.util.UUID.randomUUID().toString()
            val owner=OwnedResponseContentStore.Owner(id,run,"call")
            val store=OwnedResponseContentStore(File(parent,"owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0))
            val owned=store.authorize(owner)
            val original=buildJsonObject { put("path","a.txt");put("content","large text\n".repeat(15000)) }.toString()
            val arg=owned.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { it.append(original);it.sealCompleted { StreamedJsonValidator.requireObject(it) }.content }
            val argument=StreamedFileArgumentExtractor.Source(arg.handle,arg.byteLength,arg.utf16Length,arg.sha256,arg.kind)
            val extracted=StreamedFileArgumentExtractor.extract(owned,arg.handle,StreamedFileArgumentExtractor.Tool.WRITE_FILE,"call")
            val content=requireNotNull(extracted.content)
            var stopped=false;var mutations=0
            val barrier=object:StreamedDiskCommitAuthority { override suspend fun commitDiskOnly(path:String,action:()->Unit) { action();mutations++;stopped=true } }
            val files=FileRepository(root);val edits=EditHistory(files,streamCommitAuthority=barrier)
            val before=StreamFileVersion.capture(root,"a.txt");val backup=edits.prepareStream("a.txt",before)
            val observation=StreamedTextEditObservation(files,"a.txt",before,StreamContentVersion(true,content.bytes,content.sha256),argument.sha256,barrier,{})
            val call=AgentStreamedTextEditCall(sessions,SessionTextEditEvidence.Owner(id,run,"call","tool:1","write_file"),{true},{_,_->})
            val journal=ToolCallHistoryJournal(File(parent,"journal"),store) { if(stopped)throw java.util.concurrent.CancellationException("Stop") }
            val pending=journal.recordCompleted(owner,"write_file",argument)
            journal.approveStreamed(pending,"a".repeat(64),observation) {}
            val execution=journal.beginExecution(pending) {}
            StreamedDiskEditTransaction.execute(files,edits,backup,observation,call,owned,content)
            assertTrue(stopped);assertEquals(1,mutations)
            val proof=call.publicationProof(observation)
            assertThrows(java.util.concurrent.CancellationException::class.java) { journal.settle(execution,buildJsonObject {}) }
            val foreignJournal=ToolCallHistoryJournal(File(parent,"journal"),store)
            assertThrows(IllegalArgumentException::class.java) { foreignJournal.checkpointCommitted(execution,proof) }
            journal.checkpointCommitted(execution,proof)
            val restarted=ToolCallHistoryJournal(File(parent,"journal"),store)
            assertEquals(ToolCallHistoryJournal.State.SETTLED,restarted.state(pending.locator))
            assertEquals(content.sha256,StreamFileVersion.capture(root,"a.txt").sha256)
            assertThrows(IllegalArgumentException::class.java) { restarted.beginExecution(pending) {} }
            assertEquals(1,mutations)
        } finally { parent.deleteRecursively() }
    }
}
