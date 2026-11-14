# Responses heartbeat compatibility

## Failure and correction

The reported HTTP 200 stream error was `Unsupported OpenAI Responses SSE event: keepalive`. Droide's Responses parser previously treated every unlisted event as unsupported, including gateway liveness messages. This is independent of model size or reasoning effort; receiving a heartbeat could interrupt an otherwise valid turn.

The Responses accumulator now accepts explicitly named `keepalive`, `ping` and `heartbeat` events as control metadata. They emit no assistant text/tool deltas, retain no replay content and do not establish completion. A control event containing an error still raises the existing typed provider error. A control label cannot suppress response fields such as text, tool arguments, response output, usage or finish reasons. Unknown event types and malformed JSON retain their existing rejection behavior.

SSE framing also discards empty/whitespace-only payloads, the exact JSON `null` flush, and the exact `: keepalive` comment serialized as data by some proxies. Filtering occurs only in SSE framing: a buffered HTTP response containing null is still invalid. Normal text that contains these words and the `[DONE]` marker remain untouched.

Completed typed heartbeat events participate in the existing wait-for-next-event idle policy, allowing long reasoning periods. Empty flushes, comments and incomplete frames do not renew that deadline. Event/line size limits, cancellation, terminal-response validation, interrupted-history ownership and retry policies remain unchanged. A heartbeat never authorizes executing a partial tool call or replaying the provider request.

## Reference

Reviewed the downloaded OpenCode source at commit [`df54d0cddff3abe5d3d12692f306aca44cc3f13f`](https://github.com/anomalyco/opencode/commit/df54d0cddff3abe5d3d12692f306aca44cc3f13f):

- [SSE framing](https://github.com/anomalyco/opencode/blob/df54d0cddff3abe5d3d12692f306aca44cc3f13f/packages/ai/src/route/framing.ts) explicitly drops `null` and `: keepalive` proxy frames.
- [Responses event handling](https://github.com/anomalyco/opencode/blob/df54d0cddff3abe5d3d12692f306aca44cc3f13f/packages/ai/src/protocols/open-responses.ts) separates no-output metadata from terminal events and provider failures.

Droide keeps its explicit event allowlist and completion gates rather than accepting arbitrary unknown payloads. No upstream implementation or new dependency is vendored.

## Validation

Regression tests cover HTTP/SSE behavior across Chat Completions, Responses, Anthropic and Google, typed heartbeat idle progress, non-progress flush timeouts, exact text/tool preservation, response errors and credential redaction, EOF without completion, retained-memory stability and real agent file writes/history. These are local host/loopback tests, not evidence of a live provider account or an installed APK. Build and physical-device verification remain separate steps.
