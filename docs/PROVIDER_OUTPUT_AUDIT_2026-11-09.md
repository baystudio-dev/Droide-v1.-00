# Provider output/reasoning audit and fixes — 2026-11-09

Base: the corrected CLI/LSP source archive `Droide-Full-Source-2026-11-09-cli-lsp-lifecycle-fixes.zip`, SHA-256 `6ec0ad7ade3f353ad51d3a48b2e0814c6b82e08738d5b130ea2d0ccbe2df1e69`. Its earlier CLI/LSP fixes remain included.

## User-visible symptom and source findings

The screenshot reports `finish:length` with Reason High and approximately 2% context use. Context occupancy and a single response's output reservation are different limits. Reasoning may share the output allowance with visible text and tool arguments; a small context percentage does not prevent output exhaustion. The screenshot alone does not reveal the exact gateway request, provider metadata, usage breakdown, or account limit.

Confirmed source defects:

1. The application universally clamped automatic output to 8,192 tokens, even when a model advertised more. OpenAI/OpenRouter effort, Anthropic adaptive thinking and Gemini level thinking did not receive extra room when metadata was absent.
2. Gemini numeric thinking could consume all but one token of an output ceiling. This left almost no room for visible text or a tool call.
3. Typed-history compaction independently forced a 2,048-token total and omitted the selected reasoning configuration. Fixing the main agent budget alone did not fix this path.
4. Model discovery removed `models/` from non-Google IDs. That prefix is a Google resource convention, but can be part of a legitimate exact model ID elsewhere.
5. Native model discovery ignored continuation cursors, so subsequent Google/Anthropic pages were never read.

## Allocation policy

- Use valid advertised output capacity, retaining input room for models with a shared context window. The ordinary automatic allocation retains the existing shared-window quarter policy; explicit numeric thinking can require additional reservation, still checked against the full input/output window and known output cap.
- If output metadata is absent, ordinary requests retain the 8,192 fallback. Reasoning effort/adaptive requests use a local 32,768 reservation, or 65,536 for extended effort. These are local fallback allocations, not invented claims about an unknown model's supported maximum.
- Explicit caller output caps are preserved and validated. Conflicting aliases and values above an advertised cap fail before network use.
- Numeric Gemini presets reserve answer/tool-call room: up to 4,096 tokens, sharing smaller ceilings evenly. Provider reasoning minima/maxima still apply; impossible configurations fail explicitly.
- Preflight, recent-history retention, compaction budgets and outgoing requests use the same selected output reservation. Input-only ceilings are not reduced by output twice.
- Typed summary requests use the selected reasoning/model allocation. The summary's independent text/storage and shrinking-checkpoint constraints remain enforced. Summarization does not acquire tools or permission to execute historical instructions.
- Length/incomplete outcomes remain failures. Incomplete tool arguments are never admitted as completed actions, and the patch does not blindly replay provider requests.

Larger maximum allocation does not force a model to spend that many tokens. Actual usage remains provider-reported; neither this patch nor metadata can guarantee that a particular task fits a provider/account's true limit.

## Provider audit scope

The review covers all twelve bootstrap provider routes, the supported native/catalog aliases, dynamically discovered compatible providers, custom endpoints and local model routes. It compares the four wire protocols and their capability/limit mappings. The companion provider matrix lists exact coverage and remaining compatibility limits.

This is a source/protocol audit, not a successful live invocation of every model marketed by every provider. Unknown gateway behavior, account-specific restrictions and missing/inaccurate metadata cannot be certified from a model name. No paid/live provider calls or credentials were used.

## Upstream comparison

VS Code checkout `e646868227c3e3a9e3f24277c8c7220be42c0be7`, bundled Copilot:

- `extensions/copilot/src/platform/endpoint/node/chatEndpoint.ts:247` uses advertised output capacity rather than universally clamping it to 8,192.
- `extensions/copilot/src/extension/prompt/node/chatMLFetcher.ts:162` initializes the outgoing limit from the endpoint and honors explicit overrides.
- `extensions/copilot/src/platform/endpoint/node/responsesApi.ts:1525` and `chatMLFetcher.ts:1980` retain length/incomplete failure semantics and partial public text.

Codex checkout `7ee5efad4a30c9e05362bf3c87bf9f4670f25813`:

- `codex-rs/codex-api/src/common.rs:281` defines its Responses request without a universal `max_output_tokens` cap.
- `codex-rs/codex-api/src/sse/responses.rs:451` preserves incomplete-response evidence and reports failure. This does not authorize executing incomplete tool calls or blindly retrying a whole turn.

## Verification results and boundaries

**106 unique focused tests passed**: 96 allocation/context/reasoning/transport/catalog/compaction-policy tests and 10 pagination tests. The combined harness compiles 24 complete current production files plus two exact source slices. Whole current `ProviderAuthManager` is exercised over HTTP loopback for two-page Google/Anthropic discovery and second-page failure. Final compiled source/test input hashes match the delivered files.

Negative controls reproduce the bugs: the previous budget fails 7/9 new allocation tests; the previous catalog fails 4/4 new identity tests. There is no automatic retry or incomplete-tool execution added to make tests pass.

Native pagination stays on the original configured model-list endpoint and follows only reviewed Google/Anthropic opaque cursors. It rejects malformed/repeated cursors, checks cancellation, and limits collection to 32 pages, 8 MiB aggregate content and the existing 1,000-model picker/certification cap. Requested pages are collected before publishing runtime capability metadata. Arbitrary gateway pagination contracts are not guessed.


Focused Kotlin/JVM tests exercise allocation, model metadata, reasoning serializers, output field mappings, preflight, compaction request policy, model identities, and buffered/SSE transport using local HTTP fixtures. The prior budget is a negative control: seven of nine new allocation tests fail against its 8,192 clamp.

Some existing expectations were already stale before this patch: explicit unsupported reasoning now fails instead of being silently dropped, advertised effort metadata overrides name-based fallback presets, and native OpenAI o3 selects the Responses output field. These tests were corrected to match the existing source contracts; the prior failures and hash evidence are retained.

No APK/native/full build, Android device test, or live-provider model benchmark was run. The focused harness compiles current complete helpers/adapters and exact slices of AgentRequestSupport and LlmClient, with unchanged dependencies from filtered earlier host JARs. It does not execute the full AgentService/SessionManager lifecycle or complete typed owned-history compaction. Exact input manifests, final counts, negative controls and runner boundaries accompany the delivery.
