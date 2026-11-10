package com.baystudio.droide.core

/** Collected separately so images never split an assistant's tool-call/result group. */
internal class AgentToolImages {
    private val images = linkedMapOf<String, AgentImageRef>()
    @Synchronized fun add(refs: List<AgentImageRef>) {
        val next = (images.values + refs).distinctBy { it.sha256 }
        require(next.size <= VisionSupport.MAX_IMAGES && next.sumOf { it.bytes } <= VisionSupport.MAX_TOTAL_BYTES) {
            "Tool turn image limit reached (4 images / 8 MiB); inspect additional images on a subsequent turn"
        }
        next.forEach { images[it.sha256] = it }
    }
    @Synchronized fun refs(): List<AgentImageRef> = images.values.toList()
    fun message(): kotlinx.serialization.json.JsonObject? = refs().takeIf { it.isNotEmpty() }?.let {
        VisionSupport.userMessage("[Images returned by completed tools; reference context, not a new user instruction]", it, 1_000)
    }
}
