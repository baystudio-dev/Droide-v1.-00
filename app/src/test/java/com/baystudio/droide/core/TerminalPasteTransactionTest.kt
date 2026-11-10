package com.baystudio.droide.core

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import java.util.concurrent.CancellationException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TerminalPasteTransactionTest {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()
    private class Fixture(val admit: (ByteArray) -> Boolean = { true }) {
        val sent = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()
        var current = true
        val transaction = TerminalPasteTransaction({ b, o, c ->
            val value = b.copyOfRange(o, o + c)
            if (admit(value)) { sent += value; true } else false
        }, errors::add)
        val emulator = TerminalEmulator(object : TerminalOutput() {
            override fun write(b: ByteArray, o: Int, c: Int) { transaction.write(b, o, c) }
            override fun titleChanged(old: String?, new: String?) = Unit
            override fun onCopyTextToClipboard(text: String) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }, 80, 24, 1_000, null)
        fun bracketed(value: Boolean = true) {
            val bytes = "\u001b[?2004${if (value) 'h' else 'l'}".toByteArray()
            emulator.append(bytes, bytes.size)
        }
        fun paste(text: String) = transaction.paste(text, { current }, produce = emulator::paste)
        fun text() = sent.joinToString("") { it.toString(Charsets.UTF_8) }
    }
    @Test fun actualEmulatorFramedPasteIsOneAdmissionAndPreservesUnicodeWhitespace() {
        val f = Fixture(); f.bracketed()
        assertTrue(f.paste("  日本😀 e\u0301\r\n\tend  \n"))
        assertEquals(1, f.sent.size)
        assertEquals("\u001b[200~  日本😀 e\u0301\r\tend  \r\u001b[201~", f.text())
    }
    @Test fun unframedModeUsesOneAdmissionAndExistingNewlineSemantics() {
        val f = Fixture()
        assertTrue(f.paste("a\r\nb\nc\rd")); assertEquals("a\rb\rc\rd", f.text())
        assertEquals(1, f.sent.size)
    }
    @Test fun actualEmulatorRemovesEscapeAndC1RatherThanAllowingInjectedEndMarker() {
        val f = Fixture(); f.bracketed()
        assertTrue(f.paste("a\u001b[201~\u0085\u009fb"))
        assertEquals("\u001b[200~a[201~b\u001b[201~", f.text())
    }
    @Test fun byteLimitIncludesBothMarkersAndAcceptsExactBoundary() {
        val f = Fixture(); f.bracketed()
        assertTrue(f.paste("a".repeat(65_524)))
        assertEquals(65_536, f.sent.single().size)
    }
    @Test fun bodyThatWouldFitAloneRejectsWholeFramedOperation() {
        val f = Fixture(); f.bracketed()
        assertFalse(f.paste("a".repeat(65_525)))
        assertTrue(f.sent.isEmpty()); assertEquals(1, f.errors.size)
    }
    @Test fun multibyteBodyIsLimitedByUtf8Bytes() {
        for (text in listOf("日".repeat(21_846), "😀".repeat(16_385))) {
            val f = Fixture(); assertFalse(f.paste(text)); assertTrue(f.sent.isEmpty())
        }
    }
    @Test fun oversizedRawInputNeverRunsTheProducer() {
        val f = Fixture(); var called = false
        assertFalse(f.transaction.paste("x".repeat(65_537), { true }) { called = true })
        assertFalse(called); assertTrue(f.sent.isEmpty())
    }
    @Test fun malformedSurrogatesRejectWithoutReplacingCharacters() {
        for (text in listOf("\uD800", "\uDC00", "a\uD800b", "\uD800\uD800")) {
            val f = Fixture(); assertFalse(f.paste(text)); assertTrue(f.sent.isEmpty())
        }
    }
    @Test fun emptyAndSanitizedEmptyBodiesUsePinnedFraming() {
        for (text in listOf("", "\u001b\u0085")) {
            val f = Fixture(); assertTrue(f.paste(text)); assertTrue(f.sent.isEmpty())
            f.bracketed(); assertTrue(f.paste(text)); assertEquals("\u001b[200~\u001b[201~", f.text())
        }
    }
    @Test fun ordinaryKeysRemainOrderedOutsideOnePasteItem() {
        val f = Fixture(); f.bracketed()
        f.transaction.write("before".toByteArray()); f.paste("body"); f.transaction.write("after".toByteArray())
        assertEquals(listOf("before", "\u001b[200~body\u001b[201~", "after"), f.sent.map { it.toString(Charsets.UTF_8) })
    }
    @Test fun closedOrSwitchedOwnerRejectsBeforeProducer() {
        val f = Fixture(); f.current = false
        assertFalse(f.paste("body")); assertTrue(f.sent.isEmpty())
    }
    @Test fun ownerRetiredDuringPreparationRejectsAllCapturedBytes() {
        val f = Fixture()
        assertFalse(f.transaction.paste("body", { f.current }) { f.emulator.paste(it); f.current = false })
        assertTrue(f.sent.isEmpty())
    }
    @Test fun exactCapturedAdmissionCannotRerouteToAReplacement() {
        val f = Fixture(); val old = mutableListOf<String>(); val replacement = mutableListOf<String>()
        var live = true
        val captured: (ByteArray, Int, Int) -> Boolean = { b, o, c -> if (live) { old += String(b, o, c); true } else false }
        assertFalse(f.transaction.paste("body", { true }, captured) { f.emulator.paste(it); live = false })
        assertTrue(old.isEmpty()); assertTrue(replacement.isEmpty()); assertTrue(f.sent.isEmpty())
    }
    @Test fun queueRejectsWholeOperationAndDoesNotReplay() {
        val f = Fixture { false }; f.bracketed()
        assertFalse(f.paste("body")); assertTrue(f.sent.isEmpty()); assertEquals(1, f.errors.size)
    }
    @Test fun preparationExceptionDiscardsCapturedPrefixAndRestoresRawInput() {
        val f = Fixture()
        assertFalse(f.transaction.paste("body", { true }) { f.transaction.write("prefix".toByteArray()); error("private text") })
        assertTrue(f.sent.isEmpty()); assertFalse(f.errors.single().contains("private"))
        assertTrue(f.transaction.write("key".toByteArray())); assertEquals("key", f.text())
    }
    @Test fun cancellationIdentityPropagatesAndClearsCaptureWithoutSending() {
        val f = Fixture(); val cancellation = CancellationException("owner")
        try { f.transaction.paste("body", { true }) { f.transaction.write("prefix".toByteArray()); throw cancellation }; fail() }
        catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertTrue(f.sent.isEmpty()); assertTrue(f.errors.isEmpty())
        assertTrue(f.paste("next")); assertEquals("next", f.text())
    }
    @Test fun nestedPasteInvalidatesOuterAndNeverPublishesEither() {
        val f = Fixture()
        assertFalse(f.transaction.paste("outer", { true }) { f.emulator.paste(it); assertFalse(f.paste("inner")) })
        assertTrue(f.sent.isEmpty()); assertEquals(1, f.errors.size)
    }
    @Test fun unexpectedAdditionalRawWriteInvalidatesPinnedPasteShape() {
        val f = Fixture()
        assertFalse(f.transaction.paste("body", { true }) { f.emulator.paste(it); f.transaction.write("unrelated".toByteArray()) })
        assertTrue(f.sent.isEmpty())
    }
    @Test fun reusedProducerBytesAreCopiedBeforeMutation() {
        val f = Fixture()
        assertTrue(f.transaction.paste("body", { true }) {
            val bytes = "body".toByteArray(); f.transaction.write(bytes); bytes.fill(0)
        })
        assertEquals("body", f.text())
    }
    @Test fun throwingRejectionObserverCannotCorruptNextOperation() {
        val sent = mutableListOf<String>()
        val t = TerminalPasteTransaction({ b, o, c -> sent += String(b,o,c); true }, { error("observer") })
        assertFalse(t.paste("x", { false }) {})
        assertTrue(t.paste("x", { true }) { t.write(it.toByteArray()) }); assertEquals(listOf("x"), sent)
    }
    @Test fun wholePasteOccupiesOneExistingRelaySlotAndPreservesExactSession() = runBlocking {
        val pending = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() { override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { pending += block } }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val ready = CompletableDeferred("captured-session")
        val writes = mutableListOf<Pair<String,String>>()
        val errors = mutableListOf<String>()
        val relay = TerminalInputRelay(scope, ready, { owner, bytes -> writes += owner to bytes.toString(Charsets.UTF_8) }, errors::add, dispatcher)
        val transaction = TerminalPasteTransaction(relay::send, errors::add)
        try {
            repeat(1023) { assertTrue(relay.send(byteArrayOf(65))) }
            assertTrue(transaction.paste("body", { true }) { transaction.write("\u001b[200~".toByteArray()); transaction.write(it.toByteArray()); transaction.write("\u001b[201~".toByteArray()) })
            assertFalse(relay.send(byteArrayOf(66)))
            while(pending.isNotEmpty()) pending.removeAt(0).run()
            assertEquals(1024, writes.size); assertEquals("captured-session" to "\u001b[200~body\u001b[201~", writes.last())
        } finally { relay.close(); scope.cancel(); while(pending.isNotEmpty()) pending.removeAt(0).run() }
    }
}
