# Meta and custom providers

Settings → Providers & Models → Manage providers includes **Meta** even before the downloadable catalog is available. Meta Model API uses `https://api.meta.ai/v1`, bearer authentication and OpenAI Responses. Models are discovered and validated; their availability depends on the account. This is the current Meta API, not a redirect to another vendor hosting Llama weights.

## Add, connect, edit and remove

1. Choose **Add custom provider** in the connection menu.
2. Enter a display name, unique ID and API base URL, including the required version/path prefix. Do not append `/chat/completions`, `/responses` or `/messages`.
3. Select the documented API protocol. Supported choices are OpenAI Chat Completions, OpenAI Responses, Anthropic Messages and Google GenerateContent. Select whether the endpoint requires an API key.
4. Leave **Model IDs** empty for discovery, or enter exact model IDs, one per line, for servers without a compatible catalog. Up to 16 manual models can be configured.
5. **Save & connect** stores the definition. The next screen accepts the credential using the existing encrypted credential store. **Connect & validate** checks the server; **Use selected model** selects a validated model.
6. The edit icon on a custom provider opens its settings. **Remove custom provider** disconnects it and removes its definition/credential after confirmation; conversation history remains.

Definitions are user settings shared across projects, stored separately from `.droide/providers.json`. The UI ID `company` becomes `user.company` internally. Existing workspace definitions keep their `custom.*` namespace and configuration behavior. No external CLI account/configuration is rewritten.

## Validation and security boundaries

- Remote URLs require public HTTPS. HTTP is supported only for explicit loopback hosts; network target resolution uses the existing pinned-DNS policy. Private LAN addresses and arbitrary HTTP endpoints are not enabled by this form.
- URL credentials, queries, fragments, invalid ports and unsupported fields are rejected. API keys and arbitrary headers never enter the definition file. The four protocols determine the appropriate authorization header.
- Automatic discovery uses the existing bounded, paginated catalog flow. Manual models instead receive short non-streaming requests using the selected protocol. These requests can be billed by the provider. The form and connection view disclose this before validation. Refreshing/revalidating manual models repeats validation.
- A successful probe establishes that the endpoint accepts the model, protocol and credential. It does not certify every model capability such as images, reasoning or function tools. Existing runtime/model capability checks remain authoritative.
- A failed model probe, malformed response, authentication failure or cancellation does not commit a partially validated connection. No request is retried under a different protocol behind the user's back.
- Stored connection receipts bind the provider ID, canonical endpoint, protocol, authentication scheme, network scope and region. Editing a destination does not grant it the old credential. A changed destination requires explicit connection; live model certification is revoked when a definition changes.
- Edits compare the originally opened definition before writing. Concurrent stale saves/removals are rejected. File writes sync a temporary file then atomically replace the prior file before publishing the registry revision. Invalid settings remain available for diagnosis; they are not silently replaced with an empty list.
- Providers from projects or the downloadable catalog cannot occupy the `user.*` namespace. Dynamic catalog metadata cannot override user provider definitions or their model capabilities.
- At most 32 definitions, 16 manual models per definition and 128 KiB of serialized settings are allowed. These are configuration limits, not a model's context/output budget.

## OpenCode reference

The complete OpenCode source archive was downloaded and inspected at commit [`df54d0cddff3abe5d3d12692f306aca44cc3f13f`](https://github.com/anomalyco/opencode/commit/df54d0cddff3abe5d3d12692f306aca44cc3f13f). Archive SHA-256: `0f2f5b29e982b94e8b84dec8d5d7b362f08f80e96ab13927f0278139ec5e959a`.

- [Custom provider documentation](https://github.com/anomalyco/opencode/blob/df54d0cddff3abe5d3d12692f306aca44cc3f13f/packages/web/src/content/docs/providers.mdx): distinct provider ID, endpoint, credential, explicit models and compatible adapter selection. `/connect` storage alone does not define a usable provider.
- [Configuration schema](https://github.com/anomalyco/opencode/blob/df54d0cddff3abe5d3d12692f306aca44cc3f13f/packages/core/src/v1/config/provider.ts) and [current schema](https://github.com/anomalyco/opencode/blob/df54d0cddff3abe5d3d12692f306aca44cc3f13f/packages/schema/src/config/provider.ts): provider identity, model definitions and protocol-specific configuration are separate concerns.
- [Meta provider](https://github.com/anomalyco/opencode/blob/df54d0cddff3abe5d3d12692f306aca44cc3f13f/packages/ai/src/providers/meta.ts), [routing](https://github.com/anomalyco/opencode/blob/df54d0cddff3abe5d3d12692f306aca44cc3f13f/packages/core/src/aisdk-native.ts) and [Meta Responses protocol](https://github.com/anomalyco/opencode/blob/df54d0cddff3abe5d3d12692f306aca44cc3f13f/packages/ai/src/protocols/meta-responses.ts): official endpoint, bearer authentication and Responses transport. Droide uses its existing HTTP/SSE adapter and own function tools; this addition does not add Meta-hosted search/image tools.
- [Meta's official documentation](https://dev.meta.ai/docs) independently confirms the endpoint, authentication and supported API families. [models.dev](https://models.dev/api.json) supplies model metadata.

Droide follows these configuration boundaries through its native Kotlin/Compose implementation. It does not install arbitrary npm adapters or accept unsupported protocols/custom headers merely because OpenCode supports a broader plugin configuration format. Upstream source is research material outside the packaged app; no upstream code or new dependency is vendored by this change.

## Validation scope

Focused Kotlin/Compose compilation and host tests exercise persistence, stale writes, reserved identities, four protocol probes, connection failure/endpoint ownership, provider/model routing and the real Compose form. Device validation and a live Meta account request remain separate checks; this source change alone is not a release APK or proof of live account access.
