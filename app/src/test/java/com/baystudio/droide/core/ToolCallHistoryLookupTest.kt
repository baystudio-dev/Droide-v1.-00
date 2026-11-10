package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolCallHistoryLookupTest {
    @Test fun lookupDoesNotCreateStorageOrRestoreAuthorityAndValidatesOwner() {
        val dir=Files.createTempDirectory("history-lookup-").toFile()
        try {
            val store=OwnedResponseContentStore(File(dir,"owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0))
            val root=File(dir,"journal");val journal=ToolCallHistoryJournal(root,store)
            val owner=OwnedResponseContentStore.Owner("session","run","call")
            assertNull(journal.locate(owner,"write_file"));assertFalse(root.exists())
            val complete=store.authorize(owner).begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {
                it.append("""{"path":"a.txt","content":"test"}""");it.sealCompleted {input->StreamedJsonValidator.requireObject(input)}.content
            }
            val source=StreamedFileArgumentExtractor.Source(complete.handle,complete.byteLength,complete.utf16Length,complete.sha256,complete.kind)
            val pending=journal.recordCompleted(owner,"write_file",source)
            assertEquals(pending.locator,journal.locate(owner,"write_file"))
            journal.verifyPendingArguments(pending,source)
            assertThrows(IllegalArgumentException::class.java) {
                journal.verifyPendingArguments(ToolCallHistoryJournal.Pending(journal,pending.locator),source)
            }
            assertThrows(IllegalArgumentException::class.java) {
                journal.verifyPendingArguments(pending,StreamedFileArgumentExtractor.Source(source.handle,source.bytes+1,source.utf16Units,source.sha256,source.kind))
            }
            assertNull(journal.locate(owner.copy(sessionId="other"),"write_file"))
            assertThrows(IllegalArgumentException::class.java) {journal.locate(owner,"edit_file")}
            journal.settleWithoutExecution(pending,buildJsonObject {put("role","tool");put("tool_call_id","call");put("content","Denied without execution")})
            assertThrows(IllegalArgumentException::class.java) {journal.verifyPendingArguments(pending,source)}
            val restarted=ToolCallHistoryJournal(root,store)
            assertEquals(ToolCallHistoryJournal.State.SETTLED,restarted.state(checkNotNull(restarted.locate(owner,"write_file"))))
            assertThrows(IllegalArgumentException::class.java) {restarted.beginExecution(pending) {}}
        } finally {dir.deleteRecursively()}
    }
}
