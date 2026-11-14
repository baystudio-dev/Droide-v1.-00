# Agent workflows and session results

The native Droide chat composer provides three local commands. They open a view without contacting a model or requiring a configured provider. Commands must occupy the entire draft and have no attachments. Text with arguments continues through the selected agent's normal prompt route. External chat exposes only `/workflows` from this table; its session lifecycle and native commands belong to the connected CLI.

| Command | Purpose |
| --- | --- |
| `/results` | Browse recorded project files and assistant media from this conversation. |
| `/workflows` | Prepare a reusable review, repair, or build plan. |
| `/resume` | Review recent activities and prepare a continuation prompt. |

## Session results

Results come from successful tool activity and assistant media references. Generated document/audio outputs and artifacts record project-relative paths separately from tool text. File contents are not copied into this index or the model context. Prose and terminal output are not parsed to guess filenames.

The view presents newest results first, with one entry per current file path or media identity and 40 entries per page. Refresh captures new results without moving the page while the reader is inspecting it. Projection happens on demand, in memory, on a background dispatcher; it does not scan the workspace at application startup.

Open uses the normal editor route. Export uses the existing workspace export flow; media uses the existing attachment preview and export controls. Paths are validated and checked again before opening or exporting. Missing files, symlinks, and expired media are not silently replaced with other files. A project-file entry opens the current file, not a historical version of its bytes.

The native `send_media` tool also accepts `reference_only: true`. This records an existing visible project file, including large APK/binary outputs, without copying or loading its contents. Source read and conversation edit permissions still apply. References are not immutable snapshots and depend on the original file remaining available. Normal attachment copies retain their 64 MiB limit and seven-day retention.

External chat keeps file changes and media in the conversation; files also remain accessible through Explorer. It does not expose the native `/results` view as an external artifact inventory. A bounded window of CLI activities cannot establish a complete session inventory. Structured external file changes still map against the active session's workspace roots; foreign paths and traversal are not guessed or made exportable.

## Reusable plans

The bundled skills are `workflow-review`, `workflow-repair`, and `workflow-build`. Select a template, describe the target, then send the draft. Selecting a template does not start work or change the selected backend, provider, model, or permission mode.

Templates use the existing skill registry and can also be called with `@skill/workflow-repair` or `/skill/workflow-repair`. Workspace/user skill precedence and visibility flags still apply. Use Manage Skills to add or import custom plans. Skill bodies load through normal skill invocation, not into every conversation's initial prompt.

These are instruction templates, not an independent workflow scheduler. The selected agent retains execution ownership, turn limits, task tracking, approvals, and Stop behavior. Native runs retain their existing maximum of 96 turns. External agents retain their own execution limits. The repair/build instructions limit repeated attempts at the same failure to two cycles; that instruction does not replace the engine's enforced limits or prove that a model followed the plan.

## Continuation

The native continuation view is read-only until the user prepares and sends a prompt. It does not replay tools, grant permissions, mark work complete, or restart processes. A stopped or failed operation may have effects already; its recorded status is not a rollback guarantee.

Native continuation checks the current session, running/restoring state, persistence errors, and whether source-backed tool/result history has a complete boundary. The normal submission path rechecks execution authority when the prompt is actually sent. Switching sessions dismisses the previous session's view.

External history restores the saved conversation for inspection without starting work. On an explicit send, the backend reuses its live session or restores the original native session ID using the advertised ACP `session/load` or `session/resume` capability. Unsupported restoration fails without silently starting a replacement session. Connection, persistence, and history-read-only restrictions still apply.

Droide does not intercept external `/resume`, `/results`, or `/compact` as local actions. An exact command advertised by the active CLI session is forwarded through its native prompt route, preserving arguments. Unadvertised commands remain ordinary prompts; Droide does not infer support from a CLI's name or assume its terminal command syntax is valid over ACP. Advertised native commands with attachments remain rejected rather than losing the attachments.

## External chat presentation

All registered external chat backends use the shared composer presentation, Markdown actions, media alignment, file icons, diff/approval components, and conversation scrolling. Local commands are limited to those available for the selected backend; suggestions and dispatch use the same list. Completed external replies use the same Response presentation as streamed replies, including older saved conversations. Filtering optional step details preserves the original history paging keys.

When a CLI reports a structured plan, its task list uses the shared Tasks dock. Plan updates are stored with the original activity and survive history reload. An explicit empty plan clears the dock; an interrupted turn does not mark unfinished tasks complete. Older history without structured task metadata keeps its existing activity preview. Tasks, collapse state, and native-only notices remain scoped to their conversation.

Available model choices, reasoning levels, native CLI commands, and tool activity depend on what the connected CLI advertises. Shared UI does not invent missing backend capabilities or replace the external agent's execution engine.

## Verification

Run the focused regression selection with an Android SDK and the repository's documented JDK/Gradle setup:

```sh
python3 tools/test_agent_behaviors.py
```

The suite covers permission denial, incomplete provider streams, Stop during approval/execution, session restoration, artifact publication, on-demand skill loading, result pagination/path validation, composer routing, and UI streaming bursts. Provider fixtures use loopback servers and do not call paid model APIs. Document/audio integration tests additionally require the worker's documented Python/media dependencies.

Compose already coalesces state changes into display frames. The streaming regression verifies that a burst retains all accepted text and does not render once per chunk. No extra delay or typewriter queue is introduced into provider receipt, persistence, or tool execution. Host tests do not substitute for frame-time measurements on Android devices.
