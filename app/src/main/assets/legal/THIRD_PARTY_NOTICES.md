# Droide third-party notices

Droide v1.00 includes software and assets owned by their respective upstream authors. This notice preserves attribution and distribution obligations; it does not relicense third-party work or grant trademark rights.

## Application and Gradle runtime

The machine-readable application catalog is `legal/open_source_licenses.json`. Every directly declared runtime dependency in `app/build.gradle.kts` must map exactly once to that catalog. The exact Gradle `releaseRuntimeClasspath` closure is separately recorded in `legal/resolved_runtime_licenses.json` and verified against `app/gradle.lockfile`.

The current locked release runtime contains 136 Gradle modules. The closure includes AndroidX/Compose, Kotlin/Kotlinx, OkHttp/Okio, Kadb compatibility artifacts, HiddenApiBypass, Bouncy Castle, Eclipse JGit/JDT annotations, jsoup, Coil, Sora Editor, Gson, Accompanist, Error Prone annotations, Guava listenablefuture, JavaEWAH, Apache Commons Codec, Joni/JCodings, JSpecify, SLF4J and SnakeYAML Engine. Each resolved coordinate maps to a packaged license family and source/evidence locator. Unknown coordinates fail the release gate.

Termux `terminal-emulator` and `terminal-view` 0.118.0 use the upstream Apache-2.0 Terminal Emulator exception. Droide verifies the pinned upstream source/class/native scope offline. XZ for Java 1.12 is 0BSD. Sora Editor 0.24.6 is LGPL-2.1-or-later. Eclipse JDT annotations are EPL-2.0.

The Android-specific JGit derivative is `com.baystudio.compat:jgit-android-compat:6.10.1.202505221210-r-droide-reconstruction1`, reconstructed from upstream `org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r`, commit `9d12b89dd9e7c17eb958d923dde4001995c47a5c`. Its four source adaptations use Android's real PID for GC, omit desktop JMX registration/annotation while preserving counters, and explicitly omit Kerberos/GSS negotiation while retaining Basic/Digest authentication. This is an Android-specific derivative, not full desktop JGit API compatibility. Original signed upstream bytes, matching sources, patch, build recipe and measured reproducibility receipt are retained in `third_party/jgit/`. The derivative JAR SHA-256 is `34ddf32173ff109392e7b123aae599426cd846aee02bb28d6fff550bd141ed3b`; the receipt records compilation, not Android runtime certification.

JGit retains its Eclipse Distribution License 1.0 (BSD-3-Clause) and bundled SHA-1 UbcCheck MIT notice. `legal/licenses/JGit-Android-Compatibility-NOTICE.txt` displays both complete notices and the derivative attribution. The original `JGit-EDL-1.0.txt` is unchanged, and `JGit-UPSTREAM-ABOUT.html` is an exact copy of upstream `about.html`. JavaEWAH 1.2.3, SLF4J 1.7.36 and Commons Codec 1.17.0 remain the original three direct transitive dependencies; JDT annotations originate separately from Sora's language-textmate dependency.

Droide's locally adapted Termux terminal engine includes input ownership, bounded queues/parsing/memory and owned-process/PTY lifecycle changes. Original upstream AARs and source JARs are retained unchanged as provenance. Local Java and native sources, patches and build recipes are retained under `third_party/termux/patches/`, `third_party/termux/native/` and `tools/` under the same Apache-2.0 module exception. Each build path has separate output evidence; historical receipts do not certify newer source or native outputs.


OkHttp 4.12.0 includes Public Suffix List data under MPL-2.0 in addition to its Apache-2.0 code. The exact embedded Public Suffix NOTICE and full MPL-2.0 text are displayed with OkHttp. The upstream notice identifies the public source at https://publicsuffix.org/list/public_suffix_list.dat.
## Current native Linux runtime

Droide bundles PRoot/OpenMinis, talloc and Ubuntu Base. `legal/runtime_legal_inventory.json` is generated from the exact current runtime inputs. The Alpine minirootfs and Alpine QEMU offline pack are no longer in the Android asset tree.

- PRoot/OpenMinis runtime and loaders: GPL v2 scope, with matching source under `third_party/native-engines/proot/`.
- talloc 2.4.2: LGPL-3.0-or-later, with matching source under `third_party/native-engines/proot/`.
- Ubuntu Base 24.04.5 arm64: 91 installed packages mapped to exact Ubuntu source package/version pages.

The PRoot engine includes a targeted outer-seccomp compatibility backport based on Termux PRoot commit `a179d3e8a4e045aaa1fb8cc3284f23509d96d353`. The original OpenMinis source archive, patch, offline Android build script and instructions are preserved under `third_party/native-engines/proot/` and hash-bound by the runtime inventory. The reviewed Termux loader binaries are retained unchanged.

Aggregate archives do not relicense their components. See `legal/licenses/Runtime-Aggregates-NOTICE.md` and `legal/COPYLEFT_SOURCE_OFFER.md`.

## Historical Alpine/QEMU provenance and corresponding source

The retired QEMU 11.0.3 source, Alpine APKINDEX snapshots and two retired runtime binaries are preserved in a separate stable source archive. `third_party/native-engines/qemu/retired-packaged-runtime-2026-10-16/EXTERNAL_ARCHIVE.json` records its identity and every removed payload's path, size and checksum. The aports recipes and unchanged pre-retirement inventory/notices/source offer remain in the current tree. That historical inventory records 16 Alpine minirootfs and 52 Alpine QEMU packages, including licenses, hashes and exact source-recipe locators. It does not describe current Ubuntu runtime binaries.

BayStudio provides corresponding source for copyleft binaries distributed with Droide. PRoot/OpenMinis and talloc source archives remain in the current source tree; historical QEMU source is retained in the archive above and covered by the source-request process in `legal/COPYLEFT_SOURCE_OFFER.md`. Current Ubuntu source identities are in `legal/runtime_legal_inventory.json`; historical Alpine identities are in the archived pre-retirement inventory. For aggregate components not already mirrored as source archives, the written source offer remains valid for at least three years after the last BayStudio distribution of the relevant binary version. Retirement does not establish that this period has expired.

## Provider, model and development-tool identity marks

Provider and tool marks are used only for nominative identification. Inclusion does not imply sponsorship, endorsement or affiliation. Copyright licenses for logo datasets do not grant trademark rights.

The large provider catalog is pinned to source SVGs from `anomalyco/models.dev` and LobeHub lobe-icons with commit/path/hash provenance under `third_party/models-dev-logos/`. Their source repositories are MIT-licensed. Separate trademark rights remain with the relevant providers.

The vLLM provider deliberately uses Droide's neutral generic provider glyph. The previously reviewed vLLM media-kit logo is not redistributed because the media-kit does not currently publish an explicit license for those logo assets.

Other selected marks retain their documented upstream source and brand-use conditions in `legal/BRAND_ASSET_TERMS.md`. Full license texts are available through **Settings → About & Legal → Open Source Licenses**.

## File-type artwork

Material Icon Theme file-type artwork, Copyright (c) 2025 Material Extensions, is distributed under its upstream MIT License, retained in `legal/licenses/Material-Icon-Theme-MIT.txt`. Source: https://github.com/material-extensions/vscode-material-icon-theme at commit `13025adc5ad10a5cc649528a2c1edec93069f1b9`; exact SVGs, upstream license/README and source hashes are retained in `third_party/material-document-icons/`.

Droide converts the selected language, configuration, document, media, archive, font, and security file-type SVGs to Android VectorDrawable, preserving path data, circle geometry, translations, viewports, and fill colors. Word blue is adjusted from #01579b to #1e88e5 for dark/light visibility. These are upstream file-type representations, not official Adobe or Microsoft assets. All product, project, and language marks remain with their respective owners. Copyright licensing does not grant trademark rights, sponsorship, endorsement, affiliation or public-release clearance. The icons identify file types; they do not add viewer or execution capabilities.

## Reviewed Sqruff source notice

The optional managed SQL recipe refers to original Sqruff 0.41.0 from Quary Labs, source commit `aa4c5730d697794ffaf72a4c187d64522e5820c7`: https://github.com/quarylabs/sqruff/tree/aa4c5730d697794ffaf72a4c187d64522e5820c7.

Its reviewed upstream Apache License 2.0 text is preserved byte-for-byte in `legal/licenses/Sqruff-0.41.0-Upstream-LICENSE.txt`, SHA-256 `954c30259208c39cf74febfd8e7466e1a9bf6479c6aa7a7e85f1e39afce7bda7`. This supplementary source notice is outside the original publisher payload tree. It does not change or repackage that payload, and does not establish complete transitive binary notices, an exact binary SBOM, original-artifact acquisition, or native/device acceptance. Those remain separate release gates.

## Integrity and release gates

`third_party/ARTIFACTS_SHA256.txt` hash-binds vendored third-party files. The release source must pass dependency policy (including the offline JGit derivative/receipt check), resolved-runtime license closure, native/rootfs runtime license closure, third-party integrity, Termux provenance and provider-logo provenance checks. License metadata is fail-closed: changing direct dependencies, the Gradle lock graph, bundled runtime bytes or pinned provider-logo manifests requires an intentional legal inventory update.

## Reviewed Svelte source reconciliation notice

The optional managed recipe identifies original svelte-language-server 0.18.5 from Svelte's language-tools repository, commit `b1ab06379bca7d96f64f4c2063ffc3c28c060148`: https://github.com/sveltejs/language-tools/tree/b1ab06379bca7d96f64f4c2063ffc3c28c060148.

The source package declares MIT. The pinned repository LICENSE grants the MIT terms to the language-tools contributors and is retained without modification in `legal/licenses/Svelte-Language-Server-0.18.5-Upstream-LICENSE.txt`, SHA-256 `ada595bb2ec8d71616dde1b956edb8fbad63eadab32b8d9ab9310a1aef08e5c5`. Source identity, metadata identity, packed publisher bytes and npm provenance are separate claims. No original Svelte/npm runtime payload is bundled by this source change, and the 64-identity install policy is not a packed-byte license inventory. Any later original-payload distribution must reconcile each selected archive's actual license and notice contents, the reviewed formatter alias, transitive dependencies and original provenance before release. This supplementary notice does not establish native/device acceptance or replace those remaining gates.

## CPython 3.12.3 Unicode word classification data

The CMake native-completion adapter includes a finite word-classification table derived from CPython 3.12.3's Unicode 15.0.0 source data (748 inclusive intervals, 137,936 code points). This is data used to match Python's regular-expression word-prefix behavior; it does not embed or execute CPython. The upstream CPython license is retained in `licenses/CPython-3.12.3-LICENSE.txt`. Upstream: https://github.com/python/cpython/tree/v3.12.3 . This notice covers the derived table only; managed Python/CMake language-server wheel and runtime license verification is separate.

## Nim fixed-toolchain analysis (R97)

- nimlangserver 1.16.0, source 58168102d92d5742c39ab692aeb7bdbd7cf8df9a: MIT; retained in licenses/nimlangserver-1.16.0-LICENSE.txt.
- Nim/nimsuggest 2.2.12, source 8e8fbf60693418dc95bb0d762fd660231d08a583: retained original copying.txt in licenses/Nim-2.2.12-copying.txt.
- Nimble 0.24.1, source a399f502dec7ffcd905c1cf54b13274ad990bada: BSD-3-Clause; licenses/Nimble-0.24.1-license.txt.
- Private original Node 22.23.3, source 80dc632040e6bada37aac1220dde9c79581c9c22: full MIT and third-party text retained in licenses/Node-22.23.3-LICENSE.txt. npm/npx/corepack stay original data and are not managed CLI commands.

All original archive trees and their notices are preserved at authorized installation. These retained source notices are not a fabricated binary SBOM or a claim that uninspected compiled dependency notices are complete. Exact original archive/layout/config/ELF/library admission and target-native acceptance remain separate. The fixed analysis route leaves interactive terminal Nim and Node selections unchanged.


## Vue fixed paired analysis source integration (R98)

One configuration conditionally uses original Vue language-tools 3.3.13 (MIT), Volar language-server 2.4.28 (MIT), TypeScript 6.0.3 (Apache-2.0), and typescript-language-server 6.0.2 (Apache-2.0 with MIT Microsoft VS Code copied portions). Exact source license texts are retained beside these notices. The independent runtime.vue-helper 22.23.3 uses the same accepted original Node distribution under its own receipt/tree; Node and every shipped third-party notice are retained verbatim. No npm/lifecycle scripts are run during archive projection.

Sources: https://github.com/vuejs/language-tools/tree/v3.3.13 ; https://github.com/volarjs/volar.js/tree/v2.4.28 ; https://github.com/microsoft/TypeScript/tree/v6.0.3 ; https://github.com/typescript-language-server/typescript-language-server/tree/7b28f104e741feba092045550738e8549e4ccb11 .

The exact npm data contract contains 89 authenticated archive identities in 90 physical placements, preserving all original license, notice, README, source-map and comment data. Original package artifacts have not been opened or executed in this source checkpoint. The published TS-LSP artifact is bundled: absence of declared npm runtime dependencies does not establish absence of embedded third-party code. Its exact source/build-lock review identifies 28 bounded candidate components, not a complete bundle SBOM or complete published-bundle license closure. Actual staged map/source identities and supplementary exact notices remain a redistribution/release gate. This source checkpoint does not claim that unresolved attribution is complete.

## C# original-artifact profile (R99 source policy)

The optional C# profile specifies csharp-ls 0.28.0 (source commit
72671fc7fa810c158864e72485db68e8b9b25c3d), .NET SDK 10.0.402 with runtime
10.0.13, and an independently receipt-owned Node 22.23.3 helper. These are
three original artifacts and one server configuration. Their whole original
layouts and notices are retained. SDK/tool publisher SHA-512 and helper
publisher SHA-256 are distinguished from locally computed SHA-256 hashes.
Embedded NuGet .signature.p7s is retained as data, with no certificate-chain,
timestamp, revocation or signature verification claim.

The csharp-ls source license is in licenses/csharp-ls-0.28.0-LICENSE.txt.
licenses/dotnet-sdk-10.0.402-SOURCE-NOTICES.txt records references and limits.
These source references do not establish complete shipped-component attribution.
Unknown original dependency, configuration, native or component-notice facts
fail activation. Source-only tests do not claim original payload, native,
Android, Ubuntu/PRoot device, SDK or project execution acceptance.

## Optional Taplo TOML WASM route

The exact original @taplo/lsp 0.8.0 and @taplo/core 0.2.0 downloads retain all original files and notices. The supplemental Taplo-WASM-0.8.0-THIRD-PARTY-NOTICES.txt conservatively includes 244 source candidates; it is not a linked SBOM. Taplo-WASM-0.8.0-NOTICE-FULFILLMENT.txt retains additional terms and the exact freely accessible webpki-roots 0.22.4 preferred editable source obtain notice. Source availability is refreshed during authorized installation/publication, never ordinary LSP launch. Independent runtime.taplo-node 22.23.3 reuses the existing Node 22 license text. The source checkpoint does not claim actual original payload or device acceptance.

## Optional GraphQL local-schema original-library route

The exact five original packages are graphql-language-service 5.7.0, graphql 16.13.2, nullthrows 1.1.1, debounce-promise 3.1.2 and vscode-languageserver-types 3.17.1. Reviewed upstream source notices are retained in legal/licenses/GraphQL-5.7.0-NOTICES.txt, with provenance in GraphQL-5.7.0-NOTICE-COVERAGE.json. Future data-only installation preserves every original file and shipped notice. The independently owned runtime.graphql-node 22.23.3 preserves the full original Node LICENSE, README and tree. No original distribution has been acquired, imported or executed for this source checkpoint; distribution/ABI/native/device acceptance remains unrun. Historical metadata signatures do not establish current key trust, tarball authentication or release reproducibility.


## Optional Erlang ELP original-artifact route

ELP 2026-10-14 (source b36ba37346a8e6122fd309420cb67886ccc5871c) is available under MIT OR Apache-2.0. Exact source license texts and provenance are retained in legal/licenses/ELP-2026.10.14-NOTICES.txt and ELP-2026.10.14-NOTICE-COVERAGE.json. Original ARM64 OTP-27.3 archive bytes and its single regular elp member remain unchanged; source supplements are added separately under DROIDE-NOTICES. The selected OTP, Rebar and conditional CPython are independently preinstalled prerequisites with unverified provenance. The original native-image/transitive notice closure is unverified, and source root licenses do not establish full binary distribution compliance. No original archive/runtime was acquired or executed for this source checkpoint.

Source: https://github.com/WhatsApp/erlang-language-platform/tree/b36ba37346a8e6122fd309420cb67886ccc5871c

## Built-in Batch TextMate grammar (R106)

`droide/grammars/batchfile.tmLanguage.json` is the unchanged VS Code Batch grammar,
revision c44c5332b31c31eca0519ff6e7c5ebb9da0711e6, derived from Michael Mims
`language-batchfile` revision 6154ae25a24e01ac9329e7bcf958e093cd8733a9.
MIT license notices: `licenses/Batchfile-grammar-MIT.txt` and
`licenses/VSCode-language-data-MIT.txt`. Only grammar data is included, not VS Code
or a Windows runtime. Source and checksums: `docs/verification/r106-batch/research.json`.

## Built-in INI TextMate grammar (R107)

`droide/grammars/ini.tmLanguage.json` is unchanged VS Code language data at revision
c44c5332b31c31eca0519ff6e7c5ebb9da0711e6, derived from TextMate ini.tmbundle at
2af0cbb0704940f967152616f2f1ff0aae6287a6. Notices:
`licenses/TextMate-INI-grammar-LICENSE.txt` and `licenses/VSCode-language-data-MIT.txt`.
Source/checksums: `docs/verification/r107-ini/research.json`. No INI tool is bundled.

## Explorer TAR export (R120)

Apache Commons Compress 1.28.0 is used for streaming TAR/PAX encoding. Required runtime dependencies are Apache Commons Codec 1.19.0, Commons IO 2.20.0 and Commons Lang 3.18.0. Each upstream JAR's complete LICENSE and NOTICE are packaged under `legal/licenses/Apache-Commons-{Compress,Codec,IO,Lang}-{LICENSE,NOTICE}.txt`. All are Apache-2.0; the Compress LICENSE also preserves included third-party attributions. Optional compression/native dependencies are not selected. Exact artifact origins and registry checksum comparisons are recorded in `docs/verification/r120-explorer-export/dependency-provenance.json`.

## Goose editor data

The Goose TextMate grammar, language configuration and file icon are from aardappel/goose v2026.4, commit `6c1fbc788f6eb1f17db4cab998353d7540ed825d`, under Apache License 2.0. The original license is in `legal/licenses/Goose-Apache-2.0.txt`; sources and hashes are in `third_party/goose-editor-data/`. Icon paths are preserved and centered in an Android vector, with theme-aware monochrome color. No compiler or runtime is distributed with these editor assets.
