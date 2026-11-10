package com.baystudio.droide.ui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolInputPreviewTest {
    @Test fun completeTypedPathsAndEscapesAreDecoded() {
        val preview = AgentToolInputPreview.parse("""{"path":"src/\u4e2d\u6587.kt","command":"echo \"ready\"\nnext\tstep"}""")
        assertEquals("src/中文.kt", preview.path())
        assertEquals("echo \"ready\" next step", preview.text("command"))
    }

    @Test fun incompleteStreamShowsCommandPrefixWithoutInventingPath() {
        val command = AgentToolInputPreview.parse("""{"command":"echo building""")
        assertEquals("echo building", command.text("command"))
        assertNull(command.path())
        assertNull(AgentToolInputPreview.parse("""{"path":"src/Main.kt""").path())
        assertEquals("src/Main.kt", AgentToolInputPreview.parse("""{"path":"src/Main.kt"}""").path())
    }

    @Test fun codeContentAndNestedPathsDoNotBecomeFileIdentity() {
        val preview = AgentToolInputPreview.parse("""{"content":"import src.Main.kt; /tmp/other.txt","metadata":{"path":"wrong.kt"},"path":"correct.kt"}""")
        assertEquals("correct.kt", preview.path())
        assertNull(AgentToolInputPreview.parse("""{"command":"cat src/Main.kt"}""").path())
    }

    @Test fun longToolInputsHaveBoundedPreviews() {
        val arguments = buildJsonObject { put("command", "echo " + "x".repeat(1_000_000)) }.toString()
        val preview = AgentToolInputPreview.parse(arguments)
        assertTrue(preview.text("command")!!.startsWith("echo "))
        assertTrue(preview.text("command")!!.length <= 512)
        assertNull(AgentToolInputPreview.parse(buildJsonObject { put("path", "x".repeat(1_000)) }.toString()).path())
    }

    @Test fun malformedOrControlContainingPathsAreNotPresentedAsFiles() {
        for (input in listOf("", "{", "[]", "null", """{"path":false}""",
            """{"path":"bad\q"}""", """{"path":"bad\uZZZZ"}""", """{"path":"bad\nname.kt"}""")) {
            assertNull(input, AgentToolInputPreview.parse(input).path())
        }
    }

    @Test fun excessiveNestedArgumentsStopWithoutRecursiveParsing() {
        val input = "{\"metadata\":" + "[".repeat(10_000) + "0" + "]".repeat(10_000) + ",\"path\":\"hidden.kt\"}"
        assertNull(AgentToolInputPreview.parse(input).path())
    }

    @Test fun nonStringFieldsAreSkippedWithoutLosingLaterTypedPath() {
        assertEquals("src/Main.kt", AgentToolInputPreview.parse("""{"command":42,"metadata":[{"x":true},null],"path":"src/Main.kt"}""").path())
    }
}
