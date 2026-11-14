# Termux terminal components

Droide vendors the pinned `terminal-emulator` and `terminal-view` components from Termux v0.118.0. Their matching source, upstream license statement, provenance records and locally modified source are retained here. The ordinary Java emulator dependency is `app/libs/terminal-emulator-v0.118.0-droide-input.aar`; the selected mode and verification dependencies are defined in `app/build.gradle.kts`.

## Source and provenance

- `UPSTREAM_PROVENANCE.json` binds the original artifacts and source identities.
- `SHA256SUMS`, source archives and rebuild receipts preserve the recorded artifact checksums.
- `patches/` and `native/` retain modified Java/native source and its recorded provenance.
- `derivatives/` contains separate private variants; their receipts apply only to their exact inputs.
- `tools/verify_termux_provenance.py`, `tools/verify_termux_pty_derivative.py` and related build-mode checks validate the selected inputs.

Do not infer current build readiness from the age of a receipt or a successful host test. Ordinary AARs, host-only test JARs and explicit private derivatives have different acceptance gates. Source changes require fresh verification for the intended artifact; Android PTY/lifecycle behavior requires device tests.

## Licensing

Upstream `termux-app` is GPLv3-only by default, with an explicit Apache-2.0 Terminal Emulator exception for `terminal-view` and `terminal-emulator`. Droide includes those components, not `termux-shared`. Preserve the complete upstream license statement, Apache-2.0 license, corresponding source archives and provenance on redistribution.

Historical development reports are retained in the external development archive. Current source build guidance is in [Development](../../docs/DEVELOPMENT.md).
