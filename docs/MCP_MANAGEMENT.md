# Manage MCP

Manage MCP opens Settings → Tools for the active workspace. It supports adding,
renaming, editing, enabling/disabling and removing local stdio servers. Expand a
server to see connection details and actions. Plugin contributions remain owned by
their plugin and cannot be rewritten from this workspace form.

## Access from chat

Type `/` at the beginning of the chat draft to show a temporary command list.
`/mcp` opens workspace MCP settings and `/plugins` opens installed plugins. These
navigation commands run locally, including before a model is configured, and do
not send a message or call a provider. `/compact` uses the built-in agent's existing
context compaction action; external CLI commands that Droide does not own pass
through unchanged. A command with attachments is not consumed as local navigation.

Type `@` at a word boundary to search installed plugin and enabled MCP identities.
Choosing an entry inserts `@plugin/id` or `@mcp/server-name` at the current cursor;
it does not send the draft, start a server or grant tool permission. The agent
receives the identity with the message and uses its existing tool/skill catalog.
Typing in code, an email address or a path does not open the picker. The picker is
bounded, dismissible and keeps keyboard focus in the input. It introduces no
permanent chat button or extra toolbar row.

MCP settings use a compact list. Connection information and server actions appear
only when a row is expanded; configuration, permissions and help live in its
overflow menu. The server form scrolls on smaller screens.

Configuration is saved in `.droide/mcp.json`:

```json
{
  "servers": {
    "local": {
      "type": "stdio",
      "command": "node",
      "args": ["server.js"],
      "protocol": "auto",
      "enabled": true
    }
  }
}
```

Arguments are a JSON array of strings, preserving spaces and empty arguments;
they are not split as a shell command. The runtime validates server names,
executable/argument bounds, transport and enablement types. Remote HTTP, OAuth
and custom environment variables are not implemented by this stdio-only client.
The form and runtime reject those transports/settings instead of pretending they
are connected. Protocol Auto preserves existing negotiation behavior; 2025 and
2026 preferences remain available for compatibility.

Saving or enabling a server does not launch it. Connect displays the exact command
and arguments, and Connect once authorizes that connection/discovery attempt under
the default Ask policy. A Deny rule remains authoritative. The reviewed selection
is one-use and belongs to the workspace/controller; changed configuration,
execution host or referenced script invalidates it. This action does not create
persistent permission grants for agent tool calls.

Disabling or removing a server retires its Droide stdio session and invalidates
cached tool discovery. Late results cannot restore deleted/disabled health rows.
Disabled servers are excluded from tool discovery and new external CLI sessions.
Already-running external CLI processes own their MCP connections: restart those
sessions to apply configuration changes. Manage MCP does not interrupt their
in-flight work to force a reconnect.

The form writes through FileRepository with the version of the configuration the
user opened. Concurrent disk edits and dirty editor buffers block conflicting
writes. Other servers and unknown JSON fields survive edits. Invalid existing
configuration is left intact: use Open config to repair it, then Reload. JSON
formatting is normalized on save; comments are not supported by this JSON format.

## Source references

Reference snapshots were read locally; no upstream implementation was copied.

- [VS Code MCP commands](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/workbench/contrib/mcp/browser/mcpCommands.ts): Add Server, enablement-aware status, start/stop eligibility and surfaced action failures.
- [VS Code configuration mapping](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/workbench/contrib/mcp/common/mcpConfigFileUtils.ts): configuration remains a first-class editor document.
- [Codex MCP CLI](https://github.com/openai/codex/blob/7ee5efad4a30c9e05362bf3c87bf9f4670f25813/codex-rs/cli/src/mcp_cmd.rs): validated named-server configuration, explicit argv, separate add/remove and connection operations.
- [Codex MCP connection manager](https://github.com/openai/codex/blob/7ee5efad4a30c9e05362bf3c87bf9f4670f25813/codex-rs/codex-mcp/src/connection_manager.rs): retain disabled server configuration while excluding it from startup.
- [Codex slash commands](https://github.com/openai/codex/blob/7ee5efad4a30c9e05362bf3c87bf9f4670f25813/codex-rs/tui/src/slash_command.rs): local MCP/plugin navigation and explicit compaction commands.
- [Codex chat widget](https://github.com/openai/codex/blob/7ee5efad4a30c9e05362bf3c87bf9f4670f25813/codex-rs/tui/src/chatwidget.rs): refresh installed plugin identities for mention suggestions without starting a model turn.

## Verification scope

Regression tests cover guarded configuration writes, dirty editor protection,
connection review, runtime retirement, late health responses, cancellation,
external-session projection and actual Compose management controls. Focused host
compilation and Robolectric tests also cover cursor-aware completion, draft limits,
disabled mention filtering and keyboard focus on a 320 dp layout. They are not an
APK build or a physical-device test. See `MCP_MANAGEMENT_AUDIT_2026-11-09.md` for the
exact test inventory and focused harness limitations.
No live third-party MCP credentials/server or remote transport was exercised.
