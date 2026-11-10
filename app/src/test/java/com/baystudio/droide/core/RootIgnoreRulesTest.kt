package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RootIgnoreRulesTest {
    @Test fun growingIgnoreInputCannotReadBeyondBoundAndIsClosed() {
        var read = 0L
        var closed = false
        val input = object : java.io.InputStream() {
            override fun read(): Int { read++; return '#'.code }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill('#'.code.toByte(), offset, offset + length)
                read += length
                return length
            }
            override fun close() { closed = true }
        }
        assertThrows(RootIgnoreException::class.java) { runBlocking { RootIgnoreRules.read(input) } }
        assertEquals(RootIgnoreRules.MAX_BYTES + 1, read)
        assertTrue(closed)
    }

    @Test fun excessiveLineRulesAndInvalidUtf8FailVisibly() {
        assertThrows(RootIgnoreException::class.java) { rules("a".repeat(RootIgnoreRules.MAX_LINE_CHARS + 1)) }
        assertThrows(RootIgnoreException::class.java) {
            RootIgnoreRules.parse(List(RootIgnoreRules.MAX_RULES + 1) { "hidden-$it" })
        }
        assertThrows(RootIgnoreException::class.java) {
            runBlocking { RootIgnoreRules.read(byteArrayOf(0xc3.toByte(), 0x28).inputStream()) }
        }
    }

    @Test fun cancellationDuringIgnoreReadClosesInput() {
        val job = kotlinx.coroutines.Job()
        var closed = false
        val input = object : java.io.ByteArrayInputStream("hidden\n".repeat(5000).toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(bytes, offset, length)
                job.cancel()
                return count
            }
            override fun close() { closed = true; super.close() }
        }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { kotlinx.coroutines.withContext(job) { RootIgnoreRules.read(input) } }
        }
        assertTrue(closed)
    }

    @Test fun pathologicalPatternsHaveAFiniteQueryWorkBudget() {
        val ignore = rules("*a".repeat(1024) + "z")
        assertThrows(RootIgnoreException::class.java) {
            repeat(100) { ignore.isIgnored("a".repeat(2048)) }
        }
    }

    @Test fun ignoredDirectoryPruningRespectsExplicitParentReopening() {
        assertFalse(rules("build/", "!build/keep.kt").canDescend("build"))
        assertTrue(rules("build/", "!build/", "build/*", "!build/keep.kt").canDescend("build"))
        assertFalse(rules("build/", "!build/", "build/*", "!build/keep.kt").canDescend("build/nested"))
    }

    private fun rules(vararg lines: String) = RootIgnoreRules.parse(lines.toList())

    @Test fun `directory rule preserves same-named files and excludes directory descendants`() {
        val ignore = rules("build/")
        assertFalse(ignore.isIgnored("build"))
        assertFalse(ignore.isIgnored("module/build"))
        assertTrue(ignore.isIgnored("build", isDirectory = true))
        assertTrue(ignore.isIgnored("module/build", isDirectory = true))
        assertTrue(ignore.isIgnored("build/output.kt"))
        assertTrue(ignore.isIgnored("module/build/deeper/output.kt"))
        assertFalse(ignore.isIgnored("builder/output.kt"))
    }

    @Test fun `non-directory rule matches both file and directory`() {
        val ignore = rules("cache")
        assertTrue(ignore.isIgnored("cache"))
        assertTrue(ignore.isIgnored("nested/cache", isDirectory = true))
        assertTrue(ignore.isIgnored("nested/cache/content.kt"))
    }

    @Test fun `child negation cannot reopen excluded ancestor`() {
        val ignore = rules("build/", "!build/keep.kt")
        assertTrue(ignore.isIgnored("build/keep.kt"))
        assertTrue(ignore.isIgnored("build/drop.kt"))
    }

    @Test fun `explicitly reopening parent permits its child rules`() {
        val ignore = rules("build/", "!build/", "build/*", "!build/keep.kt")
        assertFalse(ignore.isIgnored("build", isDirectory = true))
        assertFalse(ignore.isIgnored("build/keep.kt"))
        assertTrue(ignore.isIgnored("build/drop.kt"))
        assertTrue(ignore.isIgnored("build/nested/keep.kt"))
    }

    @Test fun `directory negation does not erase descendants own exclusions`() {
        val ignore = rules("*", "!src/")
        assertFalse(ignore.isIgnored("src", isDirectory = true))
        assertTrue(ignore.isIgnored("src/main.kt"))
    }

    @Test fun `last matching rule wins for each individual entry`() {
        val ignore = rules("*.log", "!important.log", "important.log", "!final.log")
        assertTrue(ignore.isIgnored("important.log"))
        assertFalse(ignore.isIgnored("final.log"))
        assertTrue(ignore.isIgnored("ordinary.log"))
        assertFalse(ignore.isIgnored("ordinary.kt"))
    }

    @Test fun `leading slash and internal slash anchor at root`() {
        val ignore = rules("/cache/", "docs/generated.txt")
        assertTrue(ignore.isIgnored("cache/a.kt"))
        assertFalse(ignore.isIgnored("nested/cache/a.kt"))
        assertTrue(ignore.isIgnored("docs/generated.txt"))
        assertFalse(ignore.isIgnored("nested/docs/generated.txt"))
    }

    @Test fun `comments and escaped leading hash and bang are distinct`() {
        val ignore = rules("#comment", "\\#literal", "\\!literal", " #leading-space")
        assertFalse(ignore.isIgnored("#comment"))
        assertTrue(ignore.isIgnored("#literal"))
        assertTrue(ignore.isIgnored("!literal"))
        assertTrue(ignore.isIgnored(" #leading-space"))
        assertFalse(ignore.isIgnored("#leading-space"))
    }

    @Test fun `negated hash name is not a comment`() {
        val ignore = rules("*", "!#keep")
        assertFalse(ignore.isIgnored("#keep"))
        assertTrue(ignore.isIgnored("#drop"))
    }

    @Test fun `backslash escapes wildcards and ordinary characters`() {
        val ignore = rules("literal\\*.txt", "question\\?.txt", "\\alpha.txt", "\\[note\\].txt")
        assertTrue(ignore.isIgnored("literal*.txt"))
        assertFalse(ignore.isIgnored("literal-more.txt"))
        assertTrue(ignore.isIgnored("question?.txt"))
        assertFalse(ignore.isIgnored("question1.txt"))
        assertTrue(ignore.isIgnored("alpha.txt"))
        assertTrue(ignore.isIgnored("[note].txt"))
        assertFalse(ignore.isIgnored("n.txt"))
    }

    @Test fun `escaped backslash remains literal alongside wildcard`() {
        val ignore = rules("part\\\\*.txt")
        assertTrue(ignore.isIgnored("part\\one.txt"))
        assertFalse(ignore.isIgnored("partone.txt"))
        assertFalse(ignore.isIgnored("part/one.txt"))
    }

    @Test fun `dangling escape never matches`() {
        val ignore = rules("name\\", "path/name\\", "wild*\\")
        assertFalse(ignore.isIgnored("name"))
        assertFalse(ignore.isIgnored("name\\"))
        assertFalse(ignore.isIgnored("path/name"))
        assertFalse(ignore.isIgnored("wildcard"))
    }

    @Test fun `unescaped trailing ASCII spaces are ignored but leading spaces remain`() {
        val ignore = rules("plain   ", " leading ", "   ")
        assertTrue(ignore.isIgnored("plain"))
        assertFalse(ignore.isIgnored("plain "))
        assertTrue(ignore.isIgnored(" leading"))
        assertFalse(ignore.isIgnored("leading"))
        assertFalse(ignore.isIgnored(" "))
    }

    @Test fun `escaped trailing spaces and backslash parity are preserved`() {
        val ignore = rules("one\\ ", "two\\  ", "even\\\\ ", "odd\\\\\\ ")
        assertTrue(ignore.isIgnored("one "))
        assertFalse(ignore.isIgnored("one"))
        assertTrue(ignore.isIgnored("two "))
        assertFalse(ignore.isIgnored("two  "))
        assertTrue(ignore.isIgnored("even\\"))
        assertFalse(ignore.isIgnored("even\\ "))
        assertTrue(ignore.isIgnored("odd\\ "))
        assertFalse(ignore.isIgnored("odd\\"))
    }

    @Test fun `tabs and unicode whitespace remain literal including after slash`() {
        val ignore = rules("tab\t", "build/\t", "wide\u2003", "space/\\ ")
        assertTrue(ignore.isIgnored("tab\t"))
        assertFalse(ignore.isIgnored("tab"))
        assertTrue(ignore.isIgnored("build/\t"))
        assertFalse(ignore.isIgnored("build", isDirectory = true))
        assertFalse(ignore.isIgnored("build/other.kt"))
        assertTrue(ignore.isIgnored("wide\u2003"))
        assertFalse(ignore.isIgnored("wide"))
        assertTrue(ignore.isIgnored("space/ "))
        assertFalse(ignore.isIgnored("space", isDirectory = true))
    }

    @Test fun `component double star matches zero or several directories`() {
        val ignore = rules("a/**/b.txt", "**/cache/", "generated/**")
        assertTrue(ignore.isIgnored("a/b.txt"))
        assertTrue(ignore.isIgnored("a/one/two/b.txt"))
        assertFalse(ignore.isIgnored("nested/a/b.txt"))
        assertTrue(ignore.isIgnored("cache/a.kt"))
        assertTrue(ignore.isIgnored("one/two/cache/a.kt"))
        assertFalse(ignore.isIgnored("generated", isDirectory = true))
        assertTrue(ignore.isIgnored("generated/deep/output.kt"))
    }

    @Test fun `double star embedded in name does not span path separators`() {
        val ignore = rules("/a**b.txt")
        assertTrue(ignore.isIgnored("ab.txt"))
        assertTrue(ignore.isIgnored("alongb.txt"))
        assertFalse(ignore.isIgnored("a/sub/b.txt"))
        assertFalse(ignore.isIgnored("nested/ab.txt"))
    }

    @Test fun `double star accepts escaped directory separators`() {
        val afterStars = rules("a/**\\/b.txt")
        assertTrue(afterStars.isIgnored("a/one/two/b.txt"))
        assertFalse(afterStars.isIgnored("a/b.txt"))
        assertFalse(afterStars.isIgnored("nested/a/one/two/b.txt"))
        val leadingStars = rules("**\\/b.txt")
        assertTrue(leadingStars.isIgnored("one/two/b.txt"))
        assertFalse(leadingStars.isIgnored("b.txt"))
        val beforeStars = rules("a\\/**/b.txt")
        assertTrue(beforeStars.isIgnored("a/b.txt"))
        assertTrue(beforeStars.isIgnored("a/one/two/b.txt"))
    }

    @Test fun `bracket lists ranges and negation match one non-separator character`() {
        val ignore = rules("item[0-3].kt", "/other[!ab].kt", "/caret[^ab].kt")
        assertTrue(ignore.isIgnored("item0.kt"))
        assertTrue(ignore.isIgnored("item3.kt"))
        assertFalse(ignore.isIgnored("item4.kt"))
        assertFalse(ignore.isIgnored("item01.kt"))
        assertTrue(ignore.isIgnored("otherc.kt"))
        assertFalse(ignore.isIgnored("othera.kt"))
        assertFalse(ignore.isIgnored("other/.kt"))
        assertTrue(ignore.isIgnored("caretc.kt"))
        assertFalse(ignore.isIgnored("caretb.kt"))
    }

    @Test fun `bracket punctuation and escaped hyphen stay literal`() {
        val ignore = rules("dash[a\\-c].kt", "close[]a].kt", "open[[].kt", "amp[a&&b].kt")
        assertTrue(ignore.isIgnored("dash-.kt"))
        assertTrue(ignore.isIgnored("dasha.kt"))
        assertTrue(ignore.isIgnored("dashc.kt"))
        assertFalse(ignore.isIgnored("dashb.kt"))
        assertTrue(ignore.isIgnored("close].kt"))
        assertTrue(ignore.isIgnored("closea.kt"))
        assertTrue(ignore.isIgnored("open[.kt"))
        assertTrue(ignore.isIgnored("amp&.kt"))
        assertTrue(ignore.isIgnored("ampa.kt"))
        assertTrue(ignore.isIgnored("ampb.kt"))
        assertFalse(ignore.isIgnored("ampc.kt"))
    }

    @Test fun `ASCII POSIX classes are supported inside brackets`() {
        val ignore = rules("num[[:digit:]].kt", "alpha[[:alpha:]].kt", "blank[[:blank:]].kt")
        assertTrue(ignore.isIgnored("num7.kt"))
        assertFalse(ignore.isIgnored("numx.kt"))
        assertTrue(ignore.isIgnored("alphaZ.kt"))
        assertFalse(ignore.isIgnored("alpha7.kt"))
        assertTrue(ignore.isIgnored("blank .kt"))
        assertTrue(ignore.isIgnored("blank\t.kt"))
        assertFalse(ignore.isIgnored("blankx.kt"))
    }

    @Test fun `hyphen after POSIX class remains literal rather than starting a range`() {
        val ignore = rules("value[[:blank:]-a].kt")
        assertTrue(ignore.isIgnored("value .kt"))
        assertTrue(ignore.isIgnored("value\t.kt"))
        assertTrue(ignore.isIgnored("value-.kt"))
        assertTrue(ignore.isIgnored("valuea.kt"))
        assertFalse(ignore.isIgnored("value0.kt"))
        assertFalse(ignore.isIgnored("valueZ.kt"))
    }

    @Test fun `reversed bracket range preserves prior literal endpoint`() {
        val ignore = rules("reverse[z-a].kt")
        assertTrue(ignore.isIgnored("reversez.kt"))
        assertFalse(ignore.isIgnored("reversea.kt"))
        assertFalse(ignore.isIgnored("reversem.kt"))
        assertFalse(ignore.isIgnored("reverse-.kt"))
    }

    @Test fun `hyphen following completed range stays literal`() {
        val ignore = rules("range[a-c-e].kt")
        assertTrue(ignore.isIgnored("rangea.kt"))
        assertTrue(ignore.isIgnored("rangeb.kt"))
        assertTrue(ignore.isIgnored("rangec.kt"))
        assertTrue(ignore.isIgnored("range-.kt"))
        assertTrue(ignore.isIgnored("rangee.kt"))
        assertFalse(ignore.isIgnored("ranged.kt"))
    }

    @Test fun `malformed brackets never disable other valid rules`() {
        val ignore = rules("broken[", "[[:unknown:]]", "valid.kt", "another.kt")
        assertFalse(ignore.isIgnored("broken["))
        assertFalse(ignore.isIgnored("anything"))
        assertTrue(ignore.isIgnored("valid.kt"))
        assertTrue(ignore.isIgnored("another.kt"))
    }

    @Test fun `single question and star never span directories`() {
        val ignore = rules("/one?.txt", "/src/*.kt")
        assertTrue(ignore.isIgnored("one1.txt"))
        assertFalse(ignore.isIgnored("one12.txt"))
        assertTrue(ignore.isIgnored("src/main.kt"))
        assertFalse(ignore.isIgnored("src/deep/main.kt"))
    }

    @Test fun `first line UTF8 BOM is accepted without stripping later literal BOM`() {
        val ignore = rules("\uFEFFfirst.txt", "\uFEFFsecond.txt")
        assertTrue(ignore.isIgnored("first.txt"))
        assertFalse(ignore.isIgnored("second.txt"))
        assertTrue(ignore.isIgnored("\uFEFFsecond.txt"))
    }

    @Test fun `blank comment and slash-only rules do not ignore files`() {
        val ignore = rules("", " ", "#comment", "/", "!", "!/")
        assertFalse(ignore.isIgnored("visible.kt"))
        assertFalse(ignore.isIgnored("folder", isDirectory = true))
    }
}
