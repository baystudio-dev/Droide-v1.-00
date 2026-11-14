# Droide release and compliance

Droide v1.00 is maintained by **BayStudio** with engineering by **BayZov**. Droide-owned source code and original project material use the GNU Lesser General Public License version 3 only (LGPL-3.0-only); third-party components retain their respective licenses.

## Public source release

Passing `tools/verify_release_source.sh` establishes structural release checks only, not legal clearance or complete distribution compliance. Internal process artifacts, build caches and development-only notes are excluded. Functional source, build configuration, third-party provenance, corresponding-source material and legal notices remain preserved.

## Third-party license closure

The release source uses three complementary inventories:

1. `app/src/main/assets/legal/open_source_licenses.json` for direct dependencies and the in-app legal browser.
2. `app/src/main/assets/legal/resolved_runtime_licenses.json` for every locked Gradle `releaseRuntimeClasspath` module.
3. `app/src/main/assets/legal/runtime_legal_inventory.json` for current bundled Ubuntu/PRoot/XZ runtime inputs and package-level source identities.

The current Gradle runtime closure contains 136 mapped modules. The current bundled runtime inventory contains 91 Ubuntu Base packages. The exact two retired Alpine runtime inputs, historical 16-package Alpine minirootfs and 52-package QEMU inventories, notices and source-offer evidence remain outside packaged assets under `third_party/native-engines/qemu/retired-packaged-runtime-2026-10-16/`. QEMU source, pinned APKINDEX snapshots and aports recipes remain unchanged. Unknown or stale active entries and reintroduced retired assets fail release verification.

Copyleft source/notice obligations are preserved through bundled matching source where practical plus `app/src/main/assets/legal/COPYLEFT_SOURCE_OFFER.md`. Sora remains LGPL-2.1-or-later; current PRoot/Ubuntu and historical Alpine/QEMU components retain their upstream licenses and source-offer obligations. The LGPL-3.0-only first-party license applies only to Droide-owned code/material.

## Brand assets

Provider/tool marks are nominative identification only and do not imply affiliation. models.dev/LobeHub logo sources are pinned with provenance. Marks without a sufficiently clear redistribution basis are not bundled; specifically, vLLM uses a neutral Droide glyph rather than the unlicensed media-kit logo.

## Binary release boundary

The changed current/historical runtime inventories must pass the release-source gates before license closure is reported for this revision. Public APK/AAB distribution remains fail-closed until strict compilation/tests/lint, signing, privacy/terms/store declarations, physical-device certification and the independent SPAKE2 interoperability/cryptographic review are complete.

The embedded compliance copy is `app/src/main/assets/legal/DISTRIBUTION_COMPLIANCE.md`.

## First-party LGPLv3 release requirements

Before distributing a new binary covered by this revision, provide the complete corresponding first-party source for that exact binary, including modifications, build/install scripts and the applicable dependency/provenance information. Package both LGPLv3 and GPLv3 with the binary. Provide the source through a method permitted by the applicable license; a private development backup or an unverified repository link is not by itself evidence of public distribution compliance. Do not claim fulfillment until recipients can actually obtain the required materials.

For an Application combined with an LGPL library, evaluate LGPLv3 section 4: preserve notices, allow modification and reverse engineering for debugging such modifications, and provide the required relinking/recombination materials or a suitable shared-library mechanism. Provide installation information when required by GPLv3 section 6 as modified by LGPLv3. Shipping Android native .so files alone does not prove these requirements. Do not publish release-signing private keys; establish a lawful, documented rebuild/install path instead.

Preserve all third-party notices and existing source offers. Earlier copies lawfully received under MIT keep their permissions. Verify contributor provenance and redistribution rights before publication; no assignment or contributor consent is created by this file. These are release checks, not a legal opinion or a declaration that compliance is already complete.

License references: https://www.gnu.org/licenses/lgpl-3.0.html and https://www.gnu.org/licenses/gpl-3.0.html . Application guidance: https://www.gnu.org/licenses/gpl-howto.html .
