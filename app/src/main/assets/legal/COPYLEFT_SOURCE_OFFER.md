# Copyleft corresponding-source offer

Droide v1.00 distributes third-party components under GPL/LGPL and other source-availability licenses. BayStudio preserves the original licenses and offers recipients the complete corresponding source for the exact copyleft binaries distributed with Droide.

For current PRoot/OpenMinis/talloc distributions, exact source archives remain in the source tree under `third_party/native-engines/proot/`. Historical QEMU source, Alpine package indexes and retired runtime binaries are retained in a separate source archive identified by `third_party/native-engines/qemu/retired-packaged-runtime-2026-10-16/EXTERNAL_ARCHIVE.json`. That index records the archive identity, member paths, byte sizes and SHA-256 hashes; the retained archive is available from the maintainer through the source-request process below. Historical package identities, licenses, source-recipe locators and the unchanged prior source offer remain in that directory. For current Ubuntu aggregate packages, `legal/runtime_legal_inventory.json` records the exact binary package version and its source-package locator. Removing retired payloads from the current checkout does not withdraw this offer for previously distributed versions or establish that its validity period has expired.

The modified PRoot engine also includes its complete compatibility patch, offline build script and build instructions under `third_party/native-engines/proot/`. These files accompany the original source archives and are included in the runtime inventory.

For any copyleft binary distributed by BayStudio where corresponding source is not already present in the same release, BayStudio offers any third party a machine-readable copy of the complete corresponding source, including applicable build/install scripts, for no more than the physical cost of source distribution. This offer is valid for at least three years after the last BayStudio distribution of the binary version concerned.

Source requests may be filed through the issue tracker of the public Droide repository containing this notice with the title `Corresponding Source Request` and the Droide version/component requested. A public binary release must keep this notice accessible and must not remove package-level provenance or license notices.

For LGPL libraries linked with Droide, the public Droide source and pinned dependency declarations are intended to permit rebuilding with a modified compatible library. This notice does not change the license of Droide-owned code or any third-party component.
