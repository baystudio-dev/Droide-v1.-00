# Droide distribution compliance status

**Current version:** Droide v1.00 (`versionCode 44`)  
**Third-party license status:** **CURRENT/HISTORICAL INVENTORIES SEPARATED; THIS REVISION REQUIRES RELEASE-SOURCE VERIFICATION**  
**Overall binary release status:** **FAIL-CLOSED UNTIL NON-LICENSE RELEASE CHECKS PASS**

The current runtime inventory and retained historical distribution evidence are described below. This revision must pass the release-source gates before license closure is reported as verified. These source contracts are not legal advice and do not substitute for jurisdiction-specific trademark/privacy/store review.

## First-party license

Droide-owned source code and original project material are licensed under the GNU Lesser General Public License version 3 only (LGPL-3.0-only). Third-party software and assets remain under their respective licenses. See the repository `LICENSE`, `LICENSE_SCOPE.md` and packaged `legal/DROIDE_LICENSE.md`.

## Application dependency closure

`legal/open_source_licenses.json` maps every directly declared runtime dependency exactly once. `legal/resolved_runtime_licenses.json` maps every module in the locked Gradle `releaseRuntimeClasspath`; the current locked closure contains 136 modules and unknown coordinates fail the release gate.

The catalog packages the required license families, including Apache-2.0, MIT, BSD-3-Clause, EPL-2.0, MPL-2.0, LGPL-2.1-or-later, 0BSD and component-specific Bouncy Castle/JGit terms. XZ for Java 1.12 is mapped as 0BSD and the actual Java-only Termux emulator AAR plus terminal-view AAR are mapped under the upstream Apache-2.0 Terminal Emulator exception.

JGit uses the explicitly identified `6.10.1.202505221210-r-droide-reconstruction1` Android derivative. Its original upstream EDL/BSD text is unchanged, its complete bundled UbcCheck MIT notice is included in the license display, and upstream `about.html` is packaged byte-for-byte. `third_party/jgit/` contains the original signed JAR, source JAR, verified commit sources, four-file patch, licenses, deterministic JVM recipe and measured build receipt. No Android runtime certification is implied by that receipt. The default offline `verifyJGitAndroidCompat` task gates application `preBuild`/`check` and dependency-input collection; dependency policy also validates it. Neither gate performs a rebuild or accepts source-only verification.

## Offline legal browser

About & Legal now includes the direct catalog plus 136 resolved modules, retained standalone notices and original copyright/license texts for all 91 bundled Ubuntu packages. Supplementary NOTICE files open with their corresponding license. The generated catalog is checked by `tools/generate_legal_browser_catalog.py --check`; its presence does not certify publication or native runtime readiness.

## Copyleft and aggregate runtime

`legal/runtime_legal_inventory.json` is generated from current bundled Ubuntu/PRoot/XZ runtime inputs. It retains 91 Ubuntu Base package records, including source identities/locators and embedded copyright paths. The two retired Alpine binary inputs are outside the Android asset tree. Their unchanged historical inventory, 16 minirootfs and 52 QEMU package records, notices, hashes and source offer are preserved under `third_party/native-engines/qemu/retired-packaged-runtime-2026-10-16/`.

Matching current PRoot/OpenMinis/talloc source archives remain under `third_party/native-engines/proot/`. Historical QEMU source, Alpine package indexes and retired binaries are retained separately in the stable source archive identified by `third_party/native-engines/qemu/retired-packaged-runtime-2026-10-16/EXTERNAL_ARCHIVE.json`. Current source checks verify the historical notices and archive index; checking the archived payload bytes additionally requires that ZIP. `legal/COPYLEFT_SOURCE_OFFER.md` continues to cover corresponding source not already mirrored in the current source release, including historical Alpine/QEMU distributions, and remains valid for at least three years after the last BayStudio distribution of the relevant binary version. No expiry is inferred from retiring packaged inputs.

Sora Editor 0.24.6 remains LGPL-2.1-or-later. Droide's public source, pinned dependency declarations and replaceable build path preserve recipients' ability to rebuild against a compatible modified Sora library; the LGPL license and notice remain packaged.

Eclipse JDT annotations in the resolved graph are EPL-2.0 and the full EPL-2.0 text is packaged. Their upstream source locator is recorded in the resolved-runtime inventory.

## Provider and tool marks

Provider/tool marks are used only for identification; trademark rights remain with their owners. Pinned models.dev/LobeHub source assets retain commit/path/hash provenance. The vLLM media-kit mark is intentionally not redistributed because its media-kit currently lacks an explicit logo license; vLLM uses Droide's neutral generic provider glyph instead.

See `legal/BRAND_ASSET_TERMS.md` and `legal/THIRD_PARTY_NOTICES.md`.

## Supply-chain controls

The source pins the Gradle distribution checksum, immutable GitHub Action SHAs, dependency locks, verification metadata and vendored artifact hashes. `tools/verify_dependency_policy.py`, `tools/verify_resolved_runtime_licenses.py`, `tools/verify_runtime_licenses.py`, `tools/verify_third_party_integrity.py` and `tools/verify_termux_provenance.py` are mandatory release gates.

A dependency, lockfile, bundled runtime or pinned-logo provenance change must fail until its license/provenance inventory is intentionally updated.

## Non-license release blockers

Runtime legal/source verification remains required for this revision. Public APK/AAB publication also requires the normal technical/product release checks documented by the project, including strict build/test/lint, signing, privacy/terms/store declarations, physical-device certification and the independent SPAKE2 interoperability/cryptographic review.

## First-party LGPLv3 release requirements

Before distributing a new binary covered by this revision, provide the complete corresponding first-party source for that exact binary, including modifications, build/install scripts and the applicable dependency/provenance information. Package both LGPLv3 and GPLv3 with the binary. Provide the source through a method permitted by the applicable license; a private development backup or an unverified repository link is not by itself evidence of public distribution compliance. Do not claim fulfillment until recipients can actually obtain the required materials.

For an Application combined with an LGPL library, evaluate LGPLv3 section 4: preserve notices, allow modification and reverse engineering for debugging such modifications, and provide the required relinking/recombination materials or a suitable shared-library mechanism. Provide installation information when required by GPLv3 section 6 as modified by LGPLv3. Shipping Android native .so files alone does not prove these requirements. Do not publish release-signing private keys; establish a lawful, documented rebuild/install path instead.

Preserve all third-party notices and existing source offers. Earlier copies lawfully received under MIT keep their permissions. Verify contributor provenance and redistribution rights before publication; no assignment or contributor consent is created by this file. These are release checks, not a legal opinion or a declaration that compliance is already complete.

License references: https://www.gnu.org/licenses/lgpl-3.0.html and https://www.gnu.org/licenses/gpl-3.0.html . Application guidance: https://www.gnu.org/licenses/gpl-howto.html .
