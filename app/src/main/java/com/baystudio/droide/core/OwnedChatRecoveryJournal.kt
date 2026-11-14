package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Informational interrupted-transport evidence. Never an execution, replay or stage-promotion API.
 * Deliberately does not open the cancelled store: unresolved stages remain explicitly unresolved.
 */
internal class OwnedChatRecoveryJournal(private val root:File) {
    @Serializable data class Stage internal constructor(val callId:String?,val handle:String,
        val disposition:String,val kind:String?=null,val bytes:Long?=null,val utf16:Long?=null,val sha256:String?=null,
        val storedComplete:Boolean?=null,val tailStatus:String?=null,val inputHadFailed:Boolean?=null) {
        override fun toString()="RecoveryStage(disposition=$disposition,kind=$kind,bytes=$bytes,handle=redacted)"
    }
    @Serializable private data class Manifest(val version:Int=1,val session:String,val run:String,val response:String,
        val provider:String,val model:String,val endpoint:String,val protocol:String,val count:Int,val recordsSha256:String)
    data class Snapshot(val id:String,val stages:List<Stage>)
    private val json=Json { encodeDefaults=true }
    private fun directory(id:String):File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        ChatMediaStoragePaths.requireNoLinks(root);Files.createDirectories(root.toPath())
        return File(root,id).also(ChatMediaStoragePaths::requireNoLinks)
    }
    private fun forceDirectory(file:File) = java.nio.channels.FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    private fun write(file:File,bytes:ByteArray) {
        require(bytes.size<=8192) { "Recovery metadata exceeds supported record schema" }
        java.io.FileOutputStream(file).use { it.write(bytes);it.fd.sync() }
    }
    private fun read(file:File):ByteArray {
        ChatMediaStoragePaths.requireNoLinks(file)
        return Files.newInputStream(file.toPath(),NOFOLLOW_LINKS).use { input ->
            val out=java.io.ByteArrayOutputStream();val b=ByteArray(4096)
            while(out.size()<=8192) { val n=input.read(b,0,minOf(b.size,8193-out.size()));if(n<0)break;check(n>0);out.write(b,0,n) }
            require(out.size()<=8192) { "Recovery metadata record is invalid" };out.toByteArray()
        }
    }
    private fun digestBytes(digest:MessageDigest,bytes:ByteArray) {
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
    }
    private fun hex(digest:MessageDigest)=digest.digest().joinToString("") { "%02x".format(it) }
    fun persist(owner:OwnedResponseContentStore.Owner,target:ProviderReplayTarget,recovery:OwnedChatStreamAccumulator.Recovery):String {
        require(target.endpointIdentity.matches(Regex("endpoint-sha256:[a-f0-9]{64}"))) { "Recovery metadata requires a non-secret endpoint fingerprint" }
        require(recovery.checkpoints.size==recovery.checkpointOwners.size) { "Missing recovery ownership association" }
        val checkpointed=recovery.checkpoints.zip(recovery.checkpointOwners).map { (checkpoint,stage) ->
            require(stage.handle.serialized==checkpoint.content.handle.serialized)
            require(checkpoint.content.state==OwnedResponseContentStore.State.INCOMPLETE)
            stage.callId?.let { OwnedResponseContentStore.Owner(owner.sessionId,owner.runId,it) }
            Stage(stage.callId,stage.handle.serialized,"CHECKPOINTED_INCOMPLETE",checkpoint.content.kind.name,
                checkpoint.content.byteLength,checkpoint.content.utf16Length,checkpoint.content.sha256,false,
                checkpoint.tailStatus.name,checkpoint.inputHadFailed)
        }
        val unresolved=recovery.uncheckpointed.map { stage ->
            stage.callId?.let { OwnedResponseContentStore.Owner(owner.sessionId,owner.runId,it) }
            Stage(stage.callId,stage.handle.serialized,"UNRESOLVED")
        }
        val stages=checkpointed+unresolved
        require(stages.size<=2048) { "Recovery stage count exceeds the admitted Chat schema" }
        require(stages.map { it.handle }.toSet().size==stages.size) { "Ambiguous duplicate recovery stage" }
        val id=java.util.UUID.randomUUID().toString();val destination=directory(id)
        val pending=Files.createTempDirectory(root.toPath(),".pending-").toFile()
        var primaryFailure:Throwable?=null
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            stages.forEachIndexed { index,stage ->
                val bytes=json.encodeToString(Stage.serializer(),stage).toByteArray(Charsets.UTF_8)
                write(File(pending,"stage-$index.json"),bytes);digestBytes(digest,bytes)
            }
            val manifest=Manifest(session=owner.sessionId,run=owner.runId,response=owner.callId,
                provider=target.providerId,model=target.modelId,endpoint=target.endpointIdentity,protocol=target.protocol.name,
                count=stages.size,recordsSha256=hex(digest))
            write(File(pending,"manifest.json"),json.encodeToString(Manifest.serializer(),manifest).toByteArray(Charsets.UTF_8))
            forceDirectory(pending);Files.move(pending.toPath(),destination.toPath(),ATOMIC_MOVE);forceDirectory(root)
            return id
        } catch(failure:Throwable) { primaryFailure=failure;throw failure }
        finally {
            if(pending.exists()) try { PathSecurity.deleteTreeNoFollow(pending) }
            catch(cleanup:Throwable) { if(primaryFailure!=null) primaryFailure.addSuppressed(cleanup) else throw cleanup }
        }
    }
    fun reopen(id:String,owner:OwnedResponseContentStore.Owner,target:ProviderReplayTarget):Snapshot {
        val dir=directory(id);val manifest=json.decodeFromString(Manifest.serializer(),read(File(dir,"manifest.json")).toString(Charsets.UTF_8))
        require(manifest.version==1 && manifest.session==owner.sessionId && manifest.run==owner.runId && manifest.response==owner.callId)
        require(manifest.provider==target.providerId && manifest.model==target.modelId && manifest.endpoint==target.endpointIdentity && manifest.protocol==target.protocol.name)
        require(manifest.count in 0..2048)
        val digest=MessageDigest.getInstance("SHA-256")
        val stages=(0 until manifest.count).map { index ->
            val bytes=read(File(dir,"stage-$index.json"));digestBytes(digest,bytes)
            json.decodeFromString(Stage.serializer(),bytes.toString(Charsets.UTF_8)).also {
                OwnedResponseContentStore.Handle.parse(it.handle)
                require(it.disposition=="CHECKPOINTED_INCOMPLETE" || it.disposition=="UNRESOLVED")
                require(it.storedComplete!=true) { "Recovery records cannot promote semantic completion" }
            }
        }
        require(hex(digest)==manifest.recordsSha256) { "Recovery metadata changed" }
        return Snapshot(id,stages)
    }
}
