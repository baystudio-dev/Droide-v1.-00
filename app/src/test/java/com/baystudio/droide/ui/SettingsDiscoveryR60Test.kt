package com.baystudio.droide.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure host coverage of the Settings discovery index, independent of Compose or device services. */
class SettingsDiscoveryR60Test {
    private val categoryKeys = listOf(
        "GENERAL", "APPEARANCE", "EDITOR", "LANGUAGES", "FILES", "TERMINAL", "GIT", "RUN", "AI",
        "PROVIDERS", "PERMISSIONS", "TOOLS", "PLUGINS", "SKILLS", "KEYBOARD", "SECURITY", "PERFORMANCE",
        "NOTIFICATIONS", "ACCOUNTS", "ABOUT",
    )

    private fun matches(query: String): Set<String> = SettingsDiscovery.entries.keys
        .filterTo(linkedSetOf()) { SettingsDiscovery.matches(it, query) }

    private fun assertMatches(category: String, vararg queries: String) {
        queries.forEach { query -> assertTrue("$category should match '$query'", SettingsDiscovery.matches(category, query)) }
    }

    @Test fun allStableCategoryKeysHaveDistinctNonEmptyMetadata() {
        assertEquals(20, SettingsDiscovery.entries.size)
        assertEquals(categoryKeys, SettingsDiscovery.entries.keys.toList())
        assertEquals(20, SettingsDiscovery.entries.keys.distinct().size)
        assertEquals(20, SettingsDiscovery.entries.values.map { it.title }.distinct().size)
        SettingsDiscovery.entries.forEach { (key, entry) ->
            assertTrue("$key title", entry.title.isNotBlank())
            assertTrue("$key subtitle", entry.subtitle.isNotBlank())
            assertTrue("$key search terms", entry.searchTerms.isNotEmpty())
            assertTrue("$key blank term", entry.searchTerms.none { it.isBlank() })
            assertMatches(key, entry.title, entry.subtitle, *entry.searchTerms.toTypedArray())
        }
    }

    @Test fun blankQueriesPreserveTheDefaultCategoryOrderAndUnknownKeysDoNotMatch() {
        listOf("", " ", "\t\n\r", "\u00a0\u2003\u202f").forEach { query ->
            assertEquals(categoryKeys, matches(query).toList())
        }
        assertFalse(SettingsDiscovery.matches("UNKNOWN", ""))
        assertFalse(SettingsDiscovery.matches("editor", "tab width"))
        assertFalse(SettingsDiscovery.matches("", ""))
    }

    @Test fun editorControlsAreSearchableByTheirActualLabelsAndAliases() {
        assertMatches(
            "EDITOR", "Word wrap", "Detect indentation", "Default indentation", "indent style",
            "Spaces", "Tabs", "tab width", "indent size", "continuation indent", ".editorconfig",
            "External formatter fallback", ".droide/formatter.json", "smart typing", "completion",
            "wordwrap", "soft wrap", "indentation detection", "autocomplete",
        )
        assertEquals(setOf("EDITOR"), matches("tab width"))
        assertEquals(setOf("EDITOR"), matches("continuation indent"))
    }

    @Test fun bothKeyboardDestinationsExposeAllModesAndRealInputControls() {
        listOf("KEYBOARD", "TERMINAL").forEach { key ->
            assertMatches(
                key, "Compact (default)", "compact mode", "No corrections", "Normal", "autocorrect", "number row",
                "autocorrection", "soft keyboard", "IME",
                "word suggestions", "Hardware keyboard shortcuts", "Comfortable accessory keys",
            )
        }
        assertEquals(setOf("TERMINAL", "KEYBOARD"), matches("no corrections"))
        assertMatches("KEYBOARD", "Ctrl+Space", "Ctrl+Shift+P", "Quick Open", "F2 Rename", "F5 Debug")
    }

    @Test fun everyPermissionCategoryIsDiscoverableIncludingNestedBrowserAndToolchainActions() {
        assertMatches(
            "PERMISSIONS", "Read workspace files", "Edit workspace files", "mutating Git commands",
            "Fetch web resources", "Search the web", "Use MCP tools", "Repair MCP configuration",
            "Run IDE build", "tests lint file execution", "LSP quick fixes", "managed runtimes",
            "toolchain_install", "Click/type integrated browser", "localhost development servers",
            "Launch subagents", "Access paths outside workspace", "external_directory",
            "Allow", "Ask", "Deny", "Fine-grained rules",
        )
    }

    @Test fun mcpActionsAndItsUnsupportedTransportContextAreSearchable() {
        assertMatches(
            "TOOLS", "MCP servers", "local stdio", "Retry check", "Open config", ".droide/mcp.json",
            "Agent Permissions", "Agent permission controls", "discovered tools", "protocol details",
            "remote HTTP unsupported", "env injection unsupported",
        )
    }

    @Test fun nestedAndroidDevelopmentAndLocalModelActionsAreDiscoverable() {
        assertMatches(
            "RUN", "Check Ubuntu toolchain", "JDK", "SDK", "Build Tools", "ADB", "Wireless Debugging",
            "Discover devices", "Developer options", "6-digit pairing code", "Connect", "Cancel connection",
            "Disconnect", "Reset pairing", "APK deployment",
        )
        assertMatches("PROVIDERS", "Import GGUF", "Refresh", "Certify", "Retry certification", "Cancel import")
    }

    @Test fun navigationAndInformationDestinationsHaveUsefulRealSearchTerms() {
        mapOf(
            "GENERAL" to "Session restoration",
            "APPEARANCE" to ".droide/theme.json",
            "LANGUAGES" to "TextMate",
            "FILES" to "Open Files workbench",
            "GIT" to "Open Source Control",
            "AI" to "Agent sessions",
            "PLUGINS" to "Open Extensions workbench",
            "SKILLS" to "SKILL.md",
            "SECURITY" to "Provider credentials",
            "PERFORMANCE" to "Capability Health",
            "NOTIFICATIONS" to "Android notification settings",
            "ACCOUNTS" to "Grant repository access",
            "ABOUT" to "Release & Distribution Status",
        ).forEach { (key, query) -> assertMatches(key, query) }
        assertMatches("ACCOUNTS", "Connect GitHub", "Reconnect GitHub", "Disconnect GitHub", "OAuth", "Manage providers")
        assertMatches("ABOUT", "Open Source Licenses", "Third-Party Notices", "Brand Asset Terms", "Privacy", "Terms of Service")
    }

    @Test fun informationalAndUnavailableFeaturesDoNotBecomeSettingPromises() {
        assertEquals("Accounts & Services", SettingsDiscovery.entries.getValue("ACCOUNTS").title)
        assertMatches("ACCOUNTS", "settings sync not available yet")
        assertMatches("PROVIDERS", "local Agent unavailable", "4K context window")
        assertTrue(SettingsDiscovery.entries.getValue("GENERAL").subtitle.contains("information"))
        assertTrue(SettingsDiscovery.entries.getValue("PERFORMANCE").subtitle.contains("information"))
        assertFalse(SettingsDiscovery.entries.getValue("ACCOUNTS").subtitle.contains("sync", ignoreCase = true))
        assertFalse(SettingsDiscovery.entries.getValue("PROVIDERS").subtitle.contains("local Agent", ignoreCase = true))
    }

    @Test fun phantomControlsDoNotAppearInSearchResults() {
        listOf(
            "autosave", "auto save", "density", "encoding", "exclude", "watcher", "telemetry",
            "cache cleaner", "clear cache", "cache cleanup", "tree sitter", "checkpoint",
            "check for updates", "automatic updates",
        ).forEach { query -> assertTrue("Phantom control '$query' must not match", matches(query).isEmpty()) }
    }

    @Test fun matchingIgnoresCaseAndUnicodeWhitespaceAndRequiresEveryTermInAnyOrder() {
        assertMatches("EDITOR", "  TaB\tWIDTH\n", "tab\u00a0width", "tab\u2003width", "width tab")
        assertMatches("EDITOR", "detect\u202findentation", "indentation word wrap", "editor fallback")
        assertFalse(SettingsDiscovery.matches("EDITOR", "tab width unicorn"))
        assertFalse(SettingsDiscovery.matches("EDITOR", "tab width terminal"))
        assertFalse(SettingsDiscovery.matches("EDITOR", "widthtab"))
    }

    @Test fun localeAndArbitraryUnicodeInputCannotChangeOrBroadenMatches() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertMatches("EDITOR", "INDENT SIZE")
            assertMatches("PERMISSIONS", "INSTALL")
        } finally {
            Locale.setDefault(previous)
        }
        listOf("設定", "настройки", "🔧", "\uD83D", "é", ".*", "[", "tab|width").forEach { query ->
            assertTrue("Nonmatching input '$query' must remain literal", matches(query).isEmpty())
        }
        assertEquals(setOf("TOOLS"), matches(".droide/mcp.json"))
    }
}
