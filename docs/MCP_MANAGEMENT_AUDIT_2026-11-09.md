# Manage MCP and chat shortcuts — 2026-11-09

## Result

Manage MCP now edits the active workspace's stdio servers through a guarded
configuration store instead of only displaying connection health. Chat exposes
the feature through `/mcp`, installed plugins through `/plugins`, and existing
native compaction through `/compact`. `@` completes installed plugin and enabled
MCP identities without new permanent chat controls.

The base is the full provider-output-fixes source archive with SHA-256
`9ba680860ea82f57387fad94513e92a8ce123d889bbaa69c4ef55abfb6d2e937`.
This change preserves the earlier provider, editor, CLI and LSP fixes. Previous
provider tests are not counted as new verification for this change.

## Findings addressed

- The existing Manage MCP route exposed health/configuration links without a
  complete server-management flow. A separate configuration store and workspace
  controller now support add, edit/rename, remove and enablement. UI code only
  owns form/navigation state; validation and concurrency live outside Compose.
- Refresh under Ask did not authorize a connection. An explicit, one-use Connect
  once review now covers the displayed command/arguments. Deny remains binding;
  configuration, host and local-script provenance are checked again before use.
  Connecting does not grant persistent permissions for agent tool calls.
- Disabled servers could not be consistently represented across discovery,
  health and external session projection. Enablement now persists in config,
  retires owned sessions, invalidates discovery and excludes disabled entries
  from tools, mentions and newly prepared external CLI configurations.
- Health reconciliation could overwrite concurrent updates or accept obsolete
  results. Registry changes are synchronized, fingerprint-bound and reject late
  results for deleted/changed/disabled servers.
- Configuration writes now use FileRepository's existing document authority and
  disk-version comparison. Dirty editor buffers, stale forms and concurrent edits
  cannot be silently overwritten. Unknown fields survive edits, malformed files
  remain intact, and save/enable never starts a server.
- Command and mention completion retains selection/IME state and validates the
  current token before replacement. Read-only inventory loads only when needed;
  it does not call the provider or connect to MCP servers. Local navigation is
  dispatched before model readiness checks. Attachments and unknown external
  CLI commands retain their normal submission paths.

## Reference design

The source files and pinned commits are linked in `MCP_MANAGEMENT.md`. VS Code's
MCP commands/configuration mapping informed named server actions and first-class
configuration editing. Codex's MCP CLI and connection manager informed validated
argv, separate configuration/connection actions and disabled-server filtering.
Codex's slash-command and plugin-mention code informed temporary composer
suggestions. No upstream implementation was copied; these patterns use Droide's
existing workspace writer, execution host, permissions and tool registry.

## Verification

Final evidence identifies exact input hashes and commands:

| Check | Result | Evidence |
| --- | --- | --- |
| Focused Kotlin/Compose compilation | Passed, 67 inputs | `compile-v9` |
| MCP runtime/configuration/selection/identity/deadline/pool tests | 55 passed | `tests-v6/core-results.log` |
| Installed mention inventory | 2 passed | `tests-v11/core-results.log` |
| Actual Compose add/disable/remove and connection review | 3 passed | `tests-v11/McpManagementWorkflowTest-results.log` |
| Cursor/token/command completion cases | 8 passed | `tests-v10/AgentComposerShortcutsTest-results.log` |
| Actual composer selection, focus and rejected draft at 320 dp | 3 passed | `tests-v10/AgentComposerInputWorkflowTest-results.log` |

Total: **71 distinct passing tests**. Earlier/repeated runs are not added to this
count. The management UI also verifies that Edit/Remove touch targets do not
overlap. The actual production composables were inspected in host-rendered PNGs;
these are not screenshots from a physical device.

The focused harness reuses cached Android/JNI/resources and unrelated previously
compiled project classes. It compiles the changed core components, extracted
composer, MCP settings and SettingsSheet plus their current dependency closure.
It does not compile the entire app: large AgentPane/IdeScreen integration files
were reviewed for wiring, but are outside the final compilation closure. An
earlier broader compilation hit incompatible cached project APIs; its log is
retained rather than represented as a successful full-app check.

One existing test,
`McpSelectionSafetyTest.healthRefreshCannotStartAChangedConfiguration`, is excluded
only in the focused harness because its cached AgentService calls an older
EditHistory constructor. The production test was not disabled or changed. New
tests independently verify configuration-bound connection review and changed
selection rejection, but do not replace running that test in a current full
project environment. Early UI harness runs also hit host dialog/PixelCopy issues;
the final tests use the existing Android Compose activity rule and View drawing
for screenshots without disabling idling or admission checks.

## Limits and next device checks

- No APK/native/full build, physical-device run or paid/live provider request was
  performed in this task. Run the normal Android build and full project tests in
  the supported environment before release.
- This client supports local stdio. Remote HTTP, OAuth and custom environment
  variables remain unsupported and are explicitly rejected/described in Help.
- Exercise `/mcp`, `/plugins` and `@` with the actual Android keyboard, rotation,
  narrow screen, built-in agent and each installed external CLI backend.
- Try a real supported stdio server: add, Connect once under Ask, discover/call
  a tool through its normal permission flow, disable, re-enable and remove.
  Recheck denied policy and editing the config while the form/review is open.
- Existing external CLI processes own their connections. Restart those sessions
  to apply changed MCP configuration; this UI does not interrupt active CLI work.
- Mentions guide tool/plugin choice; they do not force a provider to call a tool,
  enable a disabled plugin or bypass the existing permission system.
