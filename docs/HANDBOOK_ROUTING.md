# Handbook routing in the built-in agent

The initial tool surface uses lazy discovery: `droide_help` is omitted until `tool_search`
loads it. Previously AgentRuntimePrompt only included handbook guidance when the help
schema was already advertised. This left the first request without instructions to find
the handbook, even for questions about Droide's identity or features.

The prompt now gives a bounded route whenever tool discovery is advertised: search exactly
for `droide_help` (limit 1), then call it only after its schema is available. When help is
already advertised, the prompt instead instructs the agent to read the relevant section
directly. Identity questions include "what app is this?" and "who are you?". The index can
be consulted when the appropriate section is unclear. Previously read relevant guidance
can be reused. Missing tools are reported as a limitation, not treated as available.

No manual, screenshot, schema or tool result is eagerly loaded. No permission or discovery
filter changes. Plain greetings and final/tool-free responses retain their previous behavior.
This affects the shared built-in system prompt, independently of provider adapters. External
CLI bridge behavior and branding are unchanged by this fix.

Validation: two production units compiled; 52 plain JVM tests passed. The new routing suite
fails six of eight cases against the prior built source and passes all eight after the fix.
The same request-component estimate moves from 7,842 to 7,997 tokens (+155); schemas remain
unchanged. The standalone greeting estimate remains 2,041 tokens. These are local estimates,
not provider billing. Instruction delivery is verified; model obedience is not guaranteed.
No new APK or live-provider/device test was performed for this change.
