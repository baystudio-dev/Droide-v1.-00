# External CLI integrations and publisher updates

The existing Extensions catalog and Agent backend picker now include Antigravity, Muse Code, Kimi Code and DeepSeek Harness. All use the existing ACP v1 transport, structured activity renderer, permission requests, model/configuration discovery, cancellation, conversation history and workspace-review lifecycle. No new chat controls or changes to portrait/landscape geometry are needed.

| Extension | Initially verified distribution | Panel command | Terminal / login |
| --- | --- | --- | --- |
| Antigravity CLI | Google's Linux ARM64 1.5.3 archive | `agy acp --process-per-session` | `agy`; `agy acp --login` |
| Muse Code | Meta 1.4.13-R6996.1 plus community `@bex-co/muse-code-acp` 0.11.0 and official `@muse-code/sdk` 1.4.12 | `muse-code-acp` (SDK backend) | `muse`; `muse login` |
| Kimi Code CLI | Official `@moonshot-ai/kimi-code` 2.5.2 | `kimi acp` | `kimi`; `kimi login` |

| DeepSeek Harness | Official `@deepseek-ai/dsh` 0.2.2-rc.1 (public preview), Node.js >=24.9 | `dsh --profile acp` | `dsh --profile headless "task"`; provider setup with `dsh web --no-open` |

Use Extensions to install/update, authenticate in the shared Ubuntu terminal, then select the agent in chat. Each CLI owns its credentials and native session state. A built-in Meta/Google/Kimi provider connection is independent of CLI authentication. Models, effort settings and supported commands come from the agent's ACP metadata; no model list is invented.

## Installation and ownership

- Antigravity uses the original publisher archive. Latest resolves Google's platform manifest. SHA-512 authenticates the download; a canonical SHA-256 is recorded afterward for receipt compatibility. The independently verified 1.5.3 checksum remains a historical pin. `--process-per-session` is documented as required for client-provided HTTP MCP servers. Managed wrappers and health probes set the documented `AGY_CLI_DISABLE_AUTO_UPDATE=true` to prevent replacement of sealed bytes outside Extensions.
- Muse's official launcher normally updates its native executable. Extensions resolves the public stable channel and its ARM64 SHA-256/size manifest, then installs an exact, separate `runtime.muse-code` receipt. At this audit the channel advanced to 1.4.14-R7309.1 (371,529,960 bytes). An adapter remains bound to its selected runtime, not a mutable PATH lookup. It is downloaded on demand, not embedded in the application source or APK.
- The Muse installation plan discloses both components before execution, installs/reuses the verified runtime, then installs the adapter. The adapter receipt records an exact runtime dependency in addition to its optional managed Node dependency. Ownership verification and reuse reject missing, replaced or foreign runtimes. The adapter exports the owned guest executable path through `MUSE_CODE_EXECUTABLE`; `muse` delegates `--cli` to that same original binary. SDK mode is explicit, preserving interactive approvals and resumable sessions. No exec/yolo fallback is selected.
- Native and npm components retain the existing stage, verification, seal, activation, recovery and uninstall dependency guards. A failed adapter install can leave the reusable native receipt. As with other shared dependencies, uninstalling an adapter does not implicitly delete its runtime or login/history data.
- Kimi's current official distribution is TypeScript/Node, not the older Python `kimi-cli`. Node's complete engine constraint (`>=22.19.0`) is checked with the existing npm semver runtime gate. Kimi's postinstall migrates old global launchers; it is unnecessary for a private managed install and remains disabled. Its documented/source-defined `KIMI_CODE_NO_AUTO_UPDATE=1` preserves the managed package.
- npm installation retains registry SHA-512 locking and disables lifecycle scripts. All nine external agents accept publisher updates from Extensions. npm packages accept latest/tags/exact versions, while known historical pins still reject changed bytes. Native packages accept publisher-attested releases only: Antigravity exposes the current manifest and its historical reviewed release; an unavailable version fails explicitly. Runtime engines, owned dependencies, integrity, command health and activation checks remain enforced. No arbitrary download URL or version substitution is accepted.
- Plan and Build selection use native advertised mode IDs. Where advertised, the safe fallback is `default`; no auto/yolo/sandbox-disabling flags are added. An unsupported native sandbox or device permission remains a reported error, not a reason to silently weaken the CLI's policy.

## Source references actually inspected

The existing local VS Code checkout at `/workspace/droide-code-intelligence-2026-10-23/references/vscode`, commit `e646868227c3e3a9e3f24277c8c7220be42c0be7`, was read directly:

- [`chatSessions.contribution.ts`](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/workbench/contrib/chat/browser/chatSessions/chatSessions.contribution.ts): stable contribution IDs, provider metadata/capabilities, shared chat registration and provider-owned disposal.
- [`agentHostProviderService.ts`](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/platform/agentHost/node/agentHostProviderService.ts): provider registration and ownership.
- [`streamingToolCallDisplay.ts`](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/platform/agentHost/common/streamingToolCallDisplay.ts): structured incremental tool activity rather than parsing terminal display text.
- [`claudeCanUseTool.ts`](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/platform/agentHost/node/claude/claudeCanUseTool.ts): permission decisions belong to active sessions and abort signals.
- [`claudeAgent.ts`](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/platform/agentHost/node/claude/claudeAgent.ts): native process close and abort during disposal.

These references inform the integration boundaries. Droide keeps its own Android ACP implementation; the VS Code source is not claimed to implement these three vendor ACP commands. No VS Code tests were run.

Vendor references:

- [Google Antigravity ACP](https://antigravity.google/docs/cli/acp/), [editor integration and process-per-session](https://antigravity.google/docs/ide/extensions/acp/), [updater troubleshooting](https://antigravity.google/docs/cli/troubleshooting/), [official repository](https://github.com/google-antigravity/antigravity-cli). The public repository contains docs/examples, not the full proprietary native implementation. Official installer, platform manifests and original native bytes were examined separately.
- [Meta Muse Code](https://dev.meta.ai/docs/muse-code), official installer/launcher and release manifest; [community adapter source at v0.11.0](https://github.com/bex-co/muse-code-acp/tree/v0.11.0), including modes, SDK launch and pinned host integration tests.
- [Kimi installation](https://www.kimi.com/code/docs/en/kimi-code-cli/guides/getting-started.html), [native ACP](https://www.kimi.com/code/docs/en/kimi-code-cli/reference/kimi-acp.html), and the original published npm package including its mode taxonomy, update gate and postinstall migration source.

## Validation boundaries

Focused Kotlin/Compose compilation and host regressions cover catalog/install identity, artifact substitution rejection, runtime reuse, availability, session bootstrap/resumption, mode safety, activity projection and isolated history for all nine agents. Original Kimi and Muse adapter npm packages install with lifecycle scripts disabled. Actual upstream binaries/adapters answer ACP `initialize` with protocol version 1; original terminal commands and Muse `--cli --version` are checked separately.

The native protocol smoke checks run on Linux x86-64 with fresh homes and no provider credentials or inference requests. Official ARM64 downloads are checksum-verified and inspected separately. This is not proof of Android/PRoot execution, authenticated model access, native sandbox support, complete browser/tool execution, or vendor licensing eligibility on every device. A new APK and device acceptance are still required. No Dropbox upload is part of this change.

## DeepSeek protocol and configuration

The official full source was downloaded from `deepseek-ai/deepseek-harness`, commit `fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159`. `packages/acp/acp/src/index.ts`, its README and `packages/bundle/acp-app/cordis.patch.yml` establish the supported protocol. This is the official public preview, not a third-party DeepSeek-branded CLI.

ACP exposes structured tool activity, permission requests, model/reasoning config options and HTTP MCP. It advertises `sessionCapabilities.resume`, not `loadSession`: the shared bootstrap now negotiates `session/resume` when load is absent, retaining the selected session ID, workspace, cancellation and process ownership. It does not replay history on resume; Droide retains its own chat history. Native Plan mode is not advertised, so Build uses native defaults and unsupported Plan requests remain explicit errors. No permission-mode override is injected.

`dsh` is the only public command. `dsh web --no-open` opens the local setup UI (default http://127.0.0.1:3080) for provider configuration. `dsh --profile headless "task"` is the terminal one-task interface; an interactive TUI/login command is not invented. Settings, credentials and profiles live outside the versioned package under the CLI-owned home. Built-in Droide provider keys are not copied to it.

Updates stage and check new packages before selecting them. Configuration and history are not deleted by updates. A healthy install does not prove every future CLI version works on every Android device: incompatible engines, missing ARM64 assets, changed commands or protocol are reported; signed-in workflows and PRoot sandbox compatibility require device validation. DeepSeek's full dependency tree is large and downloaded on demand, not bundled into the APK.
