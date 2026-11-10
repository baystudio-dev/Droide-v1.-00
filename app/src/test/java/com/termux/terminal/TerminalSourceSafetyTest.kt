package com.termux.terminal

import android.app.Application
import android.os.Message
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Requires reviewed source classes (TEST-ONLY host JAR or rebuilt AAR); never starts JNI or a child process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TerminalSourceSafetyTest {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()

    private fun client(finished: () -> Unit = {}): TerminalSessionClient = Proxy.newProxyInstance(
        TerminalSessionClient::class.java.classLoader, arrayOf(TerminalSessionClient::class.java),
    ) { _, method, _ -> if (method.name == "onSessionFinished") finished(); null } as TerminalSessionClient

    private fun emulator(columns: Int = 20, rows: Int = 4): TerminalEmulator = TerminalEmulator(
        object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }, columns, rows, 100, client(),
    )

    private fun feed(model: TerminalEmulator, text: String) {
        val bytes = text.encodeToByteArray()
        var offset = 0
        do {
            offset += model.appendBounded(bytes, offset, bytes.size - offset, TerminalEmulator.INPUT_WORK_BUDGET)
        } while (offset < bytes.size || model.hasPendingInputWork())
    }

    @Test fun repeatYieldsWithExactPendingCountAndPreservesFollowingInputOrder() {
        val model = emulator()
        val bytes = "x\u001b[600bZ".encodeToByteArray()
        val consumed = model.appendBounded(bytes, 0, bytes.size, 8)
        assertTrue(consumed < bytes.size)
        assertTrue(model.pendingRepeatCount in 598..600)
        assertFalse(model.screen.transcriptText.contains("Z"))
        var offset = consumed
        do {
            offset += model.appendBounded(bytes, offset, bytes.size - offset, TerminalEmulator.INPUT_WORK_BUDGET)
        } while (offset < bytes.size || model.hasPendingInputWork())
        assertTrue(model.screen.transcriptText.endsWith("Z"))
    }

    @Test fun scrollExpansionAlsoYieldsAndCanBeCancelledWithoutFinishingIt() {
        val model = emulator()
        val bytes = "\u001b[900S".encodeToByteArray()
        model.appendBounded(bytes, 0, bytes.size, bytes.size)
        assertEquals(900, model.pendingScrollCount)
        model.appendBounded(byteArrayOf(), 0, 0, 3)
        assertEquals(897, model.pendingScrollCount)
        model.cancelPendingInputWork()
        assertFalse(model.hasPendingInputWork())
    }

    @Test fun overLimitExpansionIsExplicitlyRejectedWithoutExecutingItsLoop() {
        val model = emulator()
        feed(model, "a\u001b[${TerminalEmulator.MAX_EXPANDED_OPERATIONS_PER_SEQUENCE + 1}bZ")
        assertEquals("aZ", model.screen.transcriptText)
        assertFalse(model.hasPendingInputWork())
        assertTrue(model.outputLimitNotice.contains("expansion over 1000000 operations rejected"))
    }

    @Test fun hugeNumericParametersSaturateAndCursorArithmeticCannotWrap() {
        val model = emulator()
        feed(model, "\u001b[999999999999999999999C!")
        assertEquals(19, model.cursorCol)
        assertTrue(model.hasSaturatedNumericArgument())
        assertTrue(model.outputLimitNotice.contains("2147483647"))
        assertTrue(model.screen.transcriptText.endsWith("!"))
    }

    @Test fun ordinaryExpansionAndSplitUtf8RemainExact() {
        val model = emulator()
        val bytes = "é\u001b[2b\r\n😀done".encodeToByteArray()
        bytes.forEach { byte ->
            val part = byteArrayOf(byte)
            model.appendBounded(part, 0, 1, TerminalEmulator.INPUT_WORK_BUDGET)
            while (model.hasPendingInputWork()) model.appendBounded(part, 1, 0, TerminalEmulator.INPUT_WORK_BUDGET)
        }
        assertEquals("é́́\n😀done", model.screen.transcriptText)
        assertEquals("", model.outputLimitNotice)
    }

    @Test fun closeForDrainPreservesAcceptedBytesAndRejectsNewWrites() {
        val queue = ByteQueue(8)
        assertTrue(queue.write(byteArrayOf(1, 2, 3), 0, 3))
        queue.closeForDrain()
        assertFalse(queue.write(byteArrayOf(4), 0, 1))
        val bytes = ByteArray(8)
        assertEquals(3, queue.read(bytes, false))
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes.copyOf(3))
        assertEquals(-1, queue.read(bytes, false))
    }

    private fun session(finished: () -> Unit): TerminalSession = TerminalSession(
        "/not-started", "/", emptyArray(), emptyArray(), 100, client(finished),
    ).also {
        it.mEmulator = emulator(); it.mShellPid = 123
        // Pure drain fixture: no JNI/worker exists; explicitly acknowledge its absent writer.
        dispatch(it, TerminalSession.MSG_WRITER_FINISHED)
    }

    private fun dispatch(session: TerminalSession, what: Int, value: Any? = null) {
        session.mMainThreadHandler.handleMessage(Message.obtain().apply { this.what = what; obj = value })
    }

    @Test fun waitpidBeforeDelayedReaderTailDoesNotCompleteOrLoseTheTail() {
        var finished = 0
        val session = session { finished++ }
        session.mProcessToTerminalIOQueue.write("head".encodeToByteArray(), 0, 4)
        dispatch(session, TerminalSession.MSG_PROCESS_EXITED, 0)
        assertEquals(0, finished)
        session.mProcessToTerminalIOQueue.write("TAIL".encodeToByteArray(), 0, 4)
        dispatch(session, TerminalSession.MSG_READER_FINISHED)
        dispatch(session, TerminalSession.MSG_NEW_INPUT)
        assertEquals(1, finished)
        assertTrue(session.isOutputComplete)
        assertTrue(session.emulator.screen.transcriptText.contains("headTAIL"))
        dispatch(session, TerminalSession.MSG_READER_FINISHED)
        assertEquals(1, finished)
    }

    @Test fun completionWaitsForExpandedParserWorkAfterReaderEof() {
        var finished = 0
        val session = session { finished++ }
        val bytes = "x\u001b[900bZ".encodeToByteArray()
        session.mProcessToTerminalIOQueue.write(bytes, 0, bytes.size)
        dispatch(session, TerminalSession.MSG_PROCESS_EXITED, 0)
        dispatch(session, TerminalSession.MSG_READER_FINISHED)
        assertEquals(0, finished)
        repeat(5) { dispatch(session, TerminalSession.MSG_NEW_INPUT) }
        assertEquals(1, finished)
        assertTrue(session.isOutputComplete)
        assertTrue(session.emulator.screen.transcriptText.contains("Z"))
    }

    @Test fun inheritedPtyTimeoutHasOneExplicitIncompleteReceipt() {
        var finished = 0
        val session = session { finished++ }
        dispatch(session, TerminalSession.MSG_PROCESS_EXITED, 0)
        assertEquals(0, finished)
        dispatch(session, TerminalSession.MSG_DRAIN_TIMEOUT)
        assertEquals(1, finished)
        assertFalse(session.isOutputComplete)
        assertTrue(session.emulator.screen.transcriptText.contains("output incomplete"))
        dispatch(session, TerminalSession.MSG_READER_FINISHED)
        dispatch(session, TerminalSession.MSG_DRAIN_TIMEOUT)
        assertEquals(1, finished)
    }

    @Test fun smallRealArrayBudgetEvictsOldestHistoryAndReportsIt() {
        val buffer = TerminalBuffer(20, 100, 2, 4096)
        repeat(20) { index ->
            buffer.setChar(0, 1, 'A'.code + index, 0L)
            buffer.scrollDownOneLine(0, 2, 0L)
        }
        assertTrue(buffer.wasHistoryEvictedForMemory())
        assertTrue(buffer.retainedBytes <= 4096)
        assertTrue(buffer.activeTranscriptRows < 20)
        assertTrue(buffer.transcriptText.contains("T"))
        buffer.clearTranscript()
        assertEquals(0, buffer.activeTranscriptRows)
        assertTrue(buffer.retainedBytes <= 4096)
    }

    @Test fun viewportAdmissionIsFiniteAndMakesItsAdjustmentVisible() {
        val model = emulator(Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(model.mColumns <= TerminalMemoryBudget.MAX_COLUMNS)
        assertTrue(model.mRows <= TerminalMemoryBudget.MAX_ROWS)
        assertTrue(model.mColumns * model.mRows <= TerminalMemoryBudget.MAX_VISIBLE_CELLS)
        assertTrue(model.outputLimitNotice.contains("geometry limited"))
    }
    @Test fun inputWriterAcknowledgementIsRequiredBeforeCleanCompletion() {
        var finished = 0
        val session = TerminalSession("/not-started", "/", emptyArray(), emptyArray(), 100, client { finished++ })
            .also { it.mEmulator = emulator(); it.mShellPid = 123 }
        dispatch(session, TerminalSession.MSG_PROCESS_EXITED)
        dispatch(session, TerminalSession.MSG_READER_FINISHED)
        assertEquals(0, finished)
        dispatch(session, TerminalSession.MSG_WRITER_FINISHED)
        assertEquals(1, finished)
        assertEquals(0, session.inputWriteFailureReason)
    }

    @Test fun absentWriterAcknowledgementProducesUnconfirmedInputAtDeadline() {
        var finished = 0
        val session = TerminalSession("/not-started", "/", emptyArray(), emptyArray(), 100, client { finished++ })
            .also { it.mEmulator = emulator(); it.mShellPid = 123 }
        dispatch(session, TerminalSession.MSG_PROCESS_EXITED)
        dispatch(session, TerminalSession.MSG_READER_FINISHED)
        dispatch(session, TerminalSession.MSG_DRAIN_TIMEOUT)
        assertEquals(1, finished)
        assertEquals(5, session.inputWriteFailureReason)
        assertTrue(session.emulator.screen.transcriptText.contains("input incomplete/unconfirmed"))
    }

    @Test fun permanentReaderFailureIsVisibleBeforeTheChildExits() {
        var finished = 0
        val session = session { finished++ }
        session.mMainThreadHandler.handleMessage(Message.obtain().apply {
            what = TerminalSession.MSG_READER_FINISHED
            arg1 = 1
        })
        assertEquals(0, finished)
        assertTrue(session.emulator.screen.transcriptText.contains("PTY reader failed"))
        dispatch(session, TerminalSession.MSG_PROCESS_EXITED, 1)
        assertEquals(1, finished)
        assertFalse(session.isOutputComplete)
    }

    @Test fun aggregateReservationCountsReachableHistoryAndIncludesReflowSpace() {

        val field = TerminalMemoryBudget::class.java.getDeclaredField("owners").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val registry = field.get(null) as java.util.WeakHashMap<TerminalEmulator, Boolean>
        val previous = java.util.WeakHashMap(registry)
        registry.clear()
        try {
            val live = (0 until 8).map { emulator(2, 2) }
            assertEquals(8, live.size)
            assertEquals(64L * 1024 * 1024, live.size * 2 * TerminalMemoryBudget.EMULATOR_BYTES)
            try {
                emulator(2, 2)
                fail("A ninth retained emulator must not bypass the aggregate reservation")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("memory budget"))
            }
            // Finished-but-retained history cannot be unaccounted merely by clearing pending work.
            live.forEach { it.cancelPendingInputWork() }
            assertEquals(8, registry.size)
        } finally {
            registry.clear()
            registry.putAll(previous)
        }
    }

    @Test fun expandingRowsUnderBudgetPressureDoesNotEvictProspectiveVisibleRows() {
        val buffer = TerminalBuffer(20, 100, 2, 8770)
        repeat(20) { column -> repeat(63) { buffer.setChar(column, 1, 0x0301, 0L) } }
        val cursor = intArrayOf(0, 0)
        buffer.resize(20, 17, 100, cursor, 0L, false)

        val enlarged = buffer.mLines[1]
        buffer.mLines[1] = buffer.mLines[16]
        buffer.mLines[16] = enlarged
        buffer.resize(20, 2, 100, cursor, 0L, false)
        buffer.resize(20, 17, 100, cursor, 0L, false)
        repeat(17) { row -> assertNotNull(buffer.mLines[buffer.externalToInternalRow(row)]) }
        val actualBytes = 64L + 8L * buffer.mTotalRows + buffer.mLines.filterNotNull().sumOf { it.retainedBytes() }
        assertEquals(actualBytes, buffer.retainedBytes)
        assertTrue(buffer.retainedBytes in 0..8770)
        buffer.getSelectedText(0, 0, 19, 16)
    }

    @Test fun unterminatedOscCannotSwallowTheFinalIncompleteReceipt() {
        var finished = 0
        val session = session { finished++ }
        val bytes = "head\u001b]2;unterminated title".encodeToByteArray()
        session.mProcessToTerminalIOQueue.write(bytes, 0, bytes.size)
        dispatch(session, TerminalSession.MSG_PROCESS_EXITED, 0)
        dispatch(session, TerminalSession.MSG_READER_FINISHED)
        dispatch(session, TerminalSession.MSG_NEW_INPUT)
        assertEquals(1, finished)
        assertFalse(session.isOutputComplete)
        assertTrue(session.emulator.screen.transcriptText.contains("Process completed"))
        assertTrue(session.emulator.screen.transcriptText.contains("output incomplete"))
    }

    @Test fun originRelativeAttributeRectanglesAreClippedAfterAddingMargins() {
        val model = emulator(80, 24)
        feed(model, "\u001b[11;20r\u001b[?69h\u001b[11;20s\u001b[?6h\u001b[1;1;24;80;1\$r")
        assertEquals(TextStyle.CHARACTER_ATTRIBUTE_BOLD, TextStyle.decodeEffect(model.screen.getStyleAt(10, 10)))
        assertEquals(0, TextStyle.decodeEffect(model.screen.getStyleAt(9, 10)))
        assertEquals(0, TextStyle.decodeEffect(model.screen.getStyleAt(20, 10)))
        assertEquals(0, TextStyle.decodeEffect(model.screen.getStyleAt(10, 20)))
        assertEquals(0, TextStyle.decodeEffect(model.screen.getStyleAt(23, 79)))
    }

}
