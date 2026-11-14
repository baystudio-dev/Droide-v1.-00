# Automatic topic titles

The built-in agent initially displays its existing first-message preview. After a successful
turn, an independent, cancellable utility request generates a short title in the user's
language. A standalone greeting defers generation until a meaningful user request arrives.
Neither the title prompt nor its answer is inserted into API history, typed chat, exact
rollout, tool results, compaction summaries, or the agent system prompt. UI geometry is unchanged.

## Bounded work

- Inspect at most eight user messages from the already bounded session presentation window.
  Choose the first substantive request, ignoring a standalone greeting/acknowledgment.
- Send only a UTF-8-safe prefix of that request (1,536 bytes) plus fixed title instructions.
  Strip appended attachment-context markers. Do not open any files, images, attachments,
  tools, project instructions, skill bodies or historical exact ledgers.
- Use the already selected provider/model/credentials and the existing four protocol adapters.
  There is no model discovery, provider switching, new dependency, or workspace scan.
- Do not inherit High/Ultra reasoning. Request Low effort only where the existing policy can
  express it through OpenAI-compatible/OpenRouter options. Do not activate optional Anthropic
  thinking budgets. Allocate up to 2,048 output tokens including any provider reasoning
  (256 for a known non-reasoning model), clamped to the advertised output limit.
- Allow at most two in-flight utility requests per service, with a 12-second cancellable
  deadline. Generation does not hold the main chat's busy state or await in its run.
- Persist `titleGenerationAttempted` before inference. One automatic attempt per eligible
  session, including across workspace restores; HTTP and application retries are disabled.
  A provider failure, timeout, invalid title or incomplete answer retains the provisional
  title. This trades automatic retry/re-titling for predictable cost and no request loops.

This does have a separate, bounded provider cost; it is not a zero-token operation. It adds
zero messages/tool definitions to the working agent context. It does not re-title completed
topics on every turn. Unknown/mandatory provider thinking can still exhaust the small utility
budget; an incomplete result is never published as a title.

## Ownership and persistence

The asynchronous job captures the originating session ID, never the currently selected ID.
Claim and publication use the existing session writer mutex and export admission. Publication
reads the current session and compares the expected provisional title, so a concurrent title
change wins. Deletion cannot recreate a session. New messages, typed checkpoints, pending
inputs and exact history are retained. Title-only metadata does not invalidate the conversation
revision; compaction merges the latest title and attempt flag at commit. Shutdown cancels the
owning service scope; the exit fence prevents launching or publishing new utility work.

The attempt flag defaults to false when reading older JSON. Existing first-message titles are
eligible when that first user message is still available; arbitrary imported/custom titles and
child/fork sessions are not renamed. There is no bulk backfill of old sessions. Header and saved
session list observe a metadata revision to update without adding messages to the transcript.

## Reference sources actually inspected

- VS Code `e646868227c3e3a9e3f24277c8c7220be42c0be7`:
  `src/vs/platform/agentHost/node/agentHostSessionTitleController.ts` and
  `chatContributions/sessionTitle/sessionTitleContribution.ts`. The utility-model strategy
  separates generation from the active turn, bounds input/output, persists metadata and checks
  that a user rename did not supersede the provisional title.
- Codex `7ee5efad4a30c9e05362bf3c87bf9f4670f25813`:
  `codex-rs/tui/src/app/thread_title.rs`. It uses a temporary title-generation thread, bounded
  user context, cancellation and a saved-name writer. Droide uses an ordinary stateless request
  instead of starting another agent/tool-enabled session.

This change covers the built-in agent. External CLI sessions retain their vendor-owned
conversation flow and existing local titles; no hidden extra ACP turn is sent on their behalf.

## Validation

See the adjacent task evidence for compilation, JVM/Robolectric lifecycle tests, real AgentService
loopback workflow, and title request/response coverage through all four provider adapters.
No full APK, physical-device UI or authenticated-provider test was performed for this change.
