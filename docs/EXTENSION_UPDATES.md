# Extension update ownership

Extensions contains application features, external tools, private integration components,
and project dependency detectors. An entry in this catalog is not always a separately
installable package. `ExtensionUpdatePolicy` gives each family an explicit update route.
The same policy controls the detail pane and rejects unsupported version requests from
agent/toolchain callers; a request for `latest` must never silently become an old release.

## Routes

| Route | Update owner | Behavior |
| --- | --- | --- |
| Publisher | External tool publisher/package manager | Explicit **Update / install latest** or **Choose version** resolves a version and prepares the existing installer. |
| Droide | Application or coordinated analysis adapter | Updated in a Droide release. Compatible components offered by that release remain installable; no misleading independent latest button. |
| Parent | Private integration runtime/helper | Links to its owning integration. Versions move with that integration, independently of terminal defaults. |
| Project | Framework dependency | Gives project-specific manifest/lockfile guidance. Does not install a global framework or change an arbitrary workspace automatically. |
| Upstream manager | User-managed language server | Links to upstream update instructions. Droide discovers the selected PATH executable without imposing its own version pin. |
| Components | Android Development bundle | Links to the SDK, build tools, JDK and Gradle entries. Project builds continue to use their Gradle Wrapper. |

This follows the distinction in the referenced VS Code extension-management service:
application-owned components have product compatibility rules, while independently
installed extensions can select publisher versions. VS Code reference snapshot:
`e646868227c3e3a9e3f24277c8c7220be42c0be7`,
`src/vs/platform/extensionManagement/node/extensionManagementService.ts`.
This is an architectural reference, not a claim that Droide installs VSIX extensions.

## Publisher updates and compatibility

All nine external agents retain independent publisher selection: OpenCode, Codex,
Claude Code, Gemini CLI, GitHub Copilot CLI, Antigravity, Muse Code, Kimi Code,
and DeepSeek Harness. See `EXTERNAL_AGENT_ADDITIONS.md` for their actual protocols,
installation dependencies, credentials, and testing limits.

Buf, Terraform LS, Texlab, Marksman, Vim language server and PerlNavigator now accept
publisher versions beyond their original reviewed releases. ZLS can update within the
0.16.x line supported by the current Zig compiler adapter. A new Zig compiler line
needs an adapter update; selecting an incompatible line is rejected explicitly.
The official Nomic Foundation Solidity language server now has an npm installation
and update route, using its published command and package identity.

Historical release hashes remain authoritative for those exact versions. They must
never attest a newer version. New native releases need their own publisher checksum
and exact official download URL. New npm releases need complete registry URL/integrity
locks. The old Vim/PerlNavigator dependency closures remain enforced for historical
receipts; newer versions use their own integrity-locked publisher dependencies.
Publisher notices are retained, with historical notice fingerprints checked only for
their historical versions. Layout, bounds, runtime and command health checks remain.

Buf and ZLS installer health scripts bind their version assertions to the selected
release. Their original-version scripts remain byte-for-byte identical so existing
receipts do not change merely by upgrading Droide. ZLS resolves the highest stable,
compatible patch, excluding development and other compiler lines. Terraform resolves
the highest numeric stable release from HashiCorp and requires that archive's entry in
its SHA256SUMS. Metadata requests use an Accept header supported by those publishers.

Successful metadata resolution is not proof that a future release runs on Android.
Installation retains the staged runtime/protocol checks and activation transaction.
An incompatible upstream release must fail before replacing the active package.
There is no automatic downgrade to an older version disguised as `latest`.

## Coordinated integrations

GraphQL, Taplo, C#, Vue, Nim, Svelte, Prisma legacy schemas, ELP, CMake, PowerShell
Editor Services, Sqruff, Clojure LSP, Make LS and LemMinX currently have source-specific
adapters or runtime/dependency contracts. Their integrated versions update with Droide,
as do their private helpers. This is disclosed in the detail pane; they are not
advertised as independently compatible with every upstream version.

When updating one of these integrations, update its adapter, artifact identity,
dependency closure, health contract and regression fixtures together. Preserve old
receipt interpretation and do not reuse version-specific coordinate repairs, source
attribution, compiler assumptions or private runtimes for a different upstream release.
Promote a family to independent publisher selection only after removing those actual
couplings, not merely its version guard.

Metals, asm-lsp, ElixirLS, HLS and the R languageserver use their upstream installation
workflow. Their detail panes explain which executable/library Droide consumes and link
to the relevant installation/update documentation. Project frameworks similarly follow
their project package manager rather than a separate global Extensions installation.

## Responsiveness

The update policy is pure in-memory metadata. Opening the list, navigating details,
starting an agent turn or detecting a command adds no publisher requests, recursive
package scans, version polling, or health processes. Publisher metadata resolution
runs only during an explicit install/update request. Existing lightweight presence
detection and receipt projection remain separate from explicit installation verification.
Cancellation and existing background-install progress continue to use the same owner.

## Verification

`ExtensionUpdatePolicyTest` enumerates the complete production catalog. Every advertised
publisher update must reach a supported installer/package-manager recipe. Parent links
must resolve without cycles. Built-in/coordinated/project entries must not masquerade
as a global latest installation. Adding a family without a usable publisher route causes
this audit to fail instead of leaving another nonfunctional update button.

`ExtensionPublisherUpgradeTest` covers newer identities, missing checksums, explicit
version mismatches, historical health compatibility, notice retention, ZLS compiler
compatibility and new npm dependency locks. Existing historical policy and transaction
tests continue to protect old receipts and failed-install recovery. Host verification
does not replace Android/PRoot installation and authenticated agent testing.
