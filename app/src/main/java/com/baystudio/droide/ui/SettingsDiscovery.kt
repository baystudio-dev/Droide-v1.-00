package com.baystudio.droide.ui

/**
 * Searchable destinations in Settings, keyed by the existing SettingsCategory enum names.
 *
 * This is a discovery index, not a list of independently configurable features. Informational
 * destinations and unavailable capabilities keep that context in their descriptions. Labels come
 * from SettingsSheet and its Android development, local model and MCP sections.
 */
internal object SettingsDiscovery {
    data class Entry(
        val title: String,
        val subtitle: String,
        val searchTerms: List<String>,
    )

    private val keyboardTerms = listOf(
        "Editor & terminal keyboard",
        "Coding keyboard modes soft keyboard IME",
        "Compact (default) mode",
        "No corrections mode",
        "Normal mode",
        "Word suggestions and corrections autocorrect autocorrection",
        "Number row controlled by the keyboard app",
        "Hardware keyboard shortcuts",
        "Comfortable accessory keys",
    )

    /** In display order. Keys remain stable when user-facing copy changes. */
    val entries: Map<String, Entry> = linkedMapOf(
        "GENERAL" to Entry(
            "General", "Project, scope and session restoration information",
            listOf("Workbench", "Project", "Settings scope", "User workspace", "Session restoration"),
        ),
        "APPEARANCE" to Entry(
            "Appearance", "Choose a workspace theme",
            listOf("Theme selection", "Color scheme", "Switch to Workspace settings", ".droide/theme.json"),
        ),
        "EDITOR" to Entry(
            "Editor", "Word wrap, indentation and formatter fallback",
            listOf(
                "Text editor", "Word wrap wordwrap soft wrap", "Detect indentation", "Indentation detection",
                "Default indentation",
                "Indent style", "Spaces", "Tabs", "Tab width", "Indent size", "Continuation indent",
                "Authority .editorconfig detected style user defaults",
                "Smart typing & completion autocomplete follows the active language and LSP",
                "Workspace formatting", "External formatter fallback", ".droide/formatter.json",
                "Indentation authority",
            ),
        ),
        "LANGUAGES" to Entry(
            "Languages & LSP", "Language support information and extensions",
            listOf(
                "Language intelligence", "Detection LanguageRegistry active file",
                "LSP diagnostics completion symbols navigation", "Grammar TextMate grammars built in",
                "Manage language extensions", "Open the Extensions workbench",
            ),
        ),
        "FILES" to Entry(
            "Files & Workspace", "Workspace scope, file access and export",
            listOf(
                "Active workspace", "User vs Workspace", "File access sandboxed paths",
                "Open Files workbench", "Browse and manage the active workspace",
                "Export all", "Export status progress results previous exports",
                "Save entire active workspace to device storage", "Export file folder Explorer TAR archive downloads",
            ),
        ),
        "TERMINAL" to Entry(
            "Terminal", "Keyboard preferences and Terminal workbench",
            keyboardTerms + listOf("Switch to User settings", "Terminal engine Termux", "Open Terminal"),
        ),
        "GIT" to Entry(
            "Git & Source Control", "Repository information and Source Control workbench",
            listOf(
                "Git engine Eclipse JGit", "Safety Agent permissions mutating Git and shell actions",
                "Repository state", "Open Source Control", "Status changes branches repository actions",
            ),
        ),
        "RUN" to Entry(
            "Run, Build & Debug", "Check Ubuntu toolchain and connect Android devices",
            listOf(
                "Android & Device", "Switch to Workspace settings", "Open Run & Debug",
                "Ubuntu Android build", "JDK SDK Build Tools installed from Extensions",
                "Check Ubuntu toolchain", "Android device connection", "ADB Wireless Debugging",
                "Discover devices", "Nearby devices permission", "Developer options", "Pair device",
                "6-digit pairing code", "Available device bridge", "Connect", "Cancel connection",
                "Disconnect", "Reset pairing", "APK deployment",
            ),
        ),
        "AI" to Entry(
            "AI & Agents", "Current model, Agent conversation and sessions",
            listOf(
                "Current agent configuration", "Provider", "Model", "Reasoning provider default",
                "Context safety project-scoped permission gates", "Open Agent", "Agent sessions",
                "Browse and resume previous Agent sessions", "Choose model",
            ),
        ),
        "PROVIDERS" to Entry(
            "Providers & Models", "Provider accounts, models and local GGUF certification",
            listOf(
                "Active provider model", "Connect or manage provider", "Authentication accounts credentials",
                "Choose model", "Switch to User settings", "Local models", "Import GGUF", "Refresh",
                "Cancel import certification", "Certify", "Retry certification", "Certified",
                "Local Agent unavailable with the current 4K context window; import and certification remain available",
            ),
        ),
        "PERMISSIONS" to Entry(
            "Agent Permissions", "Workspace rules for Agent capabilities",
            listOf(
                "Project policy", "Switch to Workspace settings", "Allow Ask Deny",
                "Read workspace files", "Edit workspace files", "Run shell / mutating Git commands",
                "Fetch web resources webfetch", "Search the web websearch", "Use MCP tools",
                "Repair MCP configuration mcp_repair",
                "Run IDE build, tests, lint or file execution ide_execute",
                "Apply LSP quick fixes / formatting lsp_edit",
                "Install or repair managed runtimes/toolchains toolchain_install",
                "Click/type in the integrated browser browser_interact",
                "Let Agent browse localhost development servers browser_local",
                "Launch subagents", "Access paths outside the workspace external_directory",
                "Fine-grained rules",
            ),
        ),
        "TOOLS" to Entry(
            "Tools & MCP", "MCP status, configuration and permissions",
            listOf(
                "Tool authority", "MCP servers", "Local stdio command args",
                "Remote HTTP and env injection unsupported",
                "Server health errors discovered tools protocol details", "Retry check", "Open config",
                "Open .droide/mcp.json", "Agent Permissions", "Custom/plugin tools",
                "Agent permission controls",
            ),
        ),
        "PLUGINS" to Entry(
            "Plugins", "Open and manage extensions",
            listOf("Runtime permission boundaries", "Open Extensions workbench", "Browse and manage extensions"),
        ),
        "SKILLS" to Entry(
            "Skills", "Reusable Agent instructions for your projects",
            listOf("SKILL.md", "Personal user workspace skills", "Create import edit remove", "ZIP folder",
                "Slash /skills /skill mention @skill", "External CLI MCP", "Plugin skills", "Instructions resources"),
        ),
        "KEYBOARD" to Entry(
            "Keyboard & Input", "Coding keyboard modes, accessory keys and shortcuts",
            keyboardTerms + listOf(
                "Switch to User settings", "Context-aware keys", "IDE shortcuts",
                "Ctrl+Space Completion", "Ctrl+P Quick Open", "Ctrl+Shift+P Commands", "Ctrl+S Save",
                "Ctrl+` Terminal", "F2 Rename", "F5 Debug",
            ),
        ),
        "SECURITY" to Entry(
            "Security & Privacy", "Security information, permissions and credentials",
            listOf(
                "Security boundaries", "Secrets stored outside workspace files", "Agent policy",
                "Sensitive files paths default to Ask", "Distribution compliance information",
                "Agent Permissions", "Provider credentials", "Manage provider accounts",
            ),
        ),
        "PERFORMANCE" to Entry(
            "Performance & Storage", "Runtime and storage information; capability health",
            listOf(
                "Workspace lifetime editor terminal Agent stay active", "Indexing language services",
                "Storage projects are local-first", "Capability Health", "Inspect runtime and toolchain status",
            ),
        ),
        "NOTIFICATIONS" to Entry(
            "Notifications", "In-app feedback information and Android settings",
            listOf("Current support", "IDE feedback", "In-app status and notifications", "Android notification settings"),
        ),
        "ACCOUNTS" to Entry(
            "Accounts & Services", "GitHub connection and provider accounts",
            listOf(
                "Connect GitHub", "Sign in login OAuth browser authorization", "Restart GitHub sign-in",
                "Reconnect GitHub", "Disconnect GitHub", "Revoke GitHub access", "Repository access",
                "Grant repository access", "Choose repositories on GitHub", "GitHub App permissions",
                "Last sign-in", "Status", "Other connected services", "AI providers", "Manage providers",
                "Settings sync not available yet", "Switch to User settings",
            ),
        ),
        "ABOUT" to Entry(
            "About & Legal", "Version, official links, support, licenses and legal notices",
            listOf(
                "Droide", "Version", "Developer ${SettingsOfficialLinks.DEVELOPER_DESCRIPTION}", "Engineering BayZov",
                "Release & Distribution Status", "Privacy published website policy app-specific notice status",
                "Terms of Service published website terms", "Droide License LGPLv3 LGPL-3.0-only",
                "Open Source Licenses dependency licenses", "Third-Party Notices attributions",
                "Brand Asset Terms provider and project logo usage", "Search libraries",
                "Official links website", "Source repository GitHub", "How Droide works technology",
                "BayStudio on GitHub", "Bluesky", "Instagram", "Discord community",
                "Support & contact", "Report a bug", "Email BayStudio support supportbaystudio@gmail.com",
            ),
        ),
    )

    private val searchablePhrases = entries.mapValues { (_, entry) ->
        (listOf(entry.title, entry.subtitle) + entry.searchTerms).map { normalize(entry.title + " " + it) }
    }

    /**
     * Every query term must occur in one title, description or control phrase, in any order.
     * Category titles also participate. Separate controls cannot synthesize a nonexistent feature.
     * A blank query matches all known categories; unknown keys never match. Punctuation is literal.
     * Unicode whitespace is accepted, and case conversion does not depend on the device's locale.
     */
    fun matches(categoryKey: String, query: String): Boolean {
        val phrases = searchablePhrases[categoryKey] ?: return false
        val terms = normalize(query).split(' ').filter { it.isNotEmpty() }
        return phrases.any { text -> terms.all { text.contains(it) } }
    }

    private fun normalize(value: String): String = buildString(value.length) {
        value.forEach { character -> append(if (character.isWhitespace()) ' ' else character) }
    }.lowercase()
}
