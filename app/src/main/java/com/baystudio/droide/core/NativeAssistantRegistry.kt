package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Private durable association produced only from a terminal-admitted Chat result. */
internal class NativeAssistantRegistry(private val root: File, private val store: OwnedResponseContentStore, private val journal: ToolCallHistoryJournal? = null) {
    @Serializable internal data class SourceCall(val locator: ToolCallHistoryJournal.Locator, val argumentsHash: String)
    @Serializable private data class Auxiliary(val handle: String,val kind: String,val bytes: Long,val chars: Long,val hash: String)
    @Serializable private data class Record(val session: String,val run: String,val response: String,
        val provider: String,val model: String,val endpoint: String,val handle: String,val bytes: Long,val chars: Long,val hash: String,
        val calls: List<String>,val auxiliary: List<Auxiliary> = emptyList(), val sourceCalls: List<SourceCall> = emptyList())
    class BoundAssistant internal constructor(val owner: OwnedResponseContentStore.Owner,
        val responseSession: OwnedResponseContentStore.Session,
        val assistant: OwnedNormalizedResponse.PrivateReplay,val callIds: List<String>,
        private val journal: ToolCallHistoryJournal? = null, internal val sourceCalls: List<SourceCall> = emptyList()) {
        fun requireSettledSources() {
            sourceCalls.forEach { binding ->
                val j = requireNotNull(journal)
                val (_, arguments) = j.completedArguments(binding.locator)
                require(arguments.sha256 == binding.argumentsHash) { "Native source argument identity changed" }
                j.verifyResult(binding.locator)
            }
        }
        fun settledSourceResult(id: String): kotlinx.serialization.json.JsonObject? {
            val binding = sourceCalls.singleOrNull { it.locator.callId == id } ?: return null
            val j = requireNotNull(journal)
            if (j.state(binding.locator) != ToolCallHistoryJournal.State.SETTLED) return null
            val (_, arguments) = j.completedArguments(binding.locator)
            require(arguments.sha256 == binding.argumentsHash)
            // Recovery receipts are summaries, not arguments/content. Preserve the private result
            // unchanged and require explicit recovery if a legacy receipt cannot fit this envelope.
            val bytes = object : java.io.ByteArrayOutputStream() {
                override fun write(value: Int) { require(count < 262144) { "Settled receipt exceeds safe recovery envelope; private result retained" };super.write(value) }
                override fun write(data: ByteArray, offset: Int, length: Int) {
                    require(length >= 0 && length <= 262144 - count) { "Settled receipt exceeds safe recovery envelope; private result retained" }
                    super.write(data, offset, length)
                }
            }
            j.writeResult(binding.locator, bytes)
            val message = Json.parseToJsonElement(bytes.toString("UTF-8")) as? kotlinx.serialization.json.JsonObject
                ?: error("Private settled result is not an object")
            verifyToolResult(id, message)
            return message
        }
        fun verifyToolResult(id: String, message: kotlinx.serialization.json.JsonObject) {
            val binding = sourceCalls.singleOrNull { it.locator.callId == id } ?: return
            val j = requireNotNull(journal)
            val (_, arguments) = j.completedArguments(binding.locator)
            require(arguments.sha256 == binding.argumentsHash) { "Native source argument identity changed" }
            val expected = message.toString().toByteArray(Charsets.UTF_8)
            var offset = 0
            j.writeResult(binding.locator, object : java.io.OutputStream() {
                override fun write(value: Int) {
                    require(offset < expected.size && expected[offset].toInt().and(255) == value.and(255)) {
                        "Native source result differs from the settled private journal"
                    }
                    offset++
                }
                override fun write(bytes: ByteArray, start: Int, length: Int) {
                    for (i in start until start + length) write(bytes[i].toInt())
                }
            })
            require(offset == expected.size) { "Native source result differs from the settled private journal" }
        }
    }
    private val json=Json { encodeDefaults=true }
    private fun file(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        ChatMediaStoragePaths.requireNoLinks(root);Files.createDirectories(root.toPath())
        return File(root,"$id.json").also(ChatMediaStoragePaths::requireNoLinks)
    }
    /** Replay-only admission: no journal entry, approval, or execution token is created. */
    fun recordAccepted(result: OwnedChatResult,responseOwner: OwnedResponseContentStore.Owner,
        sourceCalls: List<ToolCallHistoryJournal.Pending> = emptyList()): String {
        require(result.target.protocol==ProviderReplayTarget.Protocol.OPENAI_CHAT)
        require(!result.hasRefusal)
        if(result.calls.isEmpty()) require(result.finishReason=="stop" && result.hasMeaningfulPublicText)
        else require(result.finishReason=="tool_calls")
        val calls=result.calls.map { it.id }
        require(calls.all { it.isNotBlank() } && calls.toSet().size==calls.size)
        result.calls.forEach { it.arguments.verify(store.authorize(responseOwner.copy(callId=it.id))) }
        require(sourceCalls.map { it.locator.callId }.toSet().size == sourceCalls.size) { "Duplicate source-call association" }
        val bindings = sourceCalls.map { pending ->
            val j = requireNotNull(journal) { "Source calls require the private journal" }
            val locator = pending.locator
            require(locator.sessionId == responseOwner.sessionId && locator.runId == responseOwner.runId)
            val call = result.calls.single { it.id == locator.callId }
            require(call.name == locator.name)
            j.verifyPendingArguments(pending, call.arguments)
            SourceCall(locator, call.arguments.sha256)
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
            result.target.endpointIdentity,native.handle.serialized,native.bytes,native.utf16Units,native.sha256,calls,auxiliary,bindings)
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
    /** Canonical envelope contains all admitted field data; redundant original auxiliary spools stay retained in source.
     * A fork copies native source ownership only and never creates runtime approvals. */
    fun copyForFork(id: String, owner: OwnedResponseContentStore.Owner, targetIdentity: ProviderReplayTarget,
        target: NativeAssistantRegistry, targetOwner: OwnedResponseContentStore.Owner): String {
        require(targetOwner.sessionId != owner.sessionId)
        val bound=reopen(id,owner,targetIdentity)
        val calls=bound.callIds
        bound.requireSettledSources()
        val copiedBindings = bound.sourceCalls.map { binding ->
            val locator = requireNotNull(journal).copyForFork(binding.locator, requireNotNull(target.journal),
                targetOwner.copy(callId = binding.locator.callId))
            SourceCall(locator, binding.argumentsHash)
        }
        val dest=target.store.authorize(targetOwner)
        val copied=dest.begin(OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION).use { writer ->
            bound.responseSession.openBytes(bound.assistant.reference.handle).use { input ->
                val b=ByteArray(8192);while(true) { val n=input.read(b);if(n<0)break;check(n>0);writer.appendUtf8(b,0,n) }
            }
            writer.sealCompleted { StreamedJsonValidator.requireObject(it) }.content
        }
        require(copied.sha256==bound.assistant.reference.sha256 && copied.byteLength==bound.assistant.reference.byteLength)
        return target.publish(Record(targetOwner.sessionId,targetOwner.runId,targetOwner.callId,targetIdentity.providerId,
            targetIdentity.modelId,targetIdentity.endpointIdentity,copied.handle.serialized,copied.byteLength,copied.utf16Length,copied.sha256,calls,sourceCalls=copiedBindings))
    }
    /** Trusted current owner is supplied separately; neither locator nor record grants workspace access. */
    fun reopen(id: String,owner: OwnedResponseContentStore.Owner,target: ProviderReplayTarget): BoundAssistant {
        val bytes=Files.newInputStream(file(id).toPath(),NOFOLLOW_LINKS).use { input ->
            val out=java.io.ByteArrayOutputStream();val b=ByteArray(4096)
            while(out.size()<=65536) { val n=input.read(b,0,minOf(b.size,65537-out.size()));if(n<0)break;check(n>0);out.write(b,0,n) }
            require(out.size()<=65536);out.toByteArray()
        }
        val r=json.decodeFromString(Record.serializer(),bytes.toString(Charsets.UTF_8))
        require(r.session==owner.sessionId && r.run==owner.runId && r.response==owner.callId)
        require(target.protocol==ProviderReplayTarget.Protocol.OPENAI_CHAT && r.provider==target.providerId && r.model==target.modelId && r.endpoint==target.endpointIdentity)
        require(r.calls.all { it.isNotBlank() } && r.calls.toSet().size==r.calls.size)
        require(r.sourceCalls.map { it.locator.callId }.toSet().size == r.sourceCalls.size)
        r.sourceCalls.forEach { binding ->
            require(binding.locator.callId in r.calls && binding.locator.sessionId == owner.sessionId && binding.locator.runId == owner.runId)
            require(binding.locator.name in setOf("write_file", "edit_file", "write", "edit", "artifact", "apply_patch", "write_stdin", "run_command", "bash", "exec_command") && binding.argumentsHash.matches(Regex("[a-f0-9]{64}")))
            requireNotNull(journal)
        }
        val session=store.authorize(owner)
        val handle=OwnedResponseContentStore.Handle.parse(r.handle)
        val source=StreamedFileArgumentExtractor.Source(handle,r.bytes,r.chars,r.hash,OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION)
        source.verify(session)
        // Semantic association was durably admitted from OwnedChatResult, not reconstructed from model text.
        val replay=OwnedNormalizedResponse.PrivateReplay.admit(session,handle,target) { StreamedJsonValidator.requireObject(it) }
        return BoundAssistant(owner,session,replay,r.calls,journal,r.sourceCalls)
    }
}
