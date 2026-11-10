package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Finite production context/parser/filter coverage; native server and Android UI are not executed. */
class LspPrismaCompletionContextTest {
    private fun parsed(label: String, insert: String? = null, snippet: Boolean = false, filter: String? = null): LspCompletionItem =
        LspCompletionProtocol.parse(buildJsonArray { add(buildJsonObject {
            put("label", label)
            if (insert != null) put("insertText", insert)
            if (snippet) put("insertTextFormat", 2)
            if (filter != null) put("filterText", filter)
        }) }, strictSnippets = true).single()

    private fun replacement(text: String, cursor0: Int, item: LspCompletionItem): String {
        val context = LspPrismaCompletionContext.capture(text, 1, cursor0 + 1)!!
        val ranged = context.withInferredRange(item)!!
        return TextEditApplier.apply(text, listOf(TextRangeEdit(
            ranged.editStartLine!!, ranged.editStartColumn!!, ranged.editEndLine!!, ranged.editEndColumn!!, ranged.insertText)))
    }

    @Test fun singleAndDoubleAtSignsUseWholeNativeAttribute() {
        for (prefix in listOf("@", "@i")) assertEquals("id Int @id", replacement("id Int $prefix", 7 + prefix.length, parsed("@id")))
        for (prefix in listOf("@i", "@@", "@@i")) {
            val item = parsed("@@index", "@@index([${'$'}0])", true)
            val context = LspPrismaCompletionContext.forLine("  $prefix", 2 + prefix.length)!!
            assertEquals(prefix, context.prefix)
            assertEquals(item, ProfessionalCompletion.filterLsp(listOf(item), context.prefix).single())
            assertEquals("  @@index([${'$'}0])", replacement("  $prefix", 2 + prefix.length, item))
        }
        assertNull(LspPrismaCompletionContext.forLine("@@i", 3)!!.withInferredRange(parsed("@id")))
    }

    @Test fun datasourceAttributeRetainsExactlyOneAtSign() {
        assertEquals("name String @db${'$'}0", replacement("name String @d", 14, parsed("@db", "@db${'$'}0", true)))
    }

    @Test fun dottedLeafPreservesQualifierAndReplacesRemainingSuffix() {
        val item = parsed("VarChar()", "VarChar(${'$'}0)", true)
        for (cursor in listOf(4, 5, 7, 11)) {
            val context = LspPrismaCompletionContext.capture("@db.VarChar", 1, cursor + 1)!!
            assertEquals("@db.VarChar(${'$'}0)", replacement("@db.VarChar", cursor, item))
            assertEquals("@db.VarChar".substring(4, cursor), context.prefix)
            assertEquals(5, context.replacementRange!!.startColumn)
            assertEquals(12, context.replacementRange!!.endColumn)
        }
    }

    @Test fun compositeLeafReplacesFullToken() {
        assertEquals("@@index([address.city", replacement("@@index([address.cityName", 19, parsed("city")))
    }

    @Test fun wholeAttributeFullTokenAllowsSingleToNativeDoubleSign() {
        assertEquals("@@index([${'$'}0])", replacement("@index", 2, parsed("@@index", "@@index([${'$'}0])", true)))
    }

    @Test fun actualTriggerIsIndependentOfPrefix() {
        for ((text, trigger) in listOf("@db." to ".", "@@" to "@", "provider = \"" to "\"")) {
            assertEquals(trigger, LspPrismaCompletionContext.forLine(text, text.length)!!.triggerCharacter)
        }
        assertNull(LspPrismaCompletionContext.forLine("@db.Var", 7)!!.triggerCharacter)
    }

    @Test fun emptyPrefixAndQuotedProviderHaveBoundedInsertion() {
        assertEquals("model", replacement("", 0, parsed("model")))
        assertEquals("provider = \"postgresql", replacement("provider = \"", 12, parsed("postgresql")))
    }

    @Test fun namedArgumentAndBlockSnippetRetainNativeText() {
        assertEquals("fields: [${'$'}0]", replacement("fields", 2, parsed("fields", "fields: [${'$'}0]", true)))
        assertEquals("model ${'$'}{1:Name} {${'$'}0}", replacement("model", 2, parsed("model", "model ${'$'}{1:Name} {${'$'}0}", true)))
    }

    @Test fun nativeFilterTextIsNotRewritten() {
        val item = parsed("Display type", "VarChar(${'$'}0)", true, "VarChar")
        val context = LspPrismaCompletionContext.forLine("@db.Va", 6)!!
        assertEquals(item, ProfessionalCompletion.filterLsp(listOf(item), context.prefix).single())
        assertEquals("VarChar", context.withInferredRange(item)!!.filterText)
        assertTrue(ProfessionalCompletion.filterLsp(listOf(item.copy(filterText = "other")), context.prefix).isEmpty())
    }

    @Test fun ambiguousMissingRangeShapesFailClosed() {
        for ((text, insertion) in listOf("@@@i" to "@@index", "@db.Va" to "@db.VarChar", "address.ci" to "address.city", "éfoo" to "foo", "fooé" to "foo")) {
            assertNull(text, LspPrismaCompletionContext.forLine(text, if (text == "fooé") 2 else text.length)!!.withInferredRange(parsed(insertion)))
        }
        assertNull(LspPrismaCompletionContext.forLine("@@i", 1)!!.withInferredRange(parsed("@@index")))
    }

    @Test fun nativeExplicitRangeAlwaysWinsAndMalformedRangeDoesNotFallback() {
        val item = parsed("@@index").copy(editStartLine = 1, editStartColumn = 1, editEndLine = 1, editEndColumn = 5)
        assertSame(item, LspPrismaCompletionContext.forLine("@@@i", 4)!!.withInferredRange(item))
        assertTrue(LspCompletionProtocol.parse(Json.parseToJsonElement("""[{"label":"city","textEdit":{"range":{"start":{"line":0,"character":10},"end":{"line":0,"character":20}},"newText":"city"}}]"""), 1, 3, "ci", true).isEmpty())
        assertNull(LspPrismaCompletionContext.forLine("ci", 2)!!.withInferredRange(item.copy(editEndLine = null)))
    }

    @Test fun conflictingAdditionalEditRejectsInferredPrimary() {
        val item = parsed("city").copy(additionalTextEdits = listOf(TextRangeEdit(1,1,1,1,"prefix")))
        assertNull(LspPrismaCompletionContext.forLine("", 0)!!.withInferredRange(item))
    }

    @Test fun utf16CrLfAndEmptyLineBounds() {
        assertNull(LspPrismaCompletionContext.capture("😀x", 1, 2))
        assertNull(LspPrismaCompletionContext.capture("a\r\nb", 1, 3))
        assertNull(LspPrismaCompletionContext.capture("x", 1, 0))
        assertNull(LspPrismaCompletionContext.capture("x", 2, 1))
        val context = LspPrismaCompletionContext.capture("// 😀\r\n@db.VarChar", 2, 8)!!
        assertEquals(LspCompletionCaret(2,8), context.requestCaret)
        assertEquals(TextRangeEdit(2,5,2,12,""), context.replacementRange)
        assertNotNull(LspPrismaCompletionContext.capture("x\n", 2, 1))
    }

    @Test fun strictSnippetSanitizationNeverDowngradesWhileDefaultIsUnchanged() {
        val raw = buildJsonArray { add(buildJsonObject { put("label", "bad"); put("insertText", "$".repeat(97)); put("insertTextFormat", 2) }) }
        assertTrue(LspCompletionProtocol.parse(raw, strictSnippets = true).isEmpty())
        assertFalse(LspCompletionProtocol.parse(raw).single().isSnippet)
    }

    @Test fun oldConstructorAndOtherProfileContextRemainUnchanged() {
        val callback = { true }
        assertNull(LspCompletionApplicationGuard("x", callback).requiredCaret)
        assertNull(LspCompletionApplicationGuard("x") { true }.requiredCaret)
        assertEquals("db.Var", ProfessionalCompletion.prefixFromLine("prisma", "@db.Var", 7))
        assertEquals("jo", ProfessionalCompletion.prefixFromLine("clojure", "str/jo", 6))
        assertFalse(ProfessionalCompletion.shouldQueryLsp("prisma", "", null))
    }
}
