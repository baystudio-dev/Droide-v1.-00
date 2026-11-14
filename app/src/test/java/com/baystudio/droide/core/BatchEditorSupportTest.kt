package com.baystudio.droide.core

import java.nio.file.Files
import java.time.Duration
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.grammar.IToken
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult
import org.eclipse.tm4e.core.registry.Registry
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class BatchEditorSupportTest {
    @Test fun routesBothExtensionsWithoutPretendingBashCanRunThem() {
        for (path in listOf("work/run.bat", "build.CMD")) {
            assertEquals("batch", LanguageRegistry.forFile(path)?.id)
            assertEquals("bat", LanguageServerRegistry.protocolLanguageId(path))
            assertNull(LanguageRegistry.runPlanFor(path))
            assertTrue(LanguageServerRegistry.forPath(path).isEmpty())
            assertTrue(LanguageServerRegistry.unavailableReason(path)!!.contains("cmd.exe"))
        }
        assertTrue(LanguageRuntimePresentation.description("batch", "Batch").contains("sh/Bash cannot"))
    }

    @Test fun upstreamGrammarHandlesCommandsCommentsVariablesAndLabels() {
        val source = "@REM halo\r\n:: second comment\r\n@ECHO %PATH%\r\nSET name=hello\r\n:finish\r\n"
        val result = DroideHighlightResult(source.count { it == '\n' } + 1, renderActualGrammarTokens(source))
        assertTrue(result.spans.any { it.line == 0 && it.kind == DroideHighlightKind.COMMENT })
        assertTrue(result.spans.any { it.line == 1 && it.kind == DroideHighlightKind.COMMENT })
        assertTrue(result.spans.any { it.line == 2 && it.kind == DroideHighlightKind.VARIABLE })
        assertTrue(result.spans.any { it.line == 2 && it.start <= 1 && it.end >= 5 })
        assertTrue(result.spans.any { it.line == 4 })
        assertFalse(result.spans.any { it.semantic })
    }

    @Test fun echoTextIsNotMistakenForCCommentsAndBmpOffsetsRemainUtf16() {
        val source = "echo https://example.test/é/文 %PATH%\nREM 文 Unicode comment"
        val spans = renderActualGrammarTokens(source)
        assertFalse(spans.any { it.line == 0 && it.kind == DroideHighlightKind.COMMENT })
        val start = source.indexOf("%PATH%")
        // TextMate can split delimiters from the variable body; assert the rendered
        // UTF-16 coverage rather than prescribing the grammar's token boundaries.
        for (column in start until start + 6) {
            assertTrue(spans.toString(), spans.any {
                it.line == 0 && column in it.start until it.end && it.kind == DroideHighlightKind.VARIABLE
            })
        }
        for (span in spans) {
            val line = source.split('\n')[span.line]
            assertTrue(span.start >= 0 && span.end <= line.length && span.end > span.start)
        }
    }

    @Test fun supplementaryCharactersNeverProduceShiftedTextMateSpans() {
        for (source in listOf("echo 😀 %PATH%", "REM 😀\necho %PATH%", "echo \uD800 %PATH%")) {
            assertTrue(DroideSyntaxSemanticEngine.highlight(source, "batch").spans.isEmpty())
        }
        assertTrue(renderActualGrammarTokens("REM ordinary again").any { it.kind == DroideHighlightKind.COMMENT })
        assertValidRuntimeFallback("REM ordinary again", DroideHighlightKind.COMMENT)
    }

    @Test fun malformedAndLargeInputsAreBoundedAndDocumentStateDoesNotLeak() {
        renderActualGrammarTokens("if (\n\"unterminated")
        BuiltInEditorGrammars.highlight("if (\n\"unterminated", "batch", 200)
        assertTrue(renderActualGrammarTokens("REM fresh").any { it.kind == DroideHighlightKind.COMMENT })
        assertValidRuntimeFallback("REM fresh", DroideHighlightKind.COMMENT)
        assertTrue(BuiltInEditorGrammars.highlight("ECHO x\n".repeat(100), "batch", 2)!!.size <= 2)
        assertTrue(BuiltInEditorGrammars.highlight("ECHO", "batch", 0)!!.isEmpty())
        assertTrue(DroideSyntaxSemanticEngine.highlight("x".repeat(DroideSyntaxSemanticEngine.MAX_HIGHLIGHT_CHARS + 1), "batch").skippedForSize)
        assertNull(BuiltInEditorGrammars.highlight("REM x", "unknown-language", 20))
    }

    @Test fun caseInsensitiveLocalCompletionAndBatchPlaceholdersNeedNoServer() {
        val items = ProfessionalCompletion.localCandidates("batch", "FOR", 3, "FOR")
        val loop = items.first { it.isSnippet && it.label == "for" }
        assertTrue(loop.insertText.contains("%%${'$'}{1:f}"))
        assertTrue(loop.insertText.contains("${'$'}0"))
        assertTrue(ProfessionalSnippets.builtIns("batch", "set").single().insertText.startsWith("set \""))
        assertTrue(ProfessionalSnippets.builtIns("batch", "rem").single().insertText.startsWith("@REM "))
    }

    @Test fun contributedGrammarRetainsPrecedenceAndUnregisterRestoresBuiltIn() {
        val root = Files.createTempDirectory("droide-batch-grammar").toFile()
        try {
            root.resolve("custom.json").writeText("""{"scopeName":"source.test-batch","patterns":[{"match":"MAGIC","name":"constant.numeric.test"}]}""")
            DeclarativeTextMateRegistry.register("test-batch", root, emptyList(), listOf(
                DroideGrammarContribution(language = "batch", scopeName = "source.test-batch", path = "custom.json")
            ))
            assertTrue(DeclarativeTextMateRegistry.hasGrammar("batch"))
            val contributed = Registry().addGrammar(IGrammarSource.fromFile(root.resolve("custom.json")))
            assertTrue(renderActualGrammarTokens("MAGIC", contributed).any { it.kind == DroideHighlightKind.NUMBER })
            assertValidRuntimeFallback("MAGIC", DroideHighlightKind.NUMBER)
            DeclarativeTextMateRegistry.unregister("test-batch")
            assertFalse(DeclarativeTextMateRegistry.hasGrammar("batch"))
            assertTrue(renderActualGrammarTokens("REM restored").any { it.kind == DroideHighlightKind.COMMENT })
            assertValidRuntimeFallback("REM restored", DroideHighlightKind.COMMENT)
        } finally {
            DeclarativeTextMateRegistry.unregister("test-batch")
            root.deleteRecursively()
        }
    }

    @Test fun stoppedTokenizationCannotPublishPartialDocumentStyles() {
        val grammar = Registry().addGrammar(
            IGrammarSource.fromResource(
                BuiltInEditorGrammars::class.java, "/droide/grammars/batchfile.tmLanguage.json",
            ),
        )
        val seed = grammar.tokenizeLine("")
        var calls = 0
        val stopped = object : IGrammar by grammar {
            override fun tokenizeLine(
                text: String,
                previous: IStateStack?,
                limit: Duration?,
            ): ITokenizeLineResult<Array<IToken>> {
                calls++
                assertEquals(Duration.ofMillis(8), limit)
                return object : ITokenizeLineResult<Array<IToken>> by seed {
                    override fun isStoppedEarly(): Boolean = true
                }
            }
        }
        assertNull(DeclarativeTextMateRegistry.highlightGrammar(stopped, "REM first\necho %PATH%", 100))
        assertEquals("An incomplete rule stack must not be used for the next line", 1, calls)
    }
    private val semanticGrammar: IGrammar by lazy {
        Registry().addGrammar(IGrammarSource.fromResource(
            BuiltInEditorGrammars::class.java, "/droide/grammars/batchfile.tmLanguage.json",
        ))
    }

    private fun assertValidRuntimeFallback(text: String, expected: DroideHighlightKind) {
        val result = DroideSyntaxSemanticEngine.highlight(text, "batch")
        assertFalse(result.skippedForSize)
        assertEquals(text.count { it == '\n' } + 1, result.lineCount)
        assertTrue(result.spans.toString(), result.spans.isEmpty() || result.spans.any { it.kind == expected })
    }

    private fun renderActualGrammarTokens(text: String, grammar: IGrammar = semanticGrammar): List<DroideHighlightSpan> {
        var state: IStateStack? = null
        val inputs = text.split('\n')
        val previousStates = mutableListOf<IStateStack?>()
        val tokenized = inputs.map { line ->
            previousStates += state
            grammar.tokenizeLine(line, state, null).also { result ->
                assertFalse("Semantic fixture requires a complete grammar result", result.isStoppedEarly)
                state = result.ruleStack
            }
        }
        var nextLine = 0
        val replay = object : IGrammar by grammar {
            override fun tokenizeLine(text: String, previous: IStateStack?, limit: Duration?): ITokenizeLineResult<Array<IToken>> {
                assertEquals(Duration.ofMillis(8), limit)
                assertEquals(inputs[nextLine], text)
                assertSame(previousStates[nextLine], previous)
                return tokenized[nextLine++]
            }
        }
        // Regex execution is verified above; this pass verifies production span conversion separately from cold-start timing.
        val spans = requireNotNull(DeclarativeTextMateRegistry.highlightGrammar(replay, text, 1_000))
        assertEquals(inputs.size, nextLine)
        return spans
    }

}
