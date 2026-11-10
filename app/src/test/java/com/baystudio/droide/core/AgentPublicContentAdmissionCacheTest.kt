package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class AgentPublicContentAdmissionCacheTest {
    private fun message(): AgentMessage {
        val handle = "orc1.00000000-0000-0000-0000-000000000001." + "a".repeat(43)
        return AgentMessage("assistant", "preview", publicContent = AgentPublicContentRef(opaqueHandle = handle,
            runId = "run", itemId = "item", bytes = 7, utf16Chars = 7, sha256 = "a".repeat(64), complete = true))
    }
    @Test fun unchangedHitChangedDescriptorAndBindingMiss() {
        val cache = AgentPublicContentAdmissionCache(); val original = message(); var calls = 0
        fun verify(owner: String, m: AgentMessage) = cache.verify(owner, listOf(m), {}) { calls++ }
        verify("s", original); verify("s", original); assertEquals(1, calls)
        val ref = requireNotNull(original.publicContent)
        listOf(original.copy(content = "changed"), original.copy(presentationId = "changed"),
            original.copy(publicContent = ref.copy(sha256 = "b".repeat(64))),
            original.copy(publicContent = ref.copy(runId = "other")),
            original.copy(publicContent = ref.copy(complete = false))).forEach { verify("s", it) }
        verify("other", original); assertEquals(7, calls)
        assertThrows(IllegalArgumentException::class.java) { verify("s", original.copy(role = "user")) }
    }
    @Test fun failedCollectionCancellationClearAndBoundedEvictionNeverGrantAdmission() {
        val cache = AgentPublicContentAdmissionCache(1); val m = message(); var calls = 0
        assertThrows(IllegalStateException::class.java) { cache.verify("s", listOf(m, m.copy(content = "fail")), {}) { if(it.content == "fail") error("reject") } }
        cache.verify("s", listOf(m), {}) { calls++ }; assertEquals(1, calls)
        assertThrows(CancellationException::class.java) { cache.verify("s", listOf(m), { throw CancellationException() }) { error("cached") } }
        cache.verify("other", listOf(m), {}) { calls++ }
        cache.verify("s", listOf(m), {}) { calls++ }; assertEquals(3, calls)
        cache.clear(); cache.verify("s", listOf(m), {}) { calls++ }; assertEquals(4, calls)
    }
    @Test fun authorityGenerationChangeAndAbortEvidenceCannotReuseAdmission() {
        val cache = AgentPublicContentAdmissionCache(); val m = message()
        assertThrows(IllegalStateException::class.java) { cache.verify("s", listOf(m), {}) { cache.clear() } }
        var calls = 0
        cache.verify("s", listOf(m), {}) { calls++ }; assertEquals(1, calls)
        val ref = requireNotNull(m.publicContent).copy(complete = false)
        val checkpoint = PublicAnswerAbortCheckpoint(ref, PublicAnswerAbortTailStatus.PRESERVED_AVAILABLE_BYTES, false)
        val partial = m.copy(publicContent = ref, publicAbort = checkpoint)
        cache.verify("s", listOf(partial), {}) { calls++ }
        cache.verify("s", listOf(partial.copy(publicAbort = checkpoint.copy(inputHadFailed = true))), {}) { calls++ }
        assertEquals(3, calls)
        assertThrows(IllegalArgumentException::class.java) { cache.verify("s", listOf(m.copy(publicAbort = checkpoint)), {}) { error("must reject") } }
    }
}
