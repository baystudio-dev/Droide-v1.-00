# Provider/model routing audit — 2026-11-09

Scope: source audit of every provider family expressible by Droide's registry/catalog and workspace configuration. This is not a live generation test of every vendor/model. No provider credentials or paid inference calls were used. Vendor capability metadata remains authoritative; model names alone cannot establish endpoint capabilities or token limits.

## Coverage matrix

`Chat` means OpenAI Chat Completions; `Responses` means OpenAI Responses. Explicit endpoint metadata may select Responses for a provider whose base protocol is Chat; a routed model name alone does not change gateway authority.

| Provider identity | Default protocol | Reasoning envelope / authority | Output and context metadata |
| --- | --- | --- | --- |
| `openai` | Chat; reviewed native reasoning families choose Responses, explicit endpoint metadata wins | Chat `reasoning_effort`; Responses adapter emits `reasoning.effort` | Live metadata + exact models.dev entry; native OpenAI Chat uses `max_completion_tokens`, Responses `max_output_tokens` |
| `anthropic` | Anthropic Messages | Manual `thinking.budget_tokens` or adaptive `thinking` + `output_config.effort`, constrained by supported model/profile metadata | `max_tokens`; numeric thinking must leave answer room; shared/input/output limits remain distinct |
| `gemini` (catalog alias `google`) | Google GenerateContent | Gemini 2.5 numeric `thinkingBudget`; reviewed Gemini 3+ `thinkingLevel` | `generationConfig.maxOutputTokens`; live `inputTokenLimit` is input-only, `outputTokenLimit` is output-only |
| `openrouter` | Chat, unless exact endpoint metadata selects Responses | Chat `reasoning.effort`; gateway supported efforts/kinds retained; Responses uses its reviewed serializer | `max_tokens` on Chat; nested `top_provider` limits and supported parameters are parsed |
| `pollinations` | Chat | Only advertised/reviewed compatible effort support; no native protocol chosen from a routed name | Central allocation with exact provider/model capability metadata; missing limits remain unknown |
| `groq` | Chat | Advertised compatible effort support plus reviewed provider-specific profiles | Same central allocation/explicit-limit validation |
| `deepseek` | Chat | Advertised compatible support; mere reasoning boolean does not invent effort serialization | Same central allocation/explicit-limit validation |
| `together` (catalog alias `togetherai`) | Chat | Advertised/reviewed compatible effort support | Alias resolves to exact catalog limits; runtime metadata takes precedence when supplied |
| `fireworks` (catalog alias `fireworks-ai`) | Chat | Advertised compatible support plus reviewed provider-specific profiles | Alias resolves to exact catalog limits |
| `deepinfra` | Chat | Advertised/reviewed compatible effort support | Central allocation and metadata |
| `huggingface` | Chat | Router remains Chat unless endpoint metadata explicitly changes it | Central allocation and metadata |
| `nvidia` | Chat | Advertised/reviewed compatible effort support | Central allocation and metadata |
| `xai` | Chat, reviewed `@ai-sdk/xai` route | Compatible effort/profile support; package name does not invent a native serializer | Exact provider/model catalog limits |
| `mistral` | Chat, reviewed `@ai-sdk/mistral` route | Compatible advertised/reviewed effort support | Exact provider/model catalog limits |
| `cerebras` | Chat, reviewed `@ai-sdk/cerebras` route | Compatible advertised/reviewed effort support | Exact provider/model catalog limits |
| `cohere` | Chat compatibility endpoint, reviewed `@ai-sdk/cohere` route | Compatible advertised/reviewed effort support | Exact provider/model catalog limits |
| Other models.dev `@ai-sdk/openai-compatible` providers | Chat; explicit supported endpoint/shape may select Responses | Compatible effort wire only when supported; unsupported packages are not automatically made callable | Exact provider ID + exact model ID; no inheritance from another vendor merely because model names resemble each other |
| Local `ollama`, `lmstudio`, `vllm`, managed `local.*` | Loopback Chat | Runtime/catalog metadata when present; unsupported reasoning settings remain unavailable | Local metadata if advertised, otherwise explicit bounded fallback allocation, not a claimed model ceiling |
| Workspace `custom.*` | Chat; exact supported endpoint metadata may select Responses | Same reviewed compatible serializer; auth type does not imply a different wire protocol | No implicit models.dev alias to another provider; discovery/configuration uses exact IDs |
| Advanced cloud auth on `custom.*` (Azure, ADC, Copilot, Bedrock) | Configured Chat-compatible endpoint | Credential resolution does not prove model/transport compatibility | Configured-model and Bedrock control-plane shortcuts may supply IDs without detailed token capability metadata; these remain unknown rather than receiving fabricated vendor limits |

## Confirmed additional fixes from this audit

1. **Namespaced model identity corruption.** `ProviderModelCatalog.parse` formerly removed `models/` from every object entry, including non-Google providers. A custom provider's real `models/company-model` could be certified/requested as `company-model`, while string-form entries retained the prefix. The fix limits resource-prefix normalization to Google and applies it consistently to object and string entries. Four `ProviderCatalogIdentityTest` cases cover exact identity, native normalization, invalid names, and capability binding.
2. **Native discovery stopped after the first page.** `ProviderAuthManager.validate` formerly ignored Google `nextPageToken` and Anthropic `has_more`/`last_id`. `ProviderModelPagination` now follows those two reviewed cursor contracts on the original configured `/models` path only. It URL-encodes opaque cursors, rejects malformed/repeated cursors, observes cancellation, and bounds collection to 32 pages, 8 MiB aggregate decoded/re-encoded UTF-8 catalog content, and the existing 1,000 model UI/certification cap. Network failure on a requested later page fails the discovery; collected entries are staged before runtime metadata publication. Ten tests cover helper behavior and actual loopback ProviderAuthManager discovery.

## Boundaries retained

- The 1,000-model catalog/certification cap remains deliberate; this is not an unlimited live-model census.
- Arbitrary OpenAI-compatible gateways may define other cursor schemes. Those are not guessed or followed; reviewed Google/Anthropic semantics do not silently apply to unrelated endpoints. Provider-returned pagination URLs are never followed.
- Model discovery and credential resolution are not successful inference probes. Configured cloud model IDs can lack capability metadata; a successful credentials/model-list validation does not prove account access or generation support for each model.
- Bedrock's separate control-plane discovery still returns configured/foundation model IDs without a full model capability projection. This audit does not certify every returned Bedrock foundation model for its configured Chat-compatible endpoint.
- Unknown/native aliases such as unversioned Gemini names do not receive invented thinking-level schemas. Unsupported settings should remain unavailable rather than being inferred from a marketing name.
- Source audit and local HTTP fixtures cannot certify every live endpoint's undocumented metadata, account entitlements, rate limits, or current model deployment.

## Source references

All paths below are under `app/src/main/java/com/baystudio/droide/core/`:

- `ProviderRegistry.kt`: bootstrap identities, configured/discovered/managed authority, catalog aliases.
- `ModelsDevCatalog.kt`: reviewed package projections, exact per-model endpoint/transport override compatibility, token metadata.
- `ModelCapabilityRegistry.kt`: catalog/runtime merge and exact provider/model binding.
- `ProviderRuntimeCapabilities.kt`: live capabilities, supported parameters/endpoints, reasoning option kinds and bounds.
- `ProviderModelRequestPolicy.kt`: authority-first protocol selection and nonconversational-model exclusion.
- `ProviderEndpointCatalog.kt`: local builtins and custom/cloud-compatible endpoint configuration.
- `ProviderModelCatalog.kt`, `ProviderModelPagination.kt`, `ProviderAuthManager.kt`: discovery normalization, bounded native pagination, staged capability publication.

Successful test counts and frozen source hashes are recorded separately by the host verification harness; this matrix does not independently claim those tests have passed.
