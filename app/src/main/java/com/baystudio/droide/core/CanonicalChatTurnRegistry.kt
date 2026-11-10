package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Private durable association produced only from a terminal-admitted Chat result. */
internal class CanonicalChatTurnRegistry(private val root: File, private val store: OwnedResponseContentStore,
    private val journal: ToolCallHistoryJournal) {
    @Serializable private data class Auxiliary(val handle: String,val kind: String,val bytes: Long,val chars: Long,val hash: String)
    @Serializable private data class Record(val session: String,val run: String,val response: String,
        val provider: String,val model: String,val endpoint: String,val handle: String,val bytes: Long,val chars: Long,val hash: String,
        val calls: List<ToolCallHistoryJournal.Locator>,val auxiliary: List<Auxiliary> = emptyList())
    class BoundTurn internal constructor(val responseSession: OwnedResponseContentStore.Session,
        val assistant: OwnedNormalizedResponse.PrivateReplay,val journal: ToolCallHistoryJournal,
        val calls: List<ToolCallHistoryJournal.Locator>)
    internal fun nativeAssistantRegistry() = NativeAssistantRegistry(File(root.parentFile,"native-assistants"),store,journal)
    private val json=Json { encodeDefaults=true }
    private fun file(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        ChatMediaStoragePaths.requireNoLinks(root);Files.createDirectories(root.toPath())
        return File(root,"$id.json").also(ChatMediaStoragePaths::requireNoLinks)
    }
    fun recordAccepted(result: OwnedChatResult,responseOwner: OwnedResponseContentStore.Owner,
        calls: List<ToolCallHistoryJournal.Locator>): String {
        require(result.target.protocol==ProviderReplayTarget.Protocol.OPENAI_CHAT)
        require(calls.size==result.calls.size)
        if(calls.isEmpty()) require(result.finishReason=="stop" && result.hasMeaningfulPublicText && !result.hasRefusal) {
            "Empty or refused final responses are not admitted as canonical completed answers"
        } else require(result.finishReason=="tool_calls" && !result.hasRefusal)
        require(calls.map { it.callId }.toSet().size==calls.size)
        calls.zip(result.calls).forEach { (saved,accepted) ->
            require(saved.sessionId==responseOwner.sessionId && saved.runId==responseOwner.runId && saved.callId==accepted.id && saved.name==accepted.name)
            val (authorized,source)=journal.recordedArguments(saved)
            accepted.arguments.verify(authorized)
            require(source.handle.serialized==accepted.arguments.handle.serialized && source.sha256==accepted.arguments.sha256 && source.bytes==accepted.arguments.bytes)
        }
        val native=result.nativeAssistantMessage
        require(native.kind==OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION)
        native.verify(store.authorize(responseOwner))
        val auxiliary=(result.auxiliarySources + result.publicText).distinctBy { it.handle.serialized }.map {
            require(it.kind==OwnedResponseContentStore.Kind.ANSWER || it.kind==OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION)
            it.verify(store.authorize(responseOwner))
            Auxiliary(it.handle.serialized,it.kind.name,it.bytes,it.utf16Units,it.sha256)
        }
        val r=Record(responseOwner.sessionId,responseOwner.runId,responseOwner.callId,result.target.providerId,result.target.modelId,
            result.target.endpointIdentity,native.handle.serialized,native.bytes,native.utf16Units,native.sha256,calls,auxiliary)
        return publish(r)
    }
    private fun publish(r: Record): String {
        val encoded=json.encodeToString(Record.serializer(),r).toByteArray()
        require(encoded.size<=65536) { "Canonical turn metadata exceeds supported schema" }
        val id=java.util.UUID.randomUUID().toString();val target=file(id)
        val temp=Files.createTempFile(root.toPath(),".turn-",".tmp").toFile()
        try { java.io.FileOutputStream(temp).use { it.write(encoded);it.fd.sync() }
            Files.move(temp.toPath(),target.toPath(),ATOMIC_MOVE)
            java.nio.channels.FileChannel.open(root.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        } finally { temp.delete() }
        return id
    }
    /** A fork copies both source ownership and call journal records, never runtime approvals. */

    fun copyForFork(id: String, owner: OwnedResponseContentStore.Owner, targetIdentity: ProviderReplayTarget,
        target: CanonicalChatTurnRegistry, targetOwner: OwnedResponseContentStore.Owner): String {
        require(targetOwner.sessionId != owner.sessionId)
        val bound=reopen(id,owner,targetIdentity)
        val calls=bound.calls.map { journal.copyForFork(it,target.journal,targetOwner.copy(callId=it.callId)) }
        val dest=target.store.authorize(targetOwner)
        val copied=dest.begin(OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION).use { writer ->
            bound.responseSession.openBytes(bound.assistant.reference.handle).use { input ->
                val b=ByteArray(8192);while(true) { val n=input.read(b);if(n<0)break;check(n>0);writer.appendUtf8(b,0,n) }
            }
            writer.sealCompleted { StreamedJsonValidator.requireObject(it) }.content
        }
        require(copied.sha256==bound.assistant.reference.sha256 && copied.byteLength==bound.assistant.reference.byteLength)
        return target.publish(Record(targetOwner.sessionId,targetOwner.runId,targetOwner.callId,targetIdentity.providerId,
            targetIdentity.modelId,targetIdentity.endpointIdentity,copied.handle.serialized,copied.byteLength,copied.utf16Length,copied.sha256,calls))
    }
    /** Trusted current owner is supplied separately; neither locator nor record grants workspace access. */
    fun reopen(id: String,owner: OwnedResponseContentStore.Owner,target: ProviderReplayTarget): BoundTurn {
        val bytes=Files.newInputStream(file(id).toPath(),NOFOLLOW_LINKS).use { input ->
            val out=java.io.ByteArrayOutputStream();val b=ByteArray(4096)
            while(out.size()<=65536) { val n=input.read(b,0,minOf(b.size,65537-out.size()));if(n<0)break;check(n>0);out.write(b,0,n) }
            require(out.size()<=65536);out.toByteArray()
        }
        val r=json.decodeFromString(Record.serializer(),bytes.toString(Charsets.UTF_8))
        require(r.session==owner.sessionId && r.run==owner.runId && r.response==owner.callId)
        require(target.protocol==ProviderReplayTarget.Protocol.OPENAI_CHAT && r.provider==target.providerId && r.model==target.modelId && r.endpoint==target.endpointIdentity)
        r.calls.forEach { require(it.sessionId==owner.sessionId && it.runId==owner.runId);journal.recordedArguments(it) }
        val session=store.authorize(owner)
        val handle=OwnedResponseContentStore.Handle.parse(r.handle)
        val source=StreamedFileArgumentExtractor.Source(handle,r.bytes,r.chars,r.hash,OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION)
        source.verify(session)
        // Semantic association was durably admitted from OwnedChatResult, not reconstructed from model
        // text.
        val replay=OwnedNormalizedResponse.PrivateReplay.admit(session,handle,target) { StreamedJsonValidator.requireObject(it) }
        return BoundTurn(session,replay,journal,r.calls)
    }
}
