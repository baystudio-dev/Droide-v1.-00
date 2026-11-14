package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core adapter tests. The host evidence lane separately binds the unchanged UI fallback edit. */
class ProfessionalMakeCompletionTest {
    private val automaticSymbols = "@<^+?*%|"
    private fun prefix(line: String, column: Int = line.length) =
        ProfessionalCompletion.prefixFromLine("makefile", line, column)
    private fun labels(vararg labels: String): List<LspCompletionItem> =
        LspCompletionProtocol.parse(JsonArray(labels.map { JsonObject(mapOf("label" to JsonPrimitive(it))) }))

    @Test fun targetPathsKeepTheirWholeReplacementPrefix() {
        for (target in listOf("build/ma", "../build/ma", "/build/ma", "build-dir/main.o", "构建/ma", "café/ma")) {
            assertEquals(target, prefix("all: $target"))
            assertEquals(listOf(target + "in"), ProfessionalCompletion.filterLsp(labels(target + "in"), target).map { it.label })
        }
    }

    @Test fun columnStopsBeforeTrailingTextAndPreservesUtf16Context() {
        val before = "all: 🚀 e\u0301 build/ma"
        assertEquals("build/ma", prefix(before + " later", before.length))
        assertEquals("build/ma", prefix("all: build/ma", Int.MAX_VALUE))
        assertEquals("", prefix("all: build/ma", -1))
        assertEquals("", prefix(""))
    }

    @Test fun partialDollarRetainsNativeLabelFallback() {
        assertEquals("$", prefix("\t@echo $"))
        val items = labels("$@", "$<")
        assertEquals(setOf("$@", "$<"), ProfessionalCompletion.filterLsp(items, "$").map { it.label }.toSet())
        assertTrue(items.all { it.insertText == it.label && it.editStartLine == null && !it.isSnippet })
    }

    @Test fun allEightCompleteAutomaticTokenPrefixesStayFinite() {
        for (symbol in automaticSymbols) {
            val token = "$" + symbol
            assertEquals(token, prefix("\t@echo $token"))
            assertEquals(listOf(token), ProfessionalCompletion.filterLsp(labels(token), prefix("\t@echo $token")).map { it.label })
        }
    }

    @Test fun oddDollarRunsReplaceOnlyTheirFinalAutomaticToken() {
        for (symbol in automaticSymbols) for (count in listOf(1, 3, 5, 7)) {
            val token = "$" + symbol
            assertEquals(token, prefix("\t@echo " + "$".repeat(count) + symbol))
        }
    }

    @Test fun evenDollarRunsFilterOutOneDollarAutomaticLabels() {
        for (symbol in automaticSymbols) for (count in listOf(2, 4, 6, 8)) {
            val suffix = "$".repeat(count) + symbol
            assertEquals(suffix, prefix("\t@echo $suffix"))
            assertTrue(ProfessionalCompletion.filterLsp(labels("$" + symbol), prefix("\t@echo $suffix")).isEmpty())
        }
    }

    @Test fun completeTokenHandlingDoesNotMakePunctuationAnIdentifier() {
        for (symbol in automaticSymbols) {
            assertEquals("", prefix("name$symbol"))
            assertEquals("name", prefix("$symbol" + "name"))
        }
        assertEquals("build/ma", ProfessionalCompletion.prefixFromLine("MAKEFILE", "all: build/ma", 13))
    }

    @Test fun variableReferencesRetainTheirDelimitersOutsideThePrefix() {
        for (opener in listOf("$(", "${'$'}{")) {
            val line = "\t@echo " + opener + "CC"
            assertEquals("CC", prefix(line))
            assertEquals(listOf("CC", "CCACHE"), ProfessionalCompletion.filterLsp(labels("CC", "CCACHE"), prefix(line)).map { it.label })
        }
    }

    @Test fun noIncomingNativeItemsMeansNoInventedNativeItems() {
        for (line in listOf("\t@echo $%", "\t@echo $(@D", "\t@echo ${'$'}{@D")) {
            assertTrue(ProfessionalCompletion.filterLsp(emptyList(), prefix(line)).isEmpty())
        }
    }

    @Test fun protocolPreservesLabelOnlyAndExplicitTextEditPrecedence() {
        val labelOnly = labels("build/main").single()
        assertEquals("build/main", labelOnly.insertText)
        assertEquals(null, labelOnly.editStartColumn)
        val explicit = kotlinx.serialization.json.Json.parseToJsonElement(
            """[{"label":"build/main","insertText":"ignored","textEdit":{"range":{"start":{"line":0,"character":5},"end":{"line":0,"character":13}},"newText":"preferred"}}]"""
        )
        val item = LspCompletionProtocol.parse(explicit, 1, 14, "all: build/ma!").single()
        assertEquals("preferred", item.insertText)
        assertEquals(6, item.editStartColumn)
        assertEquals(14, item.editEndColumn)
    }

    @Test fun filteringKeepsOriginalItemEditAndSnippetMetadata() {
        val item = LspCompletionItem("build/main", "${'$'}{1:name}", isSnippet = true,
            additionalTextEdits = listOf(TextRangeEdit(1, 1, 1, 1, "# header\n")))
        assertTrue(ProfessionalCompletion.filterLsp(listOf(item), "build/ma").single() === item)
        assertTrue(ProfessionalCompletion.fromLsp(listOf(item)).single().isSnippet)
    }

    @Test fun otherLanguagePrefixAndFilterContractsRemainBounded() {
        val cases = listOf(
            Triple("kotlin", "build/ma", "ma"), Triple("python", "build/ma", "ma"),
            Triple("css", "font-s", "font-s"), Triple("toml", "requires-py", "requires-py"),
            Triple("latex", "\\section", "section"), Triple("objectivec", "@interface", "interface"),
            Triple("scss", "${'$'}accent", "${'$'}accent"), Triple("less", "@accent", "@accent"),
            Triple("terraform", "local.some-value", "local.some-value"), Triple("xml", "ns:elem", "ns:elem"),
            Triple("third-party", "build/ma", "ma"),
        )
        for ((language, line, expected) in cases) {
            assertEquals(expected, ProfessionalCompletion.prefixFromLine(language, line, line.length))
            assertTrue(ProfessionalCompletion.filterLsp(labels(expected + "Suffix"), expected).isNotEmpty())
        }
        assertEquals("", ProfessionalCompletion.prefixFromLine("kotlin", "$@", 2))
        assertFalse(ProfessionalCompletion.shouldQueryLsp("makefile", "", " "))
        assertTrue(ProfessionalCompletion.shouldQueryLsp("makefile", "$@", "@"))
    }
}
