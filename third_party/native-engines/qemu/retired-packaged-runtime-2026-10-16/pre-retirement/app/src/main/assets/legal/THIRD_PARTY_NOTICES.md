# Droide third-party notices

Droide v1.00 includes software and assets owned by their respective upstream authors. This notice preserves attribution and distribution obligations; it does not relicense third-party work or grant trademark rights.

## Application and Gradle runtime

The machine-readable application catalog is `legal/open_source_licenses.json`. Every directly declared runtime dependency in `app/build.gradle.kts` must map exactly once to that catalog. The exact Gradle `releaseRuntimeClasspath` closure is separately recorded in `legal/resolved_runtime_licenses.json` and verified against `app/gradle.lockfile`.

The current locked release runtime contains 133 Gradle modules. The closure includes AndroidX/Compose, Kotlin/Kotlinx, OkHttp/Okio, Kadb compatibility artifacts, HiddenApiBypass, Bouncy Castle, Eclipse JGit/JDT annotations, jsoup, Coil, Sora Editor, Gson, Accompanist, Error Prone annotations, Guava listenablefuture, JavaEWAH, Apache Commons Codec, Joni/JCodings, JSpecify, SLF4J and SnakeYAML Engine. Each resolved coordinate maps to a packaged license family and source/evidence locator. Unknown coordinates fail the release gate.

Termux `terminal-emulator` and `terminal-view` 0.118.0 use the upstream Apache-2.0 Terminal Emulator exception. Droide verifies the pinned upstream source/class/native scope offline. XZ for Java 1.12 is 0BSD. Sora Editor 0.24.6 is LGPL-2.1-or-later. Eclipse JDT annotations are EPL-2.0.

The Android-specific JGit derivative is `com.baystudio.compat:jgit-android-compat:6.10.1.202505221210-r-droide-reconstruction1`, reconstructed from upstream `org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r`, commit `9d12b89dd9e7c17eb958d923dde4001995c47a5c`. Its four source adaptations use Android's real PID for GC, omit desktop JMX registration/annotation while preserving counters, and explicitly omit Kerberos/GSS negotiation while retaining Basic/Digest authentication. This is an Android-specific derivative, not full desktop JGit API compatibility. Original signed upstream bytes, matching sources, patch, build recipe and measured reproducibility receipt are retained in `third_party/jgit/`. The derivative JAR SHA-256 is `34ddf32173ff109392e7b123aae599426cd846aee02bb28d6fff550bd141ed3b`; the receipt records compilation, not Android runtime certification.

JGit retains its Eclipse Distribution License 1.0 (BSD-3-Clause) and bundled SHA-1 UbcCheck MIT notice. `legal/licenses/JGit-Android-Compatibility-NOTICE.txt` displays both complete notices and the derivative attribution. The original `JGit-EDL-1.0.txt` is unchanged, and `JGit-UPSTREAM-ABOUT.html` is an exact copy of upstream `about.html`. JavaEWAH 1.2.3, SLF4J 1.7.36 and Commons Codec 1.17.0 remain the original three direct transitive dependencies; JDT annotations originate separately from Sora's language-textmate dependency.

Droide's separate `terminal-emulator-v0.118.0-droide-input.aar` contains a local Java-only `TerminalSession` input-owner hook dated 2026-10-08. It routes user and Agent input through the application's bounded writer; the worker forwarding method reports queue acceptance and rejects main-thread use. The original AARs/source JARs, other Java classes and JNI bytes are preserved. Complete local source, diff and reproducible offline build evidence are retained in `third_party/termux/patches/` under the same Apache-2.0 module license.

## Native Linux and virtual-machine runtime

Droide bundles PRoot/OpenMinis, talloc, Linux rootfs archives and an Alpine QEMU package set. `legal/runtime_legal_inventory.json` is generated from the exact shipped bytes.

- PRoot/OpenMinis runtime and loaders: GPL v2 scope, with matching source under `third_party/native-engines/proot/`.
- talloc 2.4.2: LGPL-3.0-or-later, with matching source under `third_party/native-engines/proot/`.
- QEMU 11.0.3: GPL v2, with matching source under `third_party/native-engines/qemu/`.
- Alpine minirootfs: 16 installed packages, each with package-declared license and exact aports source locator.
- Alpine QEMU offline pack: 52 APK packages, each with hash, declared license, repository and source-recipe locator.
- Ubuntu Base 24.04.5 arm64: 91 installed packages mapped to exact Ubuntu source package/version pages.

The PRoot engine includes a targeted outer-seccomp compatibility backport based on Termux PRoot commit `a179d3e8a4e045aaa1fb8cc3284f23509d96d353`. The original OpenMinis source archive, patch, offline Android build script and instructions are preserved under `third_party/native-engines/proot/` and hash-bound by the runtime inventory. The reviewed Termux loader binaries are retained unchanged.

Aggregate archives do not relicense their components. See `legal/licenses/Runtime-Aggregates-NOTICE.md` and `legal/COPYLEFT_SOURCE_OFFER.md`.

## Corresponding source

BayStudio provides corresponding source for copyleft binaries distributed with Droide. PRoot/OpenMinis, talloc and QEMU source archives are already present in the public source tree. For aggregate Alpine/Ubuntu components not already mirrored as source archives, the runtime inventory records exact source identities/locators and the written source offer remains valid for at least three years after the last BayStudio distribution of the relevant binary version.

## Provider, model and development-tool identity marks

Provider and tool marks are used only for nominative identification. Inclusion does not imply sponsorship, endorsement or affiliation. Copyright licenses for logo datasets do not grant trademark rights.

The large provider catalog is pinned to source SVGs from `anomalyco/models.dev` and LobeHub lobe-icons with commit/path/hash provenance under `third_party/models-dev-logos/`. Their source repositories are MIT-licensed. Separate trademark rights remain with the relevant providers.

The vLLM provider deliberately uses Droide's neutral generic provider glyph. The previously reviewed vLLM media-kit logo is not redistributed because the media-kit does not currently publish an explicit license for those logo assets.

Other selected marks retain their documented upstream source and brand-use conditions in `legal/BRAND_ASSET_TERMS.md`. Full license texts are available through **Settings → About & Legal → Open Source Licenses**.

## Integrity and release gates

`third_party/ARTIFACTS_SHA256.txt` hash-binds vendored third-party files. The release source must pass dependency policy (including the offline JGit derivative/receipt check), resolved-runtime license closure, native/rootfs runtime license closure, third-party integrity, Termux provenance and provider-logo provenance checks. License metadata is fail-closed: changing direct dependencies, the Gradle lock graph, bundled runtime bytes or pinned provider-logo manifests requires an intentional legal inventory update.
