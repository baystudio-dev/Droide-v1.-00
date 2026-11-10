package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SessionForkHistoryStreamingTest {
    @Test fun exactValidationReadsDemandGeneratedHistoryOnce() {
        val history = GeneratedHistory(20_000)

        assertTrue(SessionForkHistory.isExact(history))

        assertEquals(history.size, history.accesses)
    }

    @Test fun backwardAnnotationOnlyRevisitsHistoryMatchingTheVisibleTail() {
        val history = GeneratedHistory(20_000)
        val visible = (history.size - 6 until history.size).map { AgentMessage("user", "message-$it") }

        val annotated = SessionForkHistory.annotate(visible, history)

        assertEquals((history.size - 5..history.size).toList(), annotated.map { it.forkHistoryEnd })
        assertTrue("Old historical groups must not be rebuilt for six visible messages", history.accesses <= history.size + 30)
    }

    @Test fun verifiedSnapshotAnnotationOnlyLoadsTheVisibleTailAndPrefixBoundary() {
        val history = VerifiedGeneratedHistory(20_000)
        val visible = (history.size - 6 until history.size).map { AgentMessage("user", "message-$it", forkHistoryEnd = 1) }

        assertTrue(SessionForkHistory.isExact(history))
        assertEquals(0, history.accesses)
        val annotated = SessionForkHistory.annotate(visible, history)
        assertEquals((history.size - 5..history.size).toList(), annotated.map { it.forkHistoryEnd })
        assertTrue(history.accesses <= 30)
        val beforeSlice = history.accesses

        val prefix = SessionForkHistory.slice(annotated, history, 0)!!

        assertEquals(history.size - 5, prefix.size)
        assertEquals(beforeSlice + 1, history.accesses)
        assertTrue((prefix as VerifiedSessionHistory).exactVerified)
    }

    @Test fun sliceRemainsAnIndexedLazyPrefixRatherThanCopyingHistoricalEntries() {
        val history = GeneratedHistory(20_000)
        val end = history.size - 100
        val message = AgentMessage("user", "message-${end - 1}", forkHistoryEnd = end)

        val prefix = SessionForkHistory.slice(listOf(message), history, 0)!!

        assertEquals(end, prefix.size)
        assertEquals(history.size + end, history.accesses)
        val readsBeforeAccess = history.accesses
        assertEquals(entry("user", "message-0"), prefix[0])
        assertEquals(entry("user", "message-${end - 1}"), prefix.last())
        assertEquals(readsBeforeAccess + 2, history.accesses)
        assertThrows(IndexOutOfBoundsException::class.java) { prefix[end] }
        assertThrows(IndexOutOfBoundsException::class.java) { prefix[-1] }
    }

    @Test fun exactGrammarMatchesCompleteGroupRecoveryWithoutAcceptingDiscardedEntries() {
        val variants = listOf(
            entry("user", "hello"), entry("assistant", "answer"), entry("system", "policy"),
            entry("unknown", "ignored"), json("""{"content":"no role"}"""),
            calls("a"), calls("a", "b"), calls("a", "a"),
            json("""{"role":"assistant","tool_calls":[{}]}"""),
            json("""{"role":"assistant","tool_calls":[null]}"""),
            json("""{"role":"assistant","tool_calls":[]}"""),
            json("""{"role":"assistant","tool_calls":"not-array"}"""),
            tool("a"), tool("b"), tool("other"),
            json("""{"role":"tool","content":"no id"}"""),
        )
        assertTrue(SessionForkHistory.isExact(emptyList()))
        for (first in variants) for (second in variants) for (third in variants) {
            val history = listOf(first, second, third)
            val complete = AgentHistory.completeGroups(history).flatten()
            val expected = complete == history
            assertEquals("Exact grammar changed for $history", expected, SessionForkHistory.isExact(history))
        }
        assertTrue(SessionForkHistory.isExact(listOf(calls("a", "b"), tool("b"), tool("a"))))
        assertFalse(SessionForkHistory.isExact(listOf(calls("a", "b"), tool("a"))))
        assertFalse(SessionForkHistory.isExact(listOf(calls("a"), tool("a"), tool("a"))))
    }

    @Test fun invisibleEntriesAndPresentationPartsDoNotMoveTheMatchedToolBoundary() {
        val history = listOf(
            entry("system", "policy"),
            entry("user", "build"),
            calls("a", "b", content = "I will edit"),
            tool("b"), tool("a"),
            entry("assistant", "[Background subagent completion; hidden]"),
            entry("assistant", "[Historical conversation checkpoint; hidden]"),
            entry("assistant", "done"),
        )
        val messages = listOf(
            AgentMessage("user", "build"),
            AgentMessage("assistant", "I will edit with a UI suffix"),
            presentation("step", AgentStreamPartKind.STEP),
            toolMessage("b"),
            presentation("text", AgentStreamPartKind.TEXT),
            toolMessage("a"),
            AgentMessage("assistant", "done"),
            presentation("retry", AgentStreamPartKind.RETRY),
        )

        val annotated = SessionForkHistory.annotate(messages, history)

        assertEquals(listOf(2, null, null, null, null, 5, 8, null), annotated.map { it.forkHistoryEnd })
        assertEquals(history.take(5), SessionForkHistory.slice(annotated, history, 5))
        assertNull(SessionForkHistory.slice(annotated, history, 3))
    }

    @Test fun annotationStopsAtAnUnmatchedSemanticMessageInsteadOfSkippingIt() {
        val history = listOf(entry("user", "earlier"), entry("assistant", "later"))
        val messages = listOf(AgentMessage("user", "earlier"), AgentMessage("assistant", "different"))

        assertEquals(listOf(null, null), SessionForkHistory.annotate(messages, history).map { it.forkHistoryEnd })
    }

    @Test fun malformedFullHistoryClearsOldBoundariesAndCannotBeSliced() {
        val history = listOf(entry("user", "build"), calls("pending"))
        val messages = listOf(AgentMessage("user", "build", forkHistoryEnd = 1))

        assertNull(SessionForkHistory.annotate(messages, history).single().forkHistoryEnd)
        assertNull(SessionForkHistory.slice(messages, history, 0))
        assertNull(SessionForkHistory.slice(messages, history, -1))
        assertNull(SessionForkHistory.slice(messages, history, messages.size))
    }

    @Test fun persistedBoundariesInsideToolGroupsAreRejectedButCompleteGroupsAreKept() {
        val history = listOf(entry("user", "build"), calls("a", "b"), tool("a"), tool("b"))

        assertNull(SessionForkHistory.slice(listOf(AgentMessage("assistant", "tool", forkHistoryEnd = 3)), history, 0))
        assertNull(SessionForkHistory.slice(listOf(AgentMessage("assistant", "tool", forkHistoryEnd = 5)), history, 0))
        assertEquals(history, SessionForkHistory.slice(listOf(AgentMessage("assistant", "tool", forkHistoryEnd = 4)), history, 0))
        assertEquals(emptyList<JsonObject>(), SessionForkHistory.slice(listOf(AgentMessage("assistant", "tool", forkHistoryEnd = 0)), history, 0))
    }

    @Test fun verifiedLedgerPrefixCannotCutAToolGroupAcrossPageBoundaries() {
        val root = Files.createTempDirectory("droide-fork-pages-").toFile()
        try {
            val ledger = SessionHistoryLedger(root)
            val first = ledger.writePage(listOf(entry("user", "build"), calls("a", "b")))
            val second = ledger.writePage(listOf(tool("b"), tool("a"), entry("assistant", "done")))
            val unverified = ledger.snapshot(listOf(first, second), emptyList())
            assertTrue(SessionForkHistory.isExact(unverified))
            val verified = ledger.snapshot(listOf(first, second), emptyList(), verified = true)
            fun slice(end: Int) = SessionForkHistory.slice(
                listOf(AgentMessage("assistant", "tool", forkHistoryEnd = end)), verified, 0,
            )

            assertEquals(listOf(entry("user", "build")), slice(1))
            assertNull(slice(2))
            assertNull(slice(3))
            assertEquals(unverified.take(4), slice(4))
            assertEquals(unverified, slice(5))
            assertEquals(5, verified.size)
            assertTrue(SessionForkHistory.isExact(verified))
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private open class GeneratedHistory(override val size: Int) : AbstractList<JsonObject>() {
        var accesses = 0
            private set

        override fun get(index: Int): JsonObject {
            if (index !in 0 until size) throw IndexOutOfBoundsException()
            accesses++
            return entry("user", "message-$index")
        }
    }

    private class VerifiedGeneratedHistory(size: Int) : GeneratedHistory(size), VerifiedSessionHistory {
        override val exactVerified = true
    }

    private fun toolMessage(id: String) = AgentMessage(
        "assistant", "TOOL|write|{}|done", streamPart = AgentStreamPart(
            "tool:$id", 1, AgentStreamPartKind.TOOL, AgentStreamPartState.COMPLETED, toolCallId = id,
        ),
    )

    private fun presentation(id: String, kind: AgentStreamPartKind) = AgentMessage(
        "assistant", "", streamPart = AgentStreamPart(id, 1, kind, AgentStreamPartState.COMPLETED),
    )

    private fun calls(vararg ids: String, content: String = ""): JsonObject = json(
        """{"role":"assistant","content":"$content","tool_calls":[${ids.joinToString(",") { """{"id":"$it"}""" }}]}""",
    )

    private fun tool(id: String) = json("""{"role":"tool","tool_call_id":"$id","content":"result"}""")
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    companion object {
        private fun entry(role: String, content: String) = buildJsonObject {
            put("role", role)
            put("content", content)
        }
    }
}
