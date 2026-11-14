package com.baystudio.droide.ui

import com.baystudio.droide.core.AgentMentionTarget
import org.junit.Assert.*
import org.junit.Test

class AgentComposerShortcutsTest {
    @Test fun slashOffersLocalFeaturesWithoutBackendConfiguration() {
        val query = requireNotNull(AgentComposerShortcuts.query("/", 1))
        assertEquals(listOf("/skills", "/mcp", "/plugins", "/compact", "/results", "/workflows", "/resume"),
            AgentComposerShortcuts.choices(query, AgentComposerCommand.entries, emptyList()).map { it.token })
        assertEquals(AgentComposerCommand.MCP, AgentComposerShortcuts.command(" /mcp ", false))
    }

    @Test fun navigationRequiresAnExactCommandAndDoesNotConsumeAttachments() {
        for (text in listOf("Please /mcp", "/mcp inspect", "`/mcp`", "/mcp\nkeep this draft")) {
            assertNull(AgentComposerShortcuts.command(text, false))
        }
        assertNull(AgentComposerShortcuts.command("/mcp", true))
        assertNull(AgentComposerShortcuts.command("/unknown", false))
    }

    @Test fun onlyCommandsOfferedByThePanelCanConsumeTheDraft() {
        val available = listOf(AgentComposerCommand.WORKFLOWS)
        val query = requireNotNull(AgentComposerShortcuts.query("/", 1))
        assertEquals(listOf("/workflows"), AgentComposerShortcuts.choices(query, available, emptyList()).map { it.token })
        assertEquals(AgentComposerCommand.WORKFLOWS, AgentComposerShortcuts.command("/workflows", false, available))
        for (token in listOf("/resume", "/results", "/compact", "/mcp")) {
            assertNull(token, AgentComposerShortcuts.command(token, false, available))
        }
        assertNull(AgentComposerShortcuts.command("/workflows", true, available))
        assertNull(AgentComposerShortcuts.command("/resume", false, emptyList()))
    }

    @Test fun ignoresEmailsPathsAndCode() {
        for (text in listOf("a@example", "src/@name", "`@name", "```kotlin\n@name", "see /mc")) {
            assertNull(text, AgentComposerShortcuts.query(text, text.length))
        }
        val outsideCode = "```\ncode\n```\n@"
        assertNotNull(AgentComposerShortcuts.query(outsideCode, outsideCode.length))
    }

    @Test fun mentionInsertionPreservesSurroundingDraftAndCaret() {
        val text = "Use @gi for this change"
        val query = requireNotNull(AgentComposerShortcuts.query(text, 7))
        val choice = AgentComposerChoice("@plugin/github", "GitHub", "Installed plugin")
        val (next, caret) = AgentComposerShortcuts.insert(text, query, choice)
        assertEquals("Use @plugin/github for this change", next)
        assertEquals("Use @plugin/github".length, caret)
    }

    @Test fun staleSuggestionCannotOverwriteChangedDraft() {
        val query = requireNotNull(AgentComposerShortcuts.query("@gi", 3))
        assertTrue(runCatching {
            AgentComposerShortcuts.insert("edited draft", query, AgentComposerChoice("@plugin/github", "GitHub", "Plugin"))
        }.isFailure)
    }

    @Test fun selectionAndMiddleOfIdentifierDoNotShowDestructiveCompletion() {
        assertNull(AgentComposerShortcuts.query("@github", 3))
        assertNull(AgentComposerShortcuts.query("@github", 7, selectionCollapsed = false))
        assertNull(AgentComposerShortcuts.query("@github", -1))
    }

    @Test fun mentionsFilterByDisplayNameAndKeepMcpAndPluginIdentitiesDistinct() {
        val mentions = listOf(AgentMentionTarget("@mcp/github", "GitHub server", "MCP"),
            AgentMentionTarget("@plugin/review", "GitHub review", "Plugin"))
        val query = requireNotNull(AgentComposerShortcuts.query("@git", 4))
        assertEquals(2, AgentComposerShortcuts.choices(query, AgentComposerCommand.entries, mentions).size)
        val inserted = AgentComposerShortcuts.insert("@git", query, AgentComposerChoice("@mcp/github", "GitHub", "MCP"))
        assertEquals("@mcp/github ", inserted.first)
        assertNull(AgentComposerShortcuts.query(inserted.first, inserted.second))
    }

    @Test fun suggestionsStayBoundedAndUnknownExternalCommandsPassThrough() {
        val query = requireNotNull(AgentComposerShortcuts.query("@", 1))
        val mentions = (1..100).map { AgentMentionTarget("@plugin/p$it", "Plugin $it", "Plugin") }
        assertEquals(8, AgentComposerShortcuts.choices(query, emptyList(), mentions).size)
        assertNull(AgentComposerShortcuts.command("/review", false))
    }
    @Test fun slashAndAtCanSelectNamespacedSkillsWithoutExecutingLocalCommands() {
        val target = AgentMentionTarget("@skill/review-plugin:review-code", "review-plugin:review-code", "Skill")
        for (token in listOf("/review", "@review", "/skill/review-plugin:", "@skill/review-plugin:")) {
            val query = requireNotNull(AgentComposerShortcuts.query(token, token.length))
            val choice = AgentComposerShortcuts.choices(query, AgentComposerCommand.entries, listOf(target)).single()
            assertFalse(choice.command)
            val inserted = AgentComposerShortcuts.insert(token, query, choice).first
            assertTrue(inserted.contains("skill/review-plugin:review-code"))
            assertNull(AgentComposerShortcuts.command(inserted, false))
        }
        assertEquals(AgentComposerCommand.SKILLS, AgentComposerShortcuts.command("/skills", false))
    }
}
