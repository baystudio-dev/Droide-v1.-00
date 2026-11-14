# Agent context management

This audit applies to the 2026-11-10 source following media authoring and bundled
skills. Request-size measurements and executed verification are recorded in the
accompanying `context-audit` evidence; estimates are not provider billing figures.

## Measured fixed prefix

Host fixture, empty workspace, all built-in capability flags available, no prior
conversation, project instructions or attachments. These figures include the
estimator's fixed overhead and do not represent a user's complete turn or invoice.

| Scenario | Estimated input tokens |
| --- | ---: |
| Previous source, eager built-in schemas/manual | 21,793 |
| Current initial coding request | 7,842 |
| Current request after loading document tool | 8,347 |
| Synthetic 80-plugin catalog sent eagerly (comparison) | 78,637 |
| Same 80-plugin catalog, current initial request | 7,842 |

The initial coding estimate falls by approximately 64.0%. The initial
greeting keeps its existing tool-free path. Optional skills and plugins do not
silently insert their bodies into the system prompt. Project rules and selected IDE
context still add their real cost when present.

## Request composition

`AgentService` refreshes project instructions for each request attempt, including
after compaction. It reuses the admitted prompt hook output instead of running or
accumulating the hook again. User messages are not truncated to make optional IDE
context fit. Standalone first greetings retain their existing tool-free path;
follow-ups, attachments, queued work and ambiguous text retain normal task handling.

`AgentTools.definitionsWithCustom` produces the permitted capability catalog.
`AgentToolSelection` then projects the actual model request:

- Core file, search, edit/save, owned command, plan, question and skill tools stay
  available when their runtime and mode permit them.
- `tool_search` finds other tools by English capability keywords or exact names.
  Empty queries support paginated discovery. Search returns at most five entries;
  the default is three. Schemas become callable on the **next** request.
- At most twelve additional tools are loaded. Their aggregate encoded schema
  size is limited to 65,536 UTF-8 bytes and, for known models, one fifth of the
  available input allowance using the same approximate bytes/4 policy.
- Loading another page can evict older additional tools. Selection is local to
  the current run, never shared with another chat or subagent. A refresh removes
  revoked tools; it never reintroduces a capability hidden by mode or permission.
- Compatibility aliases remain supported by execution/history, but the projected
  catalog advertises canonical tools once. Profiles that forbid discovery retain
  their original allowed surface rather than losing access to their tools.

Search uses already discovered metadata. It adds no directory walk, server probe,
embedding service or background scanner. Existing MCP discovery/cache behavior is
unchanged. Discovery does not execute a tool or grant permission; a tool absent
from the request that produced a call still fails normal admission. Original input
schemas and all normal execution-time checks remain intact.

`AgentSystemPrompt` defines operational sections with explicit tool dependencies.
Browser, media, IDE/LSP, toolchain, recovery and delegation policies accompany their
advertised tools. Scope, execution evidence, exact reads, dirty-document authority,
continuation and core user restrictions remain present independently. The authority
boundary still follows project/profile/hook context. A separate exact shared prefix
keeps Anthropic's explicit cache boundary stable when extra tools are loaded. Core
tool schemas also precede additional schemas consistently. Mandatory project rules are
not removed to obtain a smaller benchmark.

## Existing bounds retained

| Component | Bound and behavior |
| --- | --- |
| Project instructions | 28,000 body characters in the automatic brief; incomplete sources are identified for explicit follow-up reads |
| IDE context | Up to 8,000 optional characters; exact admitted user text takes priority |
| Skills | Metadata catalog first; bodies/resources loaded explicitly with bounded, versioned continuation |
| Handbook | One bounded reference section on demand |
| Long tool output | Bounded model preview; retained output can be read by session-owned reference; truncation is disclosed |
| Native history | Complete call/result groups; exact original history remains separate from the compacted model window |
| Unknown models | No invented context window; independent mobile working-set guard and provider overflow recovery |

## Token estimates and images

`ContextBudget` counts UTF-8 bytes for the coarse token estimate, including escaped
JSON keys and values, system text and schemas. UTF-16 serialized character counts
remain a separate working-set measurement. The counter does not allocate a second
serialized copy of the full history. Validated native payload projections avoid
counting their duplicate public representation.
Final provider admission uses that same validated protocol projection and the actual
selected output reservation; it must not count the public/native copies twice after
an earlier preflight already selected one for accounting.

This remains an approximation, especially for CJK, code and uncommon tokenizers.
Authoritative provider usage, same-session calibration, output/reasoning reservations
and overflow recovery remain necessary. Cached tokens are not automatically deducted
from context occupancy. A small model can still reject mandatory instructions, a
single oversized user message or a complete tool group that cannot fit safely.

For source-backed Chat requests, exact wire bytes are still validated and emitted.
Legacy messages within that stream use the shared Unicode/image-aware estimate.
Inline base64 is no longer charged as hundreds of thousands of text tokens in
addition to the image allowance. The image allowance is approximate; image contents
and history are not removed or altered by accounting.

## Compaction

Legacy compaction retains the latest user request, recent complete groups, bounded
earlier user requests, and every system/developer message. Recent-history selection
uses UTF-8 size, consistent with input estimation.

Source-backed typed compaction requires a discovered input/shared limit; an unknown
limit reports the limitation while preserving exact history. It can summarize old completed groups **within the same
user turn**. Previously, everything following the latest user message was pinned,
so a long autonomous task could have no eligible history to compact. The new
selection reserves half the remaining request room for the checkpoint and continued
work, after fixed instructions, schemas and output reservation.

The latest user message, all authority messages and latest complete tool group stay
exact. A summary is historical assistant data, never a new user request or permission.
The original ledger, provider/model/endpoint ownership and native private payloads
remain unchanged. Validation checks ordered complete groups and duplicate authority
messages. Cancellation, a failed/blank/non-reducing summary or insufficient input
room leaves the existing conversation intact. Pending/incomplete tool groups are
never split, replayed or treated as execution evidence.

## External CLI sessions

`WorkspaceAgentPluginHost` sends the current prompt/attachments on the existing ACP
session. Its bounded client guidance and IDE/skill/media bridge do not copy the native
system manual or the entire Droide chat ledger into each prompt. Native CLI engines
continue to own their context, compaction and provider-specific session state. The
terminal path and CLI command capabilities are unchanged. Actual vendor context
behavior requires testing the configured CLI/model; native estimates do not claim
to measure a vendor's private prompt.

## References and tradeoffs

Reviewed local source snapshots:

- VS Code `e646868227c3e3a9e3f24277c8c7220be42c0be7`:
  `toolSearchDeferral.ts`, `prompts/toolInstructions.ts`, and
  `chatContributions/workspaceContext/workspaceContextContribution.ts` under
  `src/vs/platform/agentHost/node/`.
- Codex `7ee5efad4a30c9e05362bf3c87bf9f4670f25813`:
  `core/src/tools/handlers/tool_search.rs`, `core/src/context_manager/history.rs`,
  `core/src/compact.rs`, and `utils/string/src/truncate.rs` under `codex-rs/`.

Droide adapts these principles through ordinary function calls instead of assuming
that every provider supports a hosted tool-search API. An additional capability can
cost one discovery model step. Core work needs no such step, and subsequent requests
reuse loaded tools within the run. Search is lexical and deterministic; it does not
claim semantic ranking. Request sizes depend on actual rules, skills, loaded tools,
attachments and conversation history. Reducing a fixed prefix does not imply an
identical reduction in billed tokens or end-to-end latency.

There are no UI/layout changes or new packaged runtime dependencies in this change.
