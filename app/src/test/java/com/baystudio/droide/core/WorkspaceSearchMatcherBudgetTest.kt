package com.baystudio.droide.core

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSearchMatcherBudgetTest {
    private fun budget(units: Long) = WorkspaceSearchBudget(
        WorkspaceSearchLimits(matchUnits = units), EmptyCoroutineContext,
        WorkspaceRequestDeadline(5_000_000_000L) { 0L },
    )

    @Test(timeout = 15_000) fun literalReservationPrecedesTheActualPrimitive(): Unit = runBlocking {
        for (units in listOf(5L, 6L)) {
            var calls = 0
            val work = budget(units)
            val hits = mutableListOf<TextSearchHit>()
            try {
                WorkspaceTextMatcher.appendLine("a.kt", 1, "aaaa", "zz", TextSearchOptions(), 10, hits, work,
                    indexOf = { text, query, start, ignoreCase ->
                        calls++
                        text.indexOf(query, start, ignoreCase)
                    })
                assertEquals(6L, units)
            } catch (limit: WorkspaceSearchLimitException) {
                assertEquals(5L, units)
                assertEquals(WorkspaceSearchStopReason.MATCH_LIMIT, limit.reason)
            }
            assertEquals(if (units == 5L) 0 else 1, calls)
            assertEquals(if (units == 5L) 0L else 6L, work.stats.matchUnits)
            assertTrue(hits.isEmpty())
        }
        val hits = mutableListOf<TextSearchHit>()
        WorkspaceTextMatcher.appendLine("a.kt", 1, "aaaa", "aa", TextSearchOptions(), 10, hits, budget(8))
        assertEquals(listOf(1, 3), hits.map { it.column })
    }

    @Test(timeout = 15_000) fun globTableReservationIsFiniteAndNotFalseNoMatch() {
        val matcher = WorkspaceGlobMatcher("*a*b")
        val tooSmall = budget(24)
        val failure = assertThrows(WorkspaceSearchLimitException::class.java) { matcher.matches("aaab", tooSmall) }
        assertEquals(WorkspaceSearchStopReason.MATCH_LIMIT, failure.reason)
        assertEquals(0L, tooSmall.stats.matchUnits)
        val exact = budget(25)
        assertTrue(matcher.matches("aaab", exact))
        assertEquals(25L, exact.stats.matchUnits)
    }

    @Test(timeout = 15_000) fun directHelperMatchesShortOriginalGrammarAndPlatformQuotingCases() {
        val face = "\uD83D\uDE00"
        val high = "\uD83D"
        val low = "\uDE00"
        val patterns = listOf(
            "*", "?", "**", "**/", "**/*.kt", "***", "**/**", "a?b", "[]{}", "\\",
            face, "x${face}y", "${face}*", "*${face}", "${face}?", "?${face}",
            "${face}**", "**${face}", "**/${face}", high, low, "x${high}y", "x${low}y",
        )
        val paths = listOf(
            "", "a", "a/b", "a/", "a.kt", "a/b.kt", "axb", "[]{}", "\\", "\n", "\r", "\u0085",
            "\u2028", "\u2029", "\u000b", "\u000c", face, "x${face}y", "a${face}", "${face}a",
            "a/${face}", high, low, "x${high}y", "x${low}y",
        )
        for (pattern in patterns) for (path in paths) {
            assertEquals("pattern=${pattern.toCharArray().map { it.code }} path=${path.toCharArray().map { it.code }}",
                original(pattern).matches(path), WorkspaceGlobMatcher(pattern).matches(path, budget(1_000_000)))
        }
        // Dot and not-slash wildcard families stay separate on the executing Android/JVM runtime.
        for (terminator in listOf("\u000b", "\u000c")) {
            assertTrue(WorkspaceGlobMatcher("*").matches(terminator, budget(100)))
            assertTrue(WorkspaceGlobMatcher("?").matches(terminator, budget(100)))
            assertEquals(Regex(".").matches(terminator), WorkspaceGlobMatcher("**").matches(terminator, budget(100)))
        }
    }

    /** Exact old translator, including a separate quote operation for each actual UTF-16 unit. */
    private fun original(glob: String): Regex = Regex("^" + buildString {
        var index = 0
        while (index < glob.length) when {
            glob.startsWith("**/", index) -> { append("(.*/)?"); index += 3 }
            glob.startsWith("**", index) -> { append(".*"); index += 2 }
            glob[index] == '*' -> { append("[^/]*"); index++ }
            glob[index] == '?' -> { append("[^/]"); index++ }
            else -> { append(Regex.escape(glob[index].toString())); index++ }
        }
    } + "$")
}
