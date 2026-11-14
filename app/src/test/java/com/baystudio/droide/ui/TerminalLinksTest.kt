package com.baystudio.droide.ui

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.baystudio.droide.core.TerminalLinks
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TerminalLinksTest {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()

    private val oauth = "https://accounts.example.test/o/oauth2/v2/auth?redirect_uri=https%3A%2F%2Fapp.test%2Fcb" +
        "&scope=openid%20email&code_challenge=Ab_c-d~e&state=A%2BB%2FC%3D&response_type=code"

    @Test fun preservesOAuthQueryAndPercentEscapesByteForByteAtEveryOffset() {
        val text = "Authorize: <$oauth>"
        for (i in oauth.indices) assertEquals("offset $i", oauth, TerminalLinks.findAt(text, 12 + i))
        assertNull(TerminalLinks.findAt(text, 0))
        assertNull(TerminalLinks.findAt(text, text.lastIndex))
    }

    @Test fun acceptsLocalhostIPv4IPv6PortsAndUppercaseSchemes() {
        for (url in listOf("http://localhost:3000/", "http://127.0.0.1:8080/callback", "http://[::1]:4567/?code=123", "HTTPS://example.test/path")) {
            assertEquals(url, TerminalLinks.findAt(url, url.lastIndex))
        }
    }

    @Test fun refusesOtherSchemesMalformedHostsAndInvalidPortsOrPercentSequences() {
        for (text in listOf("file:///etc/passwd", "javascript:alert(1)", "https:///path", "https://:443/a", "https://example.test:70000/", "https://example.test/%ZZ")) {
            assertNull(text, TerminalLinks.findAt(text, text.lastIndex))
        }
    }

    @Test fun trimsProseWrappersAndRetainsBalancedParentheses() {
        val url = "https://example.test/a_(b)"
        for (text in listOf("($url).", "[$url]", "'$url'", "<$url>", "\"$url\"")) {
            assertEquals(text, url, TerminalLinks.findAt(text, text.indexOf("https") + 10))
        }
        assertNull(TerminalLinks.findAt("($url).", url.length + 1))
    }

    @Test fun preservesQueryAndFragmentPunctuationRatherThanRewritingTokens() {
        for (url in listOf("https://example.test/?value=a,b.;!:", "https://example.test/#fragment.", "https://example.test/?state=a(b)", "https://example.test/?value=o'brien",
            "https://example.test/?state=a)", "https://example.test/?state=a]", "https://example.test/#state=a)", "https://example.test/#state=a]")) {
            assertEquals(url, TerminalLinks.findAt(url, 9))
        }
    }

    @Test fun removesOnlyExplicitProseWrappersOutsideQueryCharacters() {
        for (url in listOf("https://example.test/?state=a", "https://example.test/?state=a)", "https://example.test/?state=a]", "https://example.test/#value=(a)", "https://example.test/?state=a'")) {
            for (text in listOf("($url)", "[$url]", "'$url'", "([$url])", "'($url)'")) {
                assertEquals(text, url, TerminalLinks.findAt(text, text.indexOf("https") + 10))
            }
        }
    }

    @Test fun inspectsOnlyLocalTokenInHugeTranscriptAndRefusesTruncatedOversizeLinks() {
        val prefix = "not a link\n".repeat(100_000)
        val text = prefix + oauth + "\n" + "log output\n".repeat(100_000)
        assertEquals(oauth, TerminalLinks.findAt(text, prefix.length + 25))
        val huge = "https://example.test/?code=" + "a".repeat(17_000)
        assertNull(TerminalLinks.findAt(huge, 10))
        assertNull(TerminalLinks.findAt(huge, huge.lastIndex))
        assertNull(TerminalLinks.findAt(oauth, -1))
        assertNull(TerminalLinks.findAt(oauth, oauth.length))
    }

    @Test fun joinsOnlySoftWrapsForLongOAuthUrlIncludingScrollbackRows() {
        val emulator = emulator(40, 4)
        append(emulator, oauth + "\r\nEnter the authorization code:")
        val first = -emulator.screen.activeTranscriptRows
        assertEquals(oauth, TerminalLinks.atCell(emulator, 5, first))
        assertEquals(oauth, TerminalLinks.atCell(emulator, 10, first + 1))
        assertEquals(oauth, TerminalLinks.atCell(emulator, 0, 0))
    }

    @Test fun explicitlyWrappedUnderlinedLoginUrlRemainsOneExactDestination() {
        val emulator = emulator(40, 20)
        append(emulator, "\u001b[4;34m" + oauth.chunked(40).joinToString("\r\n") + "\u001b[0m\r\nEnter code:")
        assertFalse(emulator.screen.getLineWrap(0))
        for (row in oauth.indices step 40) {
            assertEquals(oauth, TerminalLinks.atCell(emulator, 0, row / 40))
        }
    }

    @Test fun osc8LabelUsesItsDestinationRatherThanItsDisplayedText() {
        val emulator = emulator(40, 6)
        append(emulator, "\u001b]8;;$oauth\u001b\\Click here to authenticate\u001b]8;;\u001b\\")
        assertEquals(oauth, TerminalLinks.atCell(emulator, 6, 0))
        assertNull(TerminalLinks.atCell(emulator, 30, 0))
    }

    @Test fun hyperlinkSurvivesReflowAndScrollbackWithoutChangingEncodedTarget() {
        val emulator = emulator(40, 4)
        append(emulator, "\u001b]8;id=login;$oauth\u0007" + "Authenticate".repeat(8) + "\u001b]8;;\u0007\r\ncode:")
        emulator.resize(20, 4)
        assertEquals(oauth, TerminalLinks.atCell(emulator, 0, -emulator.screen.activeTranscriptRows))
        assertEquals(oauth, TerminalLinks.atCell(emulator, 4, 0))
    }

    @Test fun erasedAndOverwrittenCellsDoNotKeepStaleHyperlinks() {
        val emulator = emulator(40, 6)
        append(emulator, "\u001b]8;;$oauth\u001b\\界e\u0301 login\u001b]8;;\u001b\\")
        assertEquals(oauth, TerminalLinks.atCell(emulator, 1, 0))
        append(emulator, "\u001b[1;2HX")
        assertNull(TerminalLinks.atCell(emulator, 0, 0))
        assertNull(TerminalLinks.atCell(emulator, 1, 0))
        assertEquals(oauth, TerminalLinks.atCell(emulator, 2, 0))
        append(emulator, "\u001b[2K")
        assertNull(TerminalLinks.atCell(emulator, 6, 0))
    }

    @Test fun insertAndDeleteShiftLinkMetadataWithTheText() {
        val emulator = emulator(40, 6)
        append(emulator, "\u001b]8;;$oauth\u0007login\u001b]8;;\u0007\u001b[1;1H\u001b[2@")
        assertNull(TerminalLinks.atCell(emulator, 0, 0))
        assertEquals(oauth, TerminalLinks.atCell(emulator, 2, 0))
        append(emulator, "\u001b[2P")
        assertEquals(oauth, TerminalLinks.atCell(emulator, 0, 0))
        assertNull(TerminalLinks.atCell(emulator, 5, 0))
    }

    @Test fun linkEvictionAndResetCannotRedirectOldCellsToNewTargets() {
        val emulator = emulator(40, 6)
        append(emulator, "\u001b]8;;$oauth\u0007old\u001b]8;;\u0007\r\n")
        repeat(140) { append(emulator, "\u001b]8;;https://example.test/$it\u0007\u001b]8;;\u0007") }
        assertNull(TerminalLinks.atCell(emulator, 0, 0))
        append(emulator, "\u001b]8;;https://example.test/new\u0007new\u001b]8;;\u0007")
        assertEquals("https://example.test/new", TerminalLinks.atCell(emulator, 0, 1))
        emulator.reset()
        append(emulator, "\u001b[3;1H\u001b]8;;https://example.test/after-reset\u0007new\u001b]8;;\u0007")
        assertNull(TerminalLinks.atCell(emulator, 0, 1))
        assertEquals("https://example.test/after-reset", TerminalLinks.atCell(emulator, 0, 2))
    }

    @Test fun linkCellsRespectTheExistingBufferMemoryBudget() {
        val emulator = emulator(40, 6)
        val before = emulator.screen.retainedBytes
        append(emulator, "\u001b]8;;$oauth\u0007login\u001b]8;;\u0007")
        assertTrue(emulator.screen.retainedBytes > before)
        append(emulator, "\u001b[2J")
        assertTrue(emulator.screen.retainedBytes <= before + 6 * (16 + 4 * 40))
    }

    @Test fun unsafeHyperlinksAreRejectedAndMouseOwnedTuiStillReceivesTaps() {
        val emulator = emulator(40, 6)
        append(emulator, "\u001b]8;;javascript:alert(1)\u0007login\u001b]8;;\u0007\r\n")
        assertNull(TerminalLinks.atCell(emulator, 0, 0))
        append(emulator, "\u001b]8;;$oauth\u0007login\u001b]8;;\u0007\u001b[?1000h")
        assertNull(TerminalLinks.atCell(emulator, 0, 1))
        assertEquals(oauth, TerminalLinks.atCell(emulator, 0, 1, explicitSelection = true))
    }

    @Test fun hardWrappedLinkDoesNotAbsorbAdjacentLinksOrUnstyledOutput() {
        val first = "https://example.test/first"
        val next = "https://example.test/other"
        val emulator = emulator(first.length, 6)
        append(emulator, "\u001b[4;34m$first\r\n$next\u001b[0m\r\nNot-part-of-link")
        assertEquals(first, TerminalLinks.atCell(emulator, 0, 0))
        assertEquals(next, TerminalLinks.atCell(emulator, 0, 1))
        assertNull(TerminalLinks.atCell(emulator, 0, 2))
    }

    @Test fun mapsUnicodeWideAndCombiningPrefixesToScreenCells() {
        val emulator = emulator(100, 4)
        // 界 and the emoji use two columns each. The combining accent uses none.
        append(emulator, "界 😀 e\u0301 " + "https://example.test/a")
        assertEquals("https://example.test/a", TerminalLinks.atCell(emulator, 8, 0))
        assertEquals("https://example.test/a", TerminalLinks.atCell(emulator, 29, 0))
        assertNull(TerminalLinks.atCell(emulator, 7, 0))
        assertNull(TerminalLinks.atCell(emulator, 30, 0))
    }

    @Test fun fullWidthHardNewlineDoesNotJoinIndependentOutputRows() {
        val url = "http://localhost:3000/path"
        val emulator = emulator(url.length, 4)
        append(emulator, url + "\r\n" + "next-output-is-not-part-of-url")
        assertEquals(url, TerminalLinks.atCell(emulator, 5, 0))
        assertNull(TerminalLinks.atCell(emulator, 5, 1))
    }

    @Test fun rejectsOversizedSoftWrapGroupsWithoutReturningPartialUrl() {
        val rowBound = emulator(20, 300)
        append(rowBound, "https://example.test/" + "a".repeat(20 * 257))
        assertNull(TerminalLinks.atCell(rowBound, 5, 0))
        val charBound = emulator(200, 120)
        append(charBound, "https://example.test/" + "a".repeat(17_000))
        assertNull(TerminalLinks.atCell(charBound, 5, 0))
    }

    @Test fun alternateScreenStillSupportsLinksUnlessProgramOwnsMouse() {
        val emulator = emulator(80, 4)
        append(emulator, "\u001b[?1049hhttps://example.test/login")
        assertEquals("https://example.test/login", TerminalLinks.atCell(emulator, 10, 0))
        append(emulator, "\u001b[?1000h")
        assertNull(TerminalLinks.atCell(emulator, 10, 0))
        append(emulator, "\u001b[?1000l")
        assertEquals("https://example.test/login", TerminalLinks.atCell(emulator, 10, 0))
    }

    @Test fun malformedCoordinatesAndBlankCellsNeverOpenLinks() {
        val emulator = emulator(40, 4)
        append(emulator, "https://example.test/")
        for ((column, row) in listOf(-1 to 0, 40 to 0, 0 to -1, 0 to 4, 35 to 0, 5 to 3)) {
            assertNull("$column,$row", TerminalLinks.atCell(emulator, column, row))
        }
    }

    private fun append(emulator: TerminalEmulator, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        do {
            offset += emulator.appendBounded(bytes, offset, bytes.size - offset, TerminalEmulator.INPUT_WORK_BUDGET)
        } while (offset < bytes.size || emulator.hasPendingInputWork())
    }

    private fun emulator(columns: Int, rows: Int): TerminalEmulator = TerminalEmulator(
        object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        },
        columns, rows, 1_000,
        object : TerminalSessionClient {
            override fun onTextChanged(session: TerminalSession) = Unit
            override fun onTitleChanged(session: TerminalSession) = Unit
            override fun onSessionFinished(session: TerminalSession) = Unit
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
            override fun onPasteTextFromClipboard(session: TerminalSession) = Unit
            override fun onBell(session: TerminalSession) = Unit
            override fun onColorsChanged(session: TerminalSession) = Unit
            override fun onTerminalCursorStateChange(state: Boolean) = Unit
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String, message: String) = Unit
            override fun logWarn(tag: String, message: String) = Unit
            override fun logInfo(tag: String, message: String) = Unit
            override fun logDebug(tag: String, message: String) = Unit
            override fun logVerbose(tag: String, message: String) = Unit
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
            override fun logStackTrace(tag: String, e: Exception) = Unit
        },
    )
}
