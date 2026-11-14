package com.baystudio.droide.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** External Android asset/provider/media-store boundaries only. The handbook handler is compiled unchanged. */
internal class HandbookAssets(val root: File) {
    val opened = mutableListOf<String>()
    var replace: ((String, ByteArray) -> ByteArray)? = null
    fun open(path: String): InputStream {
        opened += path
        val bytes = root.resolve(path).readBytes()
        return (replace?.invoke(path, bytes) ?: bytes).inputStream()
    }
}
internal class HandbookContext(val assets: HandbookAssets)
internal data class AgentConfig(val vision: Boolean = true)
internal data class ChatMediaRef(val id: String, val path: String, val owner: String, val bytes: ByteArray)
internal data class AgentImageRef(val path: String, val sha256: String, val mediaId: String)
internal object AgentTools {
    class Ctx(val workDir: File, val appContext: HandbookContext?, val sessionId: String? = "owner",
        val llmConfig: AgentConfig = AgentConfig(), val mediaRequestCurrent: () -> Boolean = { true },
        val onImageReady: ((List<AgentImageRef>) -> Unit)? = null) {
        var handbookBudget = DroideHandbookBudget()
    }
}
internal class ChatMediaStore(val root: File) {
    data class State(val refs: MutableList<ChatMediaRef> = mutableListOf(), var afterImport: () -> Unit = {}, var afterStage: () -> Unit = {}, var stageCalls: Int = 0)
    val state get() = states.getOrPut(root.absolutePath) { State() }
    suspend fun import(ownerId: String, name: String, mimeType: String, input: InputStream): ChatMediaRef {
        check(mimeType == "image/jpeg" || mimeType == "text/plain")
        val id = UUID.randomUUID().toString()
        val ref = ChatMediaRef(id, ".droide/chat-media/blobs/$id/$name", ownerId, input.readBytes())
        state.refs += ref
        state.afterImport()
        return ref
    }
    fun release(owner: String, ref: ChatMediaRef) { check(owner == ref.owner); state.refs.remove(ref) }
    companion object { val states = mutableMapOf<String, State>() }
}
internal object VisionSupport {
    const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    fun providerAcceptsImageUrl(config: AgentConfig) = config.vision
    fun sniff(bytes: ByteArray): String? = if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) "image/jpeg" else null
    suspend fun stage(workDir: File, paths: List<String>, config: AgentConfig, ownerId: String?): List<AgentImageRef> {
        check(config.vision)
        val state = ChatMediaStore(workDir).state
        state.stageCalls++
        val refs = paths.map { path ->
            val ref = state.refs.single { it.path == path && it.owner == ownerId }
            val sha = MessageDigest.getInstance("SHA-256").digest(ref.bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            AgentImageRef(path, sha, ref.id)
        }
        state.afterStage()
        return refs
    }
}
/** Unrelated service final-step text boundary; runtime prompt composition remains exact production. */
internal object AgentService { const val FINAL_STEP_INSTRUCTION = "HOST FIXTURE FINAL STEP" }
