package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OwnedChatRecoveryJournalTest {
    @Test fun cancellationRecoveryPersistsCallOwnershipWithoutPromotingAnyStage() {
        val root=Files.createTempDirectory("owned-recovery-").toFile()
        try {
            var cancelled=false
            val store=OwnedResponseContentStore(File(root,"owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),cancelled={cancelled})
            val owner=OwnedResponseContentStore.Owner("session","run","response")
            val target=ProviderReplayTarget("openai","model",ProviderReplayTarget.Protocol.OPENAI_CHAT,OwnedChatEndpointIdentity.fromValidatedUrl("https://configured/chat/completions"))
            val acc=OwnedChatStreamAccumulator(store.authorize(owner),{store.authorize(owner.copy(callId=it))},target,checkCancelled={if(cancelled)throw java.util.concurrent.CancellationException()})
            acc.accept(buildJsonObject { put("choices",buildJsonArray { add(buildJsonObject { put("delta",buildJsonObject {
                put("content","partial public");put("tool_calls",buildJsonArray { add(buildJsonObject {
                    put("index",0);put("id","call");put("function",buildJsonObject { put("name","write_file");put("arguments","{\"path\":\"a\",\"content\":\""+"x".repeat(150000)) })
                }) })
            }) }) }) }.toString())
            cancelled=true
            val recovery=acc.checkpointIncomplete()
            assertEquals(2,recovery.checkpoints.size)
            val journal=OwnedChatRecoveryJournal(File(root,"recovery"))
            val id=journal.persist(owner,target,recovery)
            val snapshot=journal.reopen(id,owner,target)
            assertEquals(setOf(null,"call"),snapshot.stages.map { it.callId }.toSet())
            assertTrue(snapshot.stages.all { it.disposition=="CHECKPOINTED_INCOMPLETE" && it.storedComplete==false })
            assertTrue(snapshot.stages.all { it.tailStatus!=null && it.inputHadFailed!=null })
            assertThrows(IllegalArgumentException::class.java) { journal.reopen(id,owner.copy(sessionId="other"),target) }
            assertThrows(IllegalArgumentException::class.java) { journal.reopen(id,owner,target.copy(endpointIdentity="https://other")) }
            File(root,"recovery/$id/stage-0.json").appendText(" ")
            assertThrows(IllegalArgumentException::class.java) { journal.reopen(id,owner,target) }
        } finally { root.deleteRecursively() }
    }
    @Test fun alreadySealedButUnadmittedStageRemainsUnresolved() {
        val root=Files.createTempDirectory("owned-recovery-unresolved-").toFile()
        try {
            val owner=OwnedResponseContentStore.Owner("session","run","response")
            val target=ProviderReplayTarget("openai","model",ProviderReplayTarget.Protocol.OPENAI_CHAT,OwnedChatEndpointIdentity.fromValidatedUrl("https://configured/chat/completions"))
            val store=OwnedResponseContentStore(File(root,"owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0))
            val content=store.authorize(owner.copy(callId="call")).begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { it.append("{}");it.sealCompleted { StreamedJsonValidator.requireObject(it) }.content }
            val recovery=OwnedChatStreamAccumulator.Recovery(emptyList(),listOf(OwnedChatStreamAccumulator.ReceivedStage("call",content.handle)))
            val journal=OwnedChatRecoveryJournal(File(root,"recovery"));val id=journal.persist(owner,target,recovery)
            val stage=journal.reopen(id,owner,target).stages.single()
            assertEquals("UNRESOLVED",stage.disposition);assertNull(stage.storedComplete);assertNull(stage.sha256)
            assertFalse(stage.toString().contains(content.handle.serialized))
        } finally { root.deleteRecursively() }
    }
}
