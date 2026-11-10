package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AgentWorkspaceRuleTriggerTest {
    private fun discovered(header: String): Boolean {
        val root = Files.createTempDirectory("rule-trigger").toFile()
        try {
            val file = root.resolve(".agents/rules/example.md")
            file.parentFile.mkdirs()
            file.writeText("---\n$header\n---\nRule body")
            return AgentProjectInstructions.discoverAlwaysOnRules(root).map { it.name } == listOf("example.md")
        } finally { root.deleteRecursively() }
    }

    @Test fun plainActivationIsPreserved() {
        for (value in listOf("always_on", "always-on", "always", "ALWAYS_ON"))
            assertTrue(value, discovered("trigger: $value"))
    }
    @Test fun doubleQuotedActivationIsRecognized() {
        assertTrue(discovered("trigger: \"always_on\""))
    }
    @Test fun singleQuotedActivationIsRecognized() {
        assertTrue(discovered("trigger: 'always-on'"))
    }
    @Test fun quotedActivationWithCommentIsRecognized() {
        assertTrue(discovered("trigger: \"always\" # project guidance"))
    }
    @Test fun malformedQuotesDoNotActivate() {
        for (value in listOf("\"always_on", "'always_on", "\"always_on'", "'always_on\"", "\"\"always_on\"\""))
            assertFalse(value, discovered("trigger: $value"))
    }
    @Test fun otherActivationRemainsOnDemand() {
        for (value in listOf("manual", "model", "\"manual\"", "'glob'", "always_on_extra", "\"always_on extra\""))
            assertFalse(value, discovered("trigger: $value"))
    }
}
