package com.baystudio.droide.core

import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SourceArtifactJournalTest {
    @Test fun publishedButUnsyncedCheckpointIsTruthfulAndNeverRestoresExecution() {
        val dir=Files.createTempDirectory("artifact-journal-").toFile()
        try {
            val workspace=dir.resolve("project").apply{mkdir()}
            val admission=OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0)
            val store=OwnedResponseContentStore(dir.resolve("owned").toPath(),admission)
            val owner=OwnedResponseContentStore.Owner("session","run","call")
            val session=store.authorize(owner)
            val original=session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {
                it.append("""{"type":"note","content":"large exact content"}""")
                it.sealCompleted {input->StreamedJsonValidator.requireObject(input)}.content
            }
            val prepared=StreamedArtifactArgumentExtractor.extract(session,original.handle,"call")
            val journal=ToolCallHistoryJournal(dir.resolve("journal"),store)
            val pending=journal.recordCompletedArtifact(owner,prepared.originalArguments)
            assertThrows(IllegalArgumentException::class.java) {journal.approve(pending,"a".repeat(64)) {}}
            val operation=UUID.randomUUID().toString()
            journal.reserveArtifactOperation(pending,operation,prepared.originalType)
            val backend=SourceArtifactPublisher(workspace,journal.artifactStorage(),admission,SourceArtifactPublisher.Publication {from,src,to,dst->
                Files.move(from.resolve(src),to.resolve(dst));AnchoredWorkspaceFiles.MoveReceipt(true,5)
            })
            val observation=backend.prepare(operation,owner,prepared.originalArguments.sha256,prepared.originalType,session,prepared.content.handle)
            journal.approveArtifact(pending,observation,backend) {}
            val execution=journal.beginExecution(pending) {}
            assertThrows(IllegalArgumentException::class.java) {journal.settle(execution,buildJsonObject {put("role","tool");put("tool_call_id","call");put("content","fake success")})}
            val failure=assertThrows(SourceArtifactPublisher.PublicationFailure::class.java) {backend.publish(observation,{})}
            val receipt=checkNotNull(failure.receipt)
            assertFalse(receipt.durabilityConfirmed)
            val result=journal.checkpointArtifactCommitted(execution,receipt)
            assertTrue(result.getValue("content").jsonPrimitive.content.contains("durability unconfirmed"))
            assertTrue(result.getValue("content").jsonPrimitive.content.contains("Do not repeat"))
            val restarted=ToolCallHistoryJournal(dir.resolve("journal"),store)
            assertEquals(ToolCallHistoryJournal.State.SETTLED,restarted.state(checkNotNull(restarted.locate(owner,"artifact"))))
            assertThrows(IllegalArgumentException::class.java) {restarted.beginExecution(pending) {}}
            assertTrue(workspace.resolve(observation.path).exists())
        } finally {dir.deleteRecursively()}
    }    @Test fun normalSettlementUsesFreshHistoricalProofAfterUserDeletesArtifact() {
        val dir=Files.createTempDirectory("artifact-journal-").toFile()
        try {
            val workspace=dir.resolve("project").apply{mkdir()}
            val admission=OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0)
            val store=OwnedResponseContentStore(dir.resolve("owned").toPath(),admission)
            val owner=OwnedResponseContentStore.Owner("session","run","call")
            val session=store.authorize(owner)
            val original=session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {
                it.append("""{"type":"note","content":"large exact content"}""")
                it.sealCompleted {input->StreamedJsonValidator.requireObject(input)}.content
            }
            val prepared=StreamedArtifactArgumentExtractor.extract(session,original.handle,"call")
            val journal=ToolCallHistoryJournal(dir.resolve("journal"),store)
            val pending=journal.recordCompletedArtifact(owner,prepared.originalArguments)
            assertThrows(IllegalArgumentException::class.java) {journal.approve(pending,"a".repeat(64)) {}}
            val operation=UUID.randomUUID().toString()
            journal.reserveArtifactOperation(pending,operation,prepared.originalType)
            val backend=SourceArtifactPublisher(workspace,journal.artifactStorage(),admission,SourceArtifactPublisher.Publication {from,src,to,dst->
                Files.move(from.resolve(src),to.resolve(dst));AnchoredWorkspaceFiles.MoveReceipt(true,0)
            })
            val observation=backend.prepare(operation,owner,prepared.originalArguments.sha256,prepared.originalType,session,prepared.content.handle)
            journal.approveArtifact(pending,observation,backend) {}
            val execution=journal.beginExecution(pending) {}
            assertThrows(IllegalArgumentException::class.java) {journal.settle(execution,buildJsonObject {put("role","tool");put("tool_call_id","call");put("content","fake success")})}
            val receipt=backend.publish(observation,{})
            assertTrue(receipt.durabilityConfirmed)
            Files.delete(workspace.resolve(observation.path).toPath())
            val result=journal.settleArtifact(execution,receipt,buildJsonObject {
                put("role","tool");put("tool_call_id","call");put("content","Created artifact ${observation.id} (note) at ${observation.path}")
            })
            assertTrue(result.getValue("content").jsonPrimitive.content.contains("current content may have changed"))
            assertFalse(result.getValue("content").jsonPrimitive.content.contains("durability unconfirmed"))
            val restarted=ToolCallHistoryJournal(dir.resolve("journal"),store)
            assertEquals(ToolCallHistoryJournal.State.SETTLED,restarted.state(checkNotNull(restarted.locate(owner,"artifact"))))
            assertThrows(IllegalArgumentException::class.java) {restarted.beginExecution(pending) {}}
            assertFalse(workspace.resolve(observation.path).exists())
        } finally {dir.deleteRecursively()}
    }
}
