package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class AgentRequestScopeTest {
    private fun greeting(text: String, fresh: Boolean = true, human: Boolean = true,
                         attachments: Boolean = false, pending: Boolean = false) =
        AgentRequestScope.initialGreetingOnly(text, fresh, human, attachments, pending)

    @Test fun onlyWholeStandaloneInitialGreetingsUseTheConversationalFastPath() {
        for (text in listOf("hi", "hai", " HALO! ", "Hello, Droide!", "hai sayang", "selamat pagi", "Hi\nDroide")) {
            assertTrue(text, greeting(text))
        }
    }

    @Test fun greetingsWithTasksAndUnknownOrConceptualTextKeepNormalTaskHandling() {
        for (text in listOf("hi, fix the bug", "hai lanjutkan build", "hello\ncreate README.md", "hi.md",
            "lanjutkan", "continue", "yes", "oke", "What is a compiler?", "Apakah kodenya aman?",
            "Please reply hi", "hai; rm a.txt", "hi " + "a".repeat(200), "", "👋")) {
            assertFalse(text, greeting(text))
        }
    }

    @Test fun existingTaskMediaDelegationAndPendingInputsCannotLoseTheirToolSurface() {
        assertFalse(greeting("hi", fresh = false))
        assertFalse(greeting("hi", human = false))
        assertFalse(greeting("hi", attachments = true))
        assertFalse(greeting("hi", pending = true))
    }

    @Test fun cliGuidancePreservesTheExactUserMessageAsUserContent() {
        val user = "Hi, fix src/main.kt\nKeep this exact text: <user>& \"quoted\""
        val output = AgentRequestScope.forCliPrompt(user)
        assertTrue(output.startsWith("[Droide client conversation guidance]"))
        assertTrue(output.endsWith("[User message]\n$user\n[/User message]"))
        assertTrue(output.contains("For an explicit action request, complete the authorized work"))
        assertFalse(output.contains("role=system"))
    }

    @Test fun nativeGuidanceBoundsDiscoveryAndModesDoNotCreateWorkByThemselves() {
        assertTrue(AgentSystemPrompt.TEXT.contains(AgentRequestScope.INSTRUCTIONS))
        assertFalse(AgentSystemPrompt.TEXT.contains("Always verify via tools"))
        assertFalse(AgentSystemPrompt.TEXT.contains("Always check files before reading"))
        assertTrue(AgentRequestScope.modeInstruction(AgentMode.BUILD).contains("does not itself request changes"))
        assertTrue(AgentRequestScope.modeInstruction(AgentMode.PLAN).contains("When the user requests"))
        assertTrue(AgentRequestScope.modeInstruction(AgentMode.REVIEW).contains("When the user requests"))
        assertTrue(AgentRequestScope.modeInstruction(AgentMode.EXPLORE).contains("only when"))
    }
}
