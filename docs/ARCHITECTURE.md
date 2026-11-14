# Droide architecture and release support map

Source reviewed: 2026-11-09. This describes the current release-intended source, not proof that a signed release has been built, installed or published. Installed metadata and actual operation results take precedence. The broader-access GitHub edition is deferred; the existing storage architecture remains in place.

The agent-facing reference is [the bundled release handbook](../app/src/main/assets/agent-handbook/about%20droide.md). It exposes separate identity, architecture, diagnostic, troubleshooting, lifecycle, release and integration sections. The implementation paths below belong to a Droide source checkout, not an ordinary user's project.

## Application and workbench

Droide is a native Android application using Kotlin, Jetpack Compose, a Sora-backed editor, native terminal/process integration and an application-managed Ubuntu ARM64 environment through PRoot. It is not an embedded desktop IDE or a separate Linux kernel/VM. Remote providers, downloads, Git remotes, websites and report submission can use the network.

`DroideApplication` and `MainActivity` manage application startup and lifecycle boundaries. [WorkspaceCoordinator](../app/src/main/java/com/baystudio/droide/core/WorkspaceCoordinator.kt) binds the selected project's repository, editor authority, permissions, language services, tools and agent integrations. Panel navigation does not itself transfer ownership of a running task.

Portrait and landscape share services/state but have different presentation policies. Landscape supports sidebar, editor and Agent together; the editor takes the remaining space. Keyboard behavior follows editor/terminal/chat focus, not keyboard visibility alone. See [the landscape workbench note](LANDSCAPE_WORKBENCH_2026-11-09.md). A resized panel is not a new filesystem or process owner.

## Stores and document authority

| Store/view | Current authority | Boundary |
| --- | --- | --- |
| Canonical project | `ProjectWorkspacePolicy`, `FileRepository`; `filesDir/projects/<project-id>` | Project IDs and roots remain distinct; aliases cannot be silently merged |
| Live editor | `EditorWorkspaceState`, `WorkspaceDocumentAuthority`, `EditorSaveCoordinator` | Document identity/revision can differ from saved disk |
| Linked device folder | Persisted Android URI grant and `SafMirror` | Provider URI, private mirror and live buffer are separate views |
| External chat review copy | `LocalAgentWorkspaceMirror`; application-owned `agent-workspaces` | Pending changes are retained work, not disposable cache |
| Conversations/checkpoints | `SessionManager`, external conversation history store | History persistence is distinct from file publication/provider execution |
| Recovery/export captures | Respective editor, copy, deletion and export owners | Retention does not mean restored, exported or safe to delete |
| Chat media | `ChatMediaStore` and conversation ownership | Preview/expiring copy is not a permanent project asset |

[ProjectWorkspacePolicy](../app/src/main/java/com/baystudio/droide/core/ProjectWorkspacePolicy.kt) enforces canonical roots and one-to-one ownership. [FileRepository](../app/src/main/java/com/baystudio/droide/core/FileRepository.kt) provides I/O with mutation/publication/recovery participants. [SafMirror](../app/src/main/java/com/baystudio/droide/core/SafMirror.kt) synchronizes granted document-provider access; a content URI is not a Linux pathname. Android scoped storage still applies.

Editor-aware reads can observe unsaved text. Shell, build/test and Git observe persisted bytes. Completed agent edits to previously clean documents request exact-version persistence; pre-existing user drafts and conflicts remain protected. [Agent edit persistence](AGENT_SAVE_2026-11-09.md) specifies document/version-bound `save_files`, the pending-save barrier and reconciliation. A failed save does not justify replaying an already applied edit.

[ProjectFilePublication](../app/src/main/java/com/baystudio/droide/core/ProjectFilePublication.kt) and [EditorSaveCoordinator](../app/src/main/java/com/baystudio/droide/ui/EditorSaveCoordinator.kt) use identity/version checks, staging and receipts. They do not create an atomic transaction spanning every store or a universal filesystem compare-and-swap guarantee against arbitrary writers. Watchers help refresh views; they do not prove no concurrent write happened. Preserve both sides of uncertain publication/conflicts.

## Linux execution and toolchains

[LocalExecutionSubstrate](../app/src/main/java/com/baystudio/droide/core/LocalExecutionSubstrate.kt) owns application-local launch specifications. [FoundryUbuntuGuestEnvironment](../app/src/main/java/com/baystudio/droide/core/FoundryUbuntuGuestEnvironment.kt) manages persistent Ubuntu Base 24.04.5 ARM64. Current Linux launch requires an ARM64 Android process; packaging native libraries for other ABIs does not certify the guest on those CPUs.

The active bundle uses Ubuntu/PRoot and required extraction/native support. Packaged Alpine/QEMU runtimes are retired. Five large historical archives were removed from active source; [retained archive documentation](../third_party/native-engines/qemu/retired-packaged-runtime-2026-10-16/README.md) preserves identity and corresponding-source access. Compatibility entries for external tools do not establish an installed emulator or second default workstation. Do not restore legacy bundles as generic build repair.

PRoot shares the app's Android kernel UID. Guest root is not Android root. Bind paths, workspace validation, approvals and process ownership are separate controls, not separate-UID containment of arbitrary guest code. Android executable/storage restrictions remain. Runtime/toolchain data stay in application-managed storage rather than being moved wholesale to shared storage.

Installed tools persist across supported terminal, agent, LSP and build routes when discovery/selection finds them. Shell aliases, profiles and tab-local environment changes are not global installation. SDK setup can require a new PRoot launch for new mounts. Built-in toolchain refresh and external CLI reconnect/new-terminal routes differ; a child shell cannot alter its parent's mounts.

`AgentToolchainActions` handles inspection/planning and authorized installation, repair and selection. Exact SDK license approval is an interactive installation boundary; a general build request does not manufacture acceptance. Preserve project version requirements. Grammar, LSP, formatter, compiler/runtime, debugger and BUILD/TEST capabilities are separate roles.

[LocalProcessSupervisor](../app/src/main/java/com/baystudio/droide/core/LocalProcessSupervisor.kt) and command/process registries retain owned sessions. A yielded command is still running; continue its session/cursor. Detached jobs have explicit lifetimes. A displayed prompt, process ID or completed animation is not proof of process success/cleanup.

## Built-in and external agents

The built-in route uses `AgentService`, `AgentTools`, provider adapters, filtered tool schemas, permission/mode checks, context management, media and conversation persistence. The last request's context estimate/reported usage is distinct from cumulative billing. Reasoning/cache counts can overlap input/output. Compaction prepares a smaller continuation under history ownership/revision checks; cancellation/failure must not be reported as a committed compacted conversation.

External chat uses `WorkspaceAgentPluginHost`, ACP integration and [ExternalAgentSessionController](../app/src/main/java/com/baystudio/droide/ui/ExternalAgentSessionController.kt). Terminal-only interactive CLI use is separate. Executable discovery, CLI login, ACP adapter readiness, model/mode choices, activity events, history persistence and reviewed publication are independent observations. A healthy terminal CLI does not prove chat-adapter readiness.

External agents can edit a persistent review copy. A tool permission and approval to publish a reviewed diff have different scopes. Canonical publication goes through reconciliation/conflict handling. Pending files remain retained if publication, cleanup or ownership is uncertain; erasing old writer data is not recovery.

[ExternalIdeTools](../app/src/main/java/com/baystudio/droide/core/ExternalIdeTools.kt) complements CLI-native tools with editor-aware read/search/list, project instructions, diagnostics, read-only LSP, text help, IDE status and supported toolchain operations when advertised. `droide ide tools`, `droide ide call` and MCP configuration provide access/discovery. This bridge does not duplicate every built-in browser/media/provider capability. Its editor workspace can differ from the CLI review copy.

### User-managed providers

[UserProviderDefinition](../app/src/main/java/com/baystudio/droide/core/UserProviderDefinition.kt) validates the four supported API protocols, endpoint syntax, authentication mode and optional manual model IDs. [UserProviderStore](../app/src/main/java/com/baystudio/droide/core/UserProviderStore.kt) atomically persists bounded, versioned metadata in private `filesDir/user-providers.json`. The reserved `user.*` namespace is separate from workspace `custom.*` definitions and cannot be supplied by models.dev. Endpoint, protocol and authentication changes invalidate live model certification; credentials remain separately encrypted and endpoint-bound through the existing connection backend. Compare-before-write rejects stale edits; malformed storage is retained rather than overwritten.

[CustomProviderForm](../app/src/main/java/com/baystudio/droide/ui/CustomProviderForm.kt) replaces the connection list while editing. Save publishes a definition, not a connected account. Existing Connect & validate then certifies discovered models, or sends explicit short probes for manually supplied IDs when a catalog is unavailable. All manual probes must succeed before connection metadata is committed; failures and cancellation leave no partial certification. Probes may incur provider usage charges, disclosed in the form. Registry revisions refresh both provider/model pickers. Meta is a bootstrap provider at `https://api.meta.ai/v1`, using bearer authentication and the existing Responses adapter; its available models still come from live/catalog evidence.

See [provider setup and OpenCode reference](CUSTOM_PROVIDERS.md) for supported protocols, boundaries and pinned upstream research. These settings do not rewrite external CLI configurations.

### External history finalization

[PluginHistoryCheckpointStore](../app/src/main/java/com/baystudio/droide/core/PluginHistoryCheckpointStore.kt) binds mutations to an admitted conversation/turn identity. [SqlitePluginConversationHistory](../app/src/main/java/com/baystudio/droide/core/SqlitePluginConversationHistory.kt) schema 6 stores one generation and bounded finish receipt. Final transcript promotion, status and receipt commit together. Its versioned fingerprint includes exact reply, ordered notices, status and all media-reference fields, with explicit nullness and UTF-16 framing.

Within the supported live-owner path, an exact retry can acknowledge an already committed finish only while identity, fingerprint and stored final state still match. A changed payload, generation, owner or superseded turn cannot borrow that receipt. New-turn admission rotates identity without treating retained transcript/media as disposable. This includes supported ambiguous-commit retries; it is not universal recovery after process death or power loss.

When finalization fails, the controller keeps the exact pending finish. Explicit Send/New Session can first retry local checkpoint persistence. That retry must not rerun the previous provider turn, submit the newly typed draft or automatically promote queued work. Stop/Exit/close join owned work. Unknown outcomes stay explicit until the supported receipt/inspection path resolves them.

## MCP, browser, Git and reporting

[Manage MCP](MCP_MANAGEMENT.md) documents local stdio configuration, enablement, connection review and retirement. Settings/runtime share guarded workspace configuration; plugin contributions remain plugin-owned. Saving is not connecting. Remote HTTP/OAuth/custom environment configuration is not implemented by the current client. Already-running external CLIs can keep their own MCP connections until reconnecting.

Browser automation combines the on-device controller with DOM/native input, capture, dialog and session-scope helpers. Observation/readiness precedes interaction; dispatch and website completion are different evidence. A timeout after dispatch can have an uncertain effect and must not trigger blind replay. Read/navigation/local-server access, interactive actions, output consent and Keep active are distinct scopes. Session permission reduces repeated prompts without overriding Deny rules or authorizing arbitrary external submissions.

Git repository operations, commit-author identity, Droide GitHub authorization, provider credentials and CLI login are separate identities. A local commit is not a push; account sign-in does not grant every repository.

Report Bug is a native Settings text form. `BugReportCoordinator`, `SettingsBugReportViewModel` and `OfficialBugReportTransport` own session preparation, draft state, cancellation, one-shot submission and uncertainty. The transport uses the official CSRF/session protocol and fixed Origin/Referer; [the origin fix note](REPORT_BUG_ORIGIN_FIX_2026-11-09.md) records the mismatch. Supplied text is submitted, not automatically collected logs/files/screenshots. Server acceptance for its email service is not inbox delivery. An unknown result requires an explicit duplicate-aware retry.

## Startup, recovery and shutdown

`WorkspaceStartupPolicy` separates startup stages/observations. `StartupRecoveryPolicy` can defer automatic editor reopening after abnormal exit information on supported Android versions, retaining dirty recovery data. Editor safeguards cover typed text admission, native surface lifetime and platform callbacks. [The startup audit](EDITOR_STARTUP_CRASH_AUDIT_2026-11-09.md) separates tested Java-boundary defects from native aborts, ANRs and memory kills; a Java guard cannot catch every native abort.

Explicit Exit closes work admission, preserves state and stops/joins captured owners before requesting closure. [ApplicationExitSequence](../app/src/main/java/com/baystudio/droide/core/ApplicationExitSequence.kt) retains progress and exact pending operations. An observation timeout updates progress rather than declaring a save/cleanup safely complete. Retry continues the captured shutdown; it does not discard drafts or switch to replacement owners. Failures remain visible.

Panel navigation, foreground interrupt, agent Stop, terminal close, detached-job stop, browser Keep active and Exit have distinct scopes. Foreground services and a manual CPU WakeLock do not guarantee survival under Android/OEM resource pressure. Force-stop/process death can bypass normal callbacks, so restart recovery is a separate validation target.

## Agent handbook loading contract

[DroideHandbookPolicy](../app/src/main/java/com/baystudio/droide/core/DroideHandbookPolicy.kt) maps 19 fixed IDs to unique numbered headings. Original IDs/numbers are preserved; new support/release sections are appended. The APK-owned guide is reference data, not project instructions or permission. Its review date identifies documentation provenance, not screenshot or installed-build freshness.

The small bootstrap remains unchanged: request relevant help rather than preload the guide for greetings or ordinary coding. Each section is limited to 5,000 characters; each result to 6,500 and a built-in tool group to 8,000. The bounded document scan scales with the allowlisted section count. No `all`, arbitrary path or URL selection is added. Historical screenshot IDs/hashes and their section allowlist are unchanged; new sections are text-only.

[AgentDroideHelp](../app/src/main/java/com/baystudio/droide/core/AgentDroideHelp.kt) checks read permission, current conversation ownership, budgets and optional image capability. Its Runtime app line uses installed `BuildConfig` values even when the guide describes release intent. External IDE help uses the same bounded reader/schema without the built-in screenshot attachment path. Enlarging this handbook does not enlarge every model request.

## Release and troubleshooting evidence

Use the project's release task and signing arrangement: release APK for direct Android installation, release AAB for the applicable Play publishing workflow. Module/flavor task names vary. Record source/artifact identity, build result, signing, API/ABI coverage and release-device checks. Source backup, component compilation, another variant's build, install, launch and publication must not be conflated.

Historical build reports remain historical evidence. This documentation change does not alter signing/runtime code or certify outstanding dependency/native provenance gates and device behavior. Gate failures must be resolved through their intended evidence path; changing an artifact label does not establish release readiness.

For each incident retain the operation, installed version, project/backend/environment, failure stage, relevant output/receipt and unsaved/pending state. Prefer bounded observation and focused repair. Do not begin by deleting recovery records, resetting all data, weakening TLS/integrity checks or replaying an uncertain remote action.
