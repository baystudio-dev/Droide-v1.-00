# Release and compliance

Droide-owned code is licensed under **LGPL-3.0-only**. Third-party components retain their respective licenses. The complete license texts, notices and attribution are available in the application and under `app/src/main/assets/legal/`.

## Source distribution

Publish the corresponding source for each distributed binary, including the build scripts, modifications and required dependency information. Keep the release tag, artifact checksum, public signing-certificate fingerprint and matching optimization mapping associated with that release. Keep signing private keys and credentials in restricted storage outside the repository.

Required materials include `LICENSE`, `COPYING`, `COPYING.LESSER`, `LICENSE_SCOPE.md`, dependency notices, source provenance and any applicable source offers. Existing source offers continue to apply to previously distributed versions.

## Dependency inventories

- `open_source_licenses.json` maps direct runtime components to packaged license texts.
- `resolved_runtime_licenses.json` covers the locked Gradle release runtime dependency closure.
- `runtime_legal_inventory.json` records bundled workstation components and source locators.
- `third_party/ARTIFACTS_SHA256.txt` identifies the retained third-party files.

All paths above are relative to `app/src/main/assets/legal/`, except the third-party manifest. Required upstream notices and provenance remain verbatim where integrity checks require their exact identity.

## Distribution checklist

Use [the release guide](docs/RELEASING.md) for source, dependency, test, lint, signing and artifact checks. Complete device validation for the intended distribution. Review the packaged [distribution notice](app/src/main/assets/legal/DISTRIBUTION_COMPLIANCE.md), [source offer](app/src/main/assets/legal/COPYLEFT_SOURCE_OFFER.md), privacy information and brand terms.

For LGPL-covered combinations, preserve the required notices and recipients' modification and debugging rights, and provide the applicable relinking or replacement mechanism. Provide installation information when the applicable license requires it. A successful build, private backup or native shared library alone does not establish distribution compliance.
