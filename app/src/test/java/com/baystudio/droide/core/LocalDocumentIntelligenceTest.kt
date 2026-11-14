package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class LocalDocumentIntelligenceTest {
    @Test fun propertiesFollowJavaEscapesContinuationAndCommentRules() {
        val text = "# comment\\\r\n  first\\ key : secret\r\nlong\\\r\n  Key=hidden\r\n\\u0061=private\r\n"
        val result = LocalDocumentIntelligence.analyze("app.properties", text)
        assertEquals(listOf("first key", "longKey", "a"), result.symbols.map { it.name })
        assertEquals(listOf(2, 3, 5), result.symbols.map { it.line })
        assertEquals(listOf(3, 1, 1), result.symbols.map { it.column })
        assertTrue(result.diagnostics.isEmpty())
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun malformedPropertiesEscapeIsReportedWithoutLeakingValues() {
        val result = LocalDocumentIntelligence.analyze("a.properties", "valid=ok\nbroken=secret\\u12ZZ")
        assertEquals(listOf("valid"), result.symbols.map { it.name })
        assertEquals(2, result.diagnostics.single().line)
        assertFalse(result.diagnostics.single().message.contains("secret"))
    }

    @Test fun iniSectionsAndKeysUseUtf16CoordinatesAndLimitedDialectChecks() {
        val result = LocalDocumentIntelligence.analyze("a.ini", "; note\n[Server]\n  emoji😀=value\n[   ]\n[missing")
        assertEquals(listOf("Server", "emoji😀"), result.symbols.map { it.name })
        assertEquals(listOf(0, 1), result.symbols.map { it.depth })
        assertEquals(3, result.symbols.last().column)
        assertEquals(listOf(4, 5), result.diagnostics.map { it.line })
        assertTrue(LocalDocumentIntelligence.analyze("unknown.conf", "[not an INI section").diagnostics.isEmpty())
    }

    @Test fun batchOutlineAndDefinitionsIgnoreCommentsAndUnsupportedExpressions() {
        val text = "@rem set hidden=no\n::hidden\n:start\n  @set \"NAME=value\"\nset /p INPUT=Prompt\nset   /a TOTAL=1+2\ngoto START\necho %NAME% !INPUT!"
        val result = LocalDocumentIntelligence.analyze("script.cmd", text)
        assertEquals(listOf("start", "NAME", "INPUT"), result.symbols.map { it.name })
        assertEquals(3, LocalDocumentIntelligence.definition("script.cmd", text, 7, 7).single().line)
        assertEquals(4, LocalDocumentIntelligence.definition("script.cmd", text, 8, 7).single().line)
        assertEquals(5, LocalDocumentIntelligence.definition("script.cmd", text, 8, 15).single().line)
        assertTrue(LocalDocumentIntelligence.definition("script.cmd", text, 1, 12).isEmpty())
    }

    @Test fun limitsAndUnsupportedDocumentsDoNotReturnMisleadingCoordinates() {
        assertNotNull(LocalDocumentIntelligence.analyze("a.ini", "x".repeat(LocalDocumentIntelligence.MAX_CHARS + 1)).limit)
        assertNotNull(LocalDocumentIntelligence.analyze("a.ini", "x=y\rz=w").limit)
        val result = LocalDocumentIntelligence.analyze("a.properties", "x=y\n".repeat(LocalDocumentIntelligence.MAX_SYMBOLS + 1))
        assertEquals(LocalDocumentIntelligence.MAX_SYMBOLS, result.symbols.size)
        assertNotNull(result.limit)
        assertTrue(LocalDocumentIntelligence.analyze("a.unknown", "key=value").symbols.isEmpty())
    }
}
